package com.bytedance.android.andresguard.apk;

import com.android.aapt.Resources;
import com.android.bundle.Config;
import com.android.tools.build.bundletool.model.utils.ResourcesUtils;
import com.bytedance.android.andresguard.android.JarSigner;
import com.bytedance.android.andresguard.commands.ObfuscateResourcesCommand;
import com.bytedance.android.andresguard.utils.VirusTotalUploader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Adapts one APK to the shared resource pipeline, then restores APK layout and signing. */
public final class ApkProcessor {
    private final ObfuscateResourcesCommand options;

    public ApkProcessor(ObfuscateResourcesCommand options) { this.options = options; }

    public Path execute() throws IOException, InterruptedException {
        AndroidBuildTools tools = new AndroidBuildTools(AndroidBuildTools.find(options.getApkBuildToolsDir().orElse(""), null));
        Path work = Files.createTempDirectory(options.getOutputPath().getParent(), "andresguard-apk-");
        try {
            Path proto = work.resolve("input.proto.apk");
            tools.run("aapt2", "convert", "--output-format", "proto", "-o", proto.toString(), options.getInputPath().toString());
            Path bundle = work.resolve("input.aab");
            Path guarded = work.resolve("processed/guarded.aab");
            Map<String, Integer> methods = zipMethods(options.getInputPath());
            Resources.ResourceTable original = toBundle(proto, bundle);
            // Keep intermediate signing and uploads disabled; only the final APK is published.
            options.toBuilder().setInputPath(bundle).setOutputPath(guarded)
                    .setDisableSign(true).setEnableVirusTotalUpload(false).build().execute();
            Path guardedProto = work.resolve("guarded.proto.apk");
            Resources.ResourceTable processed = fromBundle(guarded, guardedProto);
            Map<String, String> oldPaths = resourcePaths(original);
            resourcePaths(processed).forEach((key, newPath) -> {
                Integer method = methods.get(oldPaths.get(key));
                if (method != null) methods.merge(newPath, method, Math::min);
            });
            Path binary = work.resolve("binary.apk");
            tools.run("aapt2", "convert", "--output-format", "binary", "-o", binary.toString(), guardedProto.toString());
            Path repacked = work.resolve("repacked.apk");
            repack(binary, repacked, methods);
            Path aligned = work.resolve("aligned.apk");
            tools.run("zipalign", "-P", "16", "-f", "4", repacked.toString(), aligned.toString());
            if (!options.getDisableSign().orElse(false)) sign(tools, aligned);
            tools.run("zipalign", "-c", "-P", "16", "4", aligned.toString());
            copyReports(guarded.getParent(), options.getOutputPath().getParent());
            try {
                Files.move(aligned, options.getOutputPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(aligned, options.getOutputPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            if (options.getEnableVirusTotalUpload().orElse(false)) {
                VirusTotalUploader.upload(options.getVirusTotalApiKey().orElse(""), options.getOutputPath().toFile());
            }
            System.out.println("APK resources processed: " + options.getOutputPath());
            return options.getOutputPath();
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(work)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
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

    private static Resources.ResourceTable toBundle(Path proto, Path bundle) throws IOException {
        try (ZipFile input = new ZipFile(proto.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(bundle))) {
            ZipEntry manifestEntry = input.getEntry("AndroidManifest.xml");
            ZipEntry tableEntry = input.getEntry("resources.pb");
            if (manifestEntry == null || tableEntry == null) throw new IOException("APK must contain AndroidManifest.xml and a resource table.");
            Resources.XmlNode manifest = Resources.XmlNode.parseFrom(bytes(input, manifestEntry));
            for (Resources.XmlAttribute attribute : manifest.getElement().getAttributeList()) {
                if (attribute.getName().equals("split") && !attribute.getValue().isEmpty()) {
                    throw new IOException("Split APKs are not supported. Supply a standalone/universal APK.");
                }
            }
            Resources.ResourceTable table = Resources.ResourceTable.parseFrom(bytes(input, tableEntry));
            write(output, "BundleConfig.pb", Config.BundleConfig.newBuilder()
                    .setBundletool(Config.Bundletool.newBuilder().setVersion("1.18.3")).build().toByteArray(), ZipEntry.DEFLATED);
            Set<String> libDirs = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignature(entry.getName())) continue;
                String name = entry.getName();
                String target;
                if (name.equals("AndroidManifest.xml")) target = "manifest/" + name;
                else if (name.equals("resources.pb")) target = name;
                else if (name.matches("classes(\\d+)?\\.dex")) target = "dex/" + name;
                else if (name.startsWith("res/") || name.startsWith("assets/") || name.startsWith("lib/")) target = name;
                else target = "root/" + name;
                if (name.startsWith("lib/") && name.indexOf('/', 4) > 0) libDirs.add(name.substring(0, name.indexOf('/', 4)));
                write(output, "base/" + target, bytes(input, entry), entry.getMethod());
            }
            // Required by the shared library-file filter when it updates native directories.
            if (!libDirs.isEmpty()) {
                com.android.bundle.Files.NativeLibraries.Builder nativeConfig = com.android.bundle.Files.NativeLibraries.newBuilder();
                for (String dir : libDirs) nativeConfig.addDirectory(com.android.bundle.Files.TargetedNativeDirectory.newBuilder().setPath(dir));
                write(output, "base/native.pb", nativeConfig.build().toByteArray(), ZipEntry.DEFLATED);
            }
            return table;
        }
    }

    private static Resources.ResourceTable fromBundle(Path bundle, Path proto) throws IOException {
        try (ZipFile input = new ZipFile(bundle.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(proto))) {
            Resources.ResourceTable table = Resources.ResourceTable.parseFrom(bytes(input, input.getEntry("base/resources.pb")));
            Set<String> names = new HashSet<>();
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().startsWith("base/")) continue;
                String name = entry.getName().substring(5);
                if (name.equals("native.pb") || name.equals("assets.pb")) continue;
                if (name.equals("manifest/AndroidManifest.xml")) name = "AndroidManifest.xml";
                else if (name.startsWith("dex/")) name = name.substring(4);
                else if (name.startsWith("root/")) name = name.substring(5);
                if (!names.add(name)) throw new IOException("Duplicate APK entry: " + name);
                write(output, name, bytes(input, entry), entry.getMethod());
            }
            return table;
        }
    }

    private static Map<String, String> resourcePaths(Resources.ResourceTable table) {
        Map<String, String> paths = new HashMap<>();
        ResourcesUtils.entries(table).forEach(entry -> {
            for (Resources.ConfigValue value : entry.getEntry().getConfigValueList()) {
                if (value.getValue().getItem().hasFile()) {
                    String key = entry.getResourceId().toString() + ":" + java.util.Base64.getEncoder().encodeToString(value.getConfig().toByteArray());
                    paths.put(key, value.getValue().getItem().getFile().getPath());
                }
            }
        });
        return paths;
    }

    private static Map<String, Integer> zipMethods(Path apk) throws IOException {
        Map<String, Integer> methods = new HashMap<>();
        try (ZipFile input = new ZipFile(apk.toFile())) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (methods.put(entry.getName(), entry.getMethod()) != null) throw new IOException("Duplicate APK entry: " + entry.getName());
            }
        }
        return methods;
    }

    private static void repack(Path binary, Path apk, Map<String, Integer> methods) throws IOException {
        try (ZipFile input = new ZipFile(binary.toFile()); ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(apk))) {
            Enumeration<? extends ZipEntry> entries = input.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || isSignature(entry.getName())) continue;
                // Android 11+ requires uncompressed, aligned resources.arsc.
                int method = entry.getName().equals("resources.arsc") ? ZipEntry.STORED : methods.getOrDefault(entry.getName(), entry.getMethod());
                write(output, entry.getName(), bytes(input, entry), method);
            }
        }
    }

    private static boolean isSignature(String name) {
        String upper = name.toUpperCase(java.util.Locale.ROOT);
        return upper.startsWith("META-INF/") && (upper.equals("META-INF/MANIFEST.MF") || upper.endsWith(".SF")
                || upper.endsWith(".RSA") || upper.endsWith(".DSA") || upper.endsWith(".EC") || upper.startsWith("META-INF/SIG-"));
    }

    private static byte[] bytes(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry == null) throw new IOException("Missing required ZIP entry");
        try (InputStream stream = zip.getInputStream(entry)) { return stream.readAllBytes(); }
    }

    private static void write(ZipOutputStream zip, String name, byte[] content, int method) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32();
            crc.update(content);
            entry.setSize(content.length);
            entry.setCompressedSize(content.length);
            entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private static void copyReports(Path from, Path to) throws IOException {
        try (java.util.stream.Stream<Path> files = Files.list(from)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().endsWith(".txt")) Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
