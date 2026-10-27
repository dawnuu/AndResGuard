package com.bytedance.android.andresguard.apk;

import com.bytedance.android.andresguard.android.JarSigner;
import com.bytedance.android.andresguard.commands.ObfuscateResourcesCommand;
import com.bytedance.android.andresguard.executors.ImageCompressor;
import com.bytedance.android.andresguard.model.ResourcesMapping;
import com.bytedance.android.andresguard.obfuscation.ResGuardStringBuilder;
import com.bytedance.android.andresguard.parser.ResourcesMappingParser;
import com.bytedance.android.andresguard.utils.Utils;
import com.bytedance.android.andresguard.utils.VirusTotalUploader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Edits APK ZIP entries and resources.arsc directly, then aligns and signs the APK. */
public final class ApkProcessor {
    private static final Set<String> MEDIA = new HashSet<>(Arrays.asList(
            "png", "jpg", "jpeg", "webp", "gif", "bmp", "mp3", "wav", "ogg", "m4a", "aac", "mp4", "webm", "mkv", "avi"));
    private final ObfuscateResourcesCommand options;
    private final List<Pattern> whitelist = new ArrayList<>();

    public ApkProcessor(ObfuscateResourcesCommand options) {
        this.options = options;
        for (String rule : options.getWhiteList()) whitelist.add(Pattern.compile(Utils.convertToPatternString(rule)));
    }

    public Path execute() throws IOException, InterruptedException {
        AndroidBuildTools tools = new AndroidBuildTools(AndroidBuildTools.find(options.getApkBuildToolsDir().orElse(""), null));
        Path work = Files.createTempDirectory(options.getOutputPath().getParent(), "andresguard-apk-");
        try {
            Path reports = Files.createDirectories(work.resolve("reports"));
            Path processed = work.resolve("processed.apk");
            try (ZipFile input = new ZipFile(options.getInputPath().toFile())) {
                Map<String, ApkEntry> files = readEntries(input);
                require(files, "AndroidManifest.xml");
                require(files, "resources.arsc");
                BinaryResourceTable.checkStandaloneManifest(files.get("AndroidManifest.xml").bytes(input));
                BinaryResourceTable table = new BinaryResourceTable(files.get("resources.arsc").bytes(input));
                checkResourcePaths(table, files);
                filterFiles(files);
                filterStrings(table);
                Map<String, String> paths = new HashMap<>();
                if (options.getMergeDuplicatedResources().orElse(false)) mergeDuplicates(input, files, paths, reports);
                if (options.getEnableObfuscate()) obfuscate(table, files, paths, reports);
                table.replaceFilePaths(paths);
                checkResourcePaths(table, files);
                // These options describe AAB structures and intentionally do not remove APK entries.
                if (options.getRemoveRootFiles().orElse(false))
                    System.out.println("APK: removeRootFiles applies only to AAB; APK supplemental files are retained.");
                files.get("resources.arsc").content = table.write();
                files.get("resources.arsc").method = ZipEntry.STORED;
                writeApk(input, files, processed);
            }
            Path aligned = work.resolve("aligned.apk");
            tools.run("zipalign", "-P", "16", "-f", "4", processed.toString(), aligned.toString());
            if (!options.getDisableSign().orElse(false)) sign(tools, aligned);
            tools.run("zipalign", "-c", "-P", "16", "4", aligned.toString());
            try {
                Files.move(aligned, options.getOutputPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(aligned, options.getOutputPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            copyReports(reports, options.getOutputPath().getParent());
            if (options.getEnableVirusTotalUpload().orElse(false))
                VirusTotalUploader.upload(options.getVirusTotalApiKey().orElse(""), options.getOutputPath().toFile());
            System.out.println("APK resources processed: " + options.getOutputPath());
            return options.getOutputPath();
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(work)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }

    private static Map<String, ApkEntry> readEntries(ZipFile input) throws IOException {
        Map<String, ApkEntry> files = new TreeMap<>();
        Set<String> seen = new HashSet<>();
        Enumeration<? extends ZipEntry> entries = input.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!seen.add(entry.getName())) throw new IOException("Duplicate APK entry: " + entry.getName());
            if (!entry.isDirectory() && !isSignature(entry.getName())) files.put(entry.getName(), new ApkEntry(entry));
        }
        return files;
    }

    private void filterFiles(Map<String, ApkEntry> files) {
        if (!options.getFilterFile().orElse(false)) return;
        for (String rule : options.getFileFilterRules().orElse(Collections.emptySet())) {
            Pattern pattern = Pattern.compile(Utils.convertToPatternString(rule));
            for (String path : new ArrayList<>(files.keySet())) {
                if (!pattern.matcher(path).matches()) continue;
                if (path.equals("AndroidManifest.xml") || path.equals("resources.arsc") || path.matches("classes(\\d+)?\\.dex")
                        || path.startsWith("res/") || path.startsWith("assets/"))
                    throw new UnsupportedOperationException(path + " entry can not be filtered, please check the filter rule [" + rule + "].");
                files.remove(path);
                System.out.println("[filter] APK file: " + path);
            }
        }
    }

    private void filterStrings(BinaryResourceTable table) throws IOException {
        if (!options.getRemoveStr().orElse(false)) return;
        Set<String> unused = new HashSet<>();
        String unusedPath = options.getUnusedStrPath().orElse("");
        if (!unusedPath.isEmpty() && Files.isRegularFile(java.nio.file.Paths.get(unusedPath)))
            unused.addAll(Files.readAllLines(java.nio.file.Paths.get(unusedPath), StandardCharsets.UTF_8));
        Set<String> languages = options.getLanguageWhiteList().orElse(Collections.emptySet());
        for (BinaryResourceTable.Entry entry : table.entries()) {
            if (entry.packageId() != 127 || !entry.type.equals("string")) continue;
            if (!languages.isEmpty() && !entry.locale.isEmpty() && !languages.contains(entry.locale)) {
                entry.removeConfig();
                continue;
            }
            if (unused.contains(entry.originalName) && entry.valueType() == 3) table.replaceString(entry, "[value removed]");
        }
    }

    private void mergeDuplicates(ZipFile input, Map<String, ApkEntry> files, Map<String, String> replacements, Path reports) throws IOException {
        Map<String, List<String>> hashes = new LinkedHashMap<>();
        StringBuilder log = new StringBuilder("res filter path mapping:\n");
        int count = 0;
        long saved = 0;
        for (String path : new ArrayList<>(files.keySet())) {
            if (!path.startsWith("res/") || matches(path)) continue;
            ApkEntry file = files.get(path);
            byte[] data = file.bytes(input);
            String hash = java.util.Base64.getEncoder().encodeToString(digest(data));
            List<String> candidates = hashes.computeIfAbsent(hash, key -> new ArrayList<>());
            String keep = null;
            for (String candidate : candidates) {
                if (Arrays.equals(data, files.get(candidate).bytes(input))) { keep = candidate; break; }
            }
            if (keep == null) { candidates.add(path); continue; }
            // A merged file must remain uncompressed if any resource opened it via an FD.
            files.get(keep).method = Math.min(files.get(keep).method, file.method);
            files.remove(path);
            replacements.put(path, keep);
            log.append("\t").append(path).append(" -> ").append(keep).append("\n");
            count++; saved += file.source.getSize();
        }
        log.append("removed: count(").append(count).append("), totalSize(").append(saved).append(" bytes)\n");
        Files.writeString(reports.resolve("apk-duplicated-res.txt"), log, StandardCharsets.UTF_8);
    }

    private void obfuscate(BinaryResourceTable table, Map<String, ApkEntry> files, Map<String, String> replacements, Path reports) throws IOException {
        ResourcesMapping mapping = options.getMappingPath().isPresent() && Files.isRegularFile(options.getMappingPath().get())
                ? new ResourcesMappingParser(options.getMappingPath().get()).parse() : new ResourcesMapping();
        for (String target : mapping.getDirMapping().values()) validateResourcePath(target + "/x");
        for (String target : mapping.getEntryFilesMapping().values()) validateResourcePath(target);

        ResGuardStringBuilder names = new ResGuardStringBuilder();
        names.reset(null, options.getUseRandomName().orElse(false));
        Set<String> reserved = new HashSet<>();
        reserved.addAll(mapping.getPathMappingNameList());
        for (String name : mapping.getResourceMapping().values()) reserved.add(simpleName(name));
        for (String path : mapping.getEntryFilesMapping().values()) reserved.add(fileStem(path));
        for (BinaryResourceTable.Entry entry : table.entries()) if (!shouldObfuscate(entry)) reserved.add(entry.originalName);
        names.removeStrings(reserved);

        Map<String, String> symbols = new LinkedHashMap<>();
        Map<String, Set<String>> used = new HashMap<>();
        for (BinaryResourceTable.Entry entry : table.entries()) {
            String type = entry.packageName() + ".R." + entry.type;
            if (!shouldObfuscate(entry)) used.computeIfAbsent(type, key -> new HashSet<>()).add(entry.originalName);
        }
        for (BinaryResourceTable.Entry entry : table.entries()) {
            if (symbols.containsKey(entry.fullName())) { entry.name = symbols.get(entry.fullName()); continue; }
            if (!shouldObfuscate(entry)) {
                mapping.getResourceMapping().remove(entry.fullName());
                symbols.put(entry.fullName(), entry.originalName);
                continue;
            }
            String type = entry.packageName() + ".R." + entry.type;
            Set<String> usedNames = used.computeIfAbsent(type, key -> new HashSet<>());
            String mapped = mapping.getResourceMapping().get(entry.fullName());
            String name = mapped == null ? names.getReplaceString(usedNames) : simpleName(mapped);
            if (!name.matches("[a-zA-Z_][a-zA-Z_0-9]*")) throw new IOException("Invalid mapped resource name: " + name);
            if (!usedNames.add(name)) throw new IOException("Conflicting mapped resource name: " + type + "." + name);
            entry.name = name;
            symbols.put(entry.fullName(), name);
            mapping.getResourceMapping().put(entry.fullName(), type + "." + name);
            mapping.addResourceNameAndId(entry.fullName(), entry.resourceId());
        }

        Map<String, String> rename = new LinkedHashMap<>();
        Set<String> destinations = new HashSet<>();
        for (String path : files.keySet()) {
            if (!path.startsWith("res/") || matches(path)) { destinations.add(path); continue; }
            String target = mapping.getEntryFilesMapping().get(path);
            // Mapping files produced by the previous APK adapter used a base/ prefix.
            if (target == null) target = mapping.getEntryFilesMapping().get("base/" + path);
            if (target != null) {
                validateResourcePath(target);
                if (!destinations.add(target)) throw new IOException("Conflicting mapped APK path: " + target);
                rename.put(path, target);
            }
        }
        for (String path : files.keySet()) {
            if (!path.startsWith("res/") || matches(path) || rename.containsKey(path)) continue;
            String parent = path.substring(0, path.lastIndexOf('/'));
            String directory = mapping.getDirMapping().get(parent);
            if (directory == null) {
                directory = "res/" + names.getReplaceString();
                mapping.putDirMapping(parent, directory);
            }
            String target;
            do { target = directory + "/" + names.getReplaceString() + fileSuffix(path); } while (destinations.contains(target));
            destinations.add(target);
            rename.put(path, target);
        }
        Map<String, ApkEntry> renamed = new TreeMap<>();
        for (Map.Entry<String, ApkEntry> file : files.entrySet()) {
            String target = rename.getOrDefault(file.getKey(), file.getKey());
            if (renamed.put(target, file.getValue()) != null) throw new IOException("Duplicate output APK path: " + target);
            if (rename.containsKey(file.getKey())) {
                mapping.getEntryFilesMapping().remove("base/" + file.getKey());
                mapping.putEntryFileMapping(file.getKey(), target);
                if (options.getEnableMutateMd5().orElse(false) && MEDIA.contains(extension(file.getKey()))) file.getValue().mutate = true;
            }
        }
        files.clear(); files.putAll(renamed);
        for (Map.Entry<String, String> duplicate : replacements.entrySet()) duplicate.setValue(rename.getOrDefault(duplicate.getValue(), duplicate.getValue()));
        replacements.putAll(rename);
        for (BinaryResourceTable.Entry entry : table.entries()) {
            String path = entry.filePath();
            if (path != null) mapping.addResourcePathAndId(path, entry.resourceId());
        }
        mapping.writeMappingToFile(reports.resolve("resources-mapping.txt"));
    }

    private boolean shouldObfuscate(BinaryResourceTable.Entry entry) { return entry.packageId() != 1 && !matches(entry.fullName()); }
    private boolean matches(String value) { for (Pattern pattern : whitelist) if (pattern.matcher(value).matches()) return true; return false; }
    private static String simpleName(String name) { return name.substring(name.lastIndexOf('.') + 1); }
    private static String extension(String path) { return path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT); }
    private static String fileSuffix(String path) {
        if (path.endsWith(".9.png")) return ".9.png";
        int dot = path.lastIndexOf('.');
        return dot > path.lastIndexOf('/') ? path.substring(dot) : "";
    }
    private static String fileStem(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String suffix = fileSuffix(path);
        return name.substring(0, name.length() - suffix.length());
    }
    private static void validateResourcePath(String path) throws IOException {
        if (!path.startsWith("res/") || path.endsWith("/") || path.contains("\\") || path.contains("//")
                || Arrays.asList(path.split("/")).contains("..") || Arrays.asList(path.split("/")).contains("."))
            throw new IOException("Invalid mapped APK resource path: " + path);
    }

    private static void checkResourcePaths(BinaryResourceTable table, Map<String, ApkEntry> files) throws IOException {
        for (BinaryResourceTable.Entry entry : table.entries()) {
            if (entry.removed) continue;
            String path = entry.filePath();
            if (path != null && !files.containsKey(path)) throw new IOException("Missing APK resource file: " + path);
        }
    }

    private void writeApk(ZipFile input, Map<String, ApkEntry> files, Path output) throws IOException, InterruptedException {
        boolean compress = options.getEnableCompressImages().orElse(false);
        List<ImageCompressor.Request> requests = new ArrayList<>();
        if (compress) {
            for (ApkEntry file : files.values()) {
                String originalPath = file.source.getName();
                if (isImage(originalPath)) requests.add(new ImageCompressor.Request(originalPath, originalPath, () -> file.bytes(input)));
            }
        }
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(output));
             ImageCompressor compressor = compress
                     ? new ImageCompressor(options.getImageCompressionToolsDir().orElse(""), options.getImageCompressionCacheDir().orElse(null)) : null;
             ImageCompressor.OrderedCompression images = compressor == null ? null
                     : compressor.compressInOrder(requests, options.getImageCompressionParallelism().orElse(0))) {
            for (Map.Entry<String, ApkEntry> item : files.entrySet()) {
                ApkEntry file = item.getValue();
                String originalPath = file.source.getName();
                byte[] data = file.content;
                if (images != null && isImage(originalPath)) {
                    byte[] compressed = images.next();
                    // Unchanged images are streamed from the input like any other entry.
                    if (compressed != null) data = compressed;
                }
                if (file.mutate) {
                    if (data == null) data = file.bytes(input);
                    byte[] mutated = Arrays.copyOf(data, data.length + 1);
                    MessageDigest digest = sha256();
                    digest.update((options.getMutateMd5Seed().orElse("") + ":" + originalPath + ":").getBytes(StandardCharsets.UTF_8));
                    digest.update(data);
                    mutated[data.length] = digest.digest()[0];
                    data = mutated;
                }
                ZipEntry entry = new ZipEntry(item.getKey());
                entry.setTime(0);
                entry.setMethod(file.method);
                if (file.method == ZipEntry.STORED) {
                    if (data == null) { entry.setSize(file.source.getSize()); entry.setCrc(file.source.getCrc()); }
                    else { CRC32 crc = new CRC32(); crc.update(data); entry.setSize(data.length); entry.setCrc(crc.getValue()); }
                }
                zip.putNextEntry(entry);
                if (data != null) zip.write(data);
                else try (InputStream stream = input.getInputStream(file.source)) { stream.transferTo(zip); }
                zip.closeEntry();
            }
        }
    }

    private static boolean isImage(String path) {
        return (path.startsWith("res/") || path.startsWith("assets/"))
                && Arrays.asList("png", "jpg", "jpeg", "gif", "webp").contains(extension(path));
    }

    private void sign(AndroidBuildTools tools, Path apk) throws IOException, InterruptedException {
        JarSigner.Signature signature = options.getStoreFile().isPresent()
                ? new JarSigner.Signature(options.getStoreFile().get(), options.getStorePassword().get(), options.getKeyAlias().get(), options.getKeyPassword().get())
                : JarSigner.Signature.DEBUG_SIGNATURE;
        Map<String, String> env = new HashMap<>();
        env.put("ANDRESGUARD_STORE_PASSWORD", signature.storePassword);
        env.put("ANDRESGUARD_KEY_PASSWORD", signature.keyPassword);
        tools.signer(env, "sign", "--ks", signature.storeFile.toString(), "--ks-key-alias", signature.keyAlias,
                "--ks-pass", "env:ANDRESGUARD_STORE_PASSWORD", "--key-pass", "env:ANDRESGUARD_KEY_PASSWORD",
                "--v2-signing-enabled", "true", "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", apk.toString());
        tools.signer(Collections.emptyMap(), "verify", "--verbose", apk.toString());
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"));
    }
    private static void require(Map<String, ApkEntry> files, String name) throws IOException {
        if (!files.containsKey(name)) throw new IOException("APK must contain " + name);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static byte[] digest(byte[] data) { return sha256().digest(data); }
    private static void copyReports(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(from)) {
            for (Path file : (Iterable<Path>) files::iterator) Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        }
    }
    private static final class ApkEntry {
        final ZipEntry source;
        int method;
        byte[] content;
        boolean mutate;
        ApkEntry(ZipEntry source) { this.source = source; method = source.getMethod(); }
        byte[] bytes(ZipFile input) throws IOException {
            if (content != null) return content;
            try (InputStream stream = input.getInputStream(source)) { return stream.readAllBytes(); }
        }
    }
}
