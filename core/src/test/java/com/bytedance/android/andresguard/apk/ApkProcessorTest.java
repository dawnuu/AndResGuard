package com.bytedance.android.andresguard.apk;

import com.android.aapt.Resources;
import com.android.tools.build.bundletool.model.utils.ResourcesUtils;
import com.bytedance.android.andresguard.commands.ObfuscateResourcesCommand;
import org.junit.Assume;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** Integration tests use real AAPT2, zipalign and apksigner from the SDK. */
public class ApkProcessorTest {
    private static final byte[] DEX = "dex\n035\0unchanged-payload".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIB = new byte[16384];

    @Test
    public void signedApkKeepsIdsPayloadsAndPixelsAfterFullPipeline() throws Exception {
        try (Fixture f = fixture()) {
            Path output = f.work.resolve("result/guarded.apk");
            command(f, output).setEnableCompressImages(true).setMergeDuplicatedResources(true)
                    .setEnableMutateMd5(true).setMutateMd5Seed("test").setDisableSign(false).build().execute();
            f.tools.signer(Collections.emptyMap(), "verify", "--verbose", output.toString());
            f.tools.run("zipalign", "-c", "-P", "16", "4", output.toString());
            Resources.ResourceTable before = table(f, f.input, "before");
            Resources.ResourceTable after = table(f, output, "after");
            assertEquals(resourceIds(before), resourceIds(after));
            assertTrue(names(after).containsValue("keep_name"));
            assertFalse(names(after).containsValue("hero"));
            assertFalse(names(after).containsValue("duplicate"));
            assertEquals(strings(before), strings(after));
            try (ZipFile original = new ZipFile(f.input.toFile()); ZipFile guarded = new ZipFile(output.toFile())) {
                assertArrayEquals(read(original, "AndroidManifest.xml"), read(guarded, "AndroidManifest.xml"));
                Map<String, String> oldPaths = paths(before);
                Map<String, String> newPaths = paths(after);
                for (String id : oldPaths.keySet()) {
                    if (oldPaths.get(id).endsWith(".xml"))
                        assertArrayEquals(read(original, oldPaths.get(id)), read(guarded, newPaths.get(id)));
                }
            }
            try (ZipFile apk = new ZipFile(output.toFile())) {
                assertEquals(ZipEntry.STORED, apk.getEntry("resources.arsc").getMethod());
                assertEquals(ZipEntry.STORED, apk.getEntry("classes.dex").getMethod());
                assertEquals(ZipEntry.STORED, apk.getEntry("lib/arm64-v8a/libfixture.so").getMethod());
                assertArrayEquals(DEX, read(apk, "classes.dex"));
                assertArrayEquals(LIB, read(apk, "lib/arm64-v8a/libfixture.so"));
                assertArrayEquals("retain me".getBytes(StandardCharsets.UTF_8), read(apk, "META-INF/custom.txt"));
                ResourcesUtils.entries(after).forEach(entry -> entry.getEntry().getConfigValueList().forEach(value -> {
                    if (value.getValue().getItem().hasFile()) {
                        String path = value.getValue().getItem().getFile().getPath();
                        assertNotNull("Resource path must exist: " + path, apk.getEntry(path));
                        if (path.endsWith(".png")) {
                            try {
                                BufferedImage image = ImageIO.read(apk.getInputStream(apk.getEntry(path)));
                                assertNotNull(image);
                                assertEquals(64, image.getWidth());
                                assertEquals(0xff123456, image.getRGB(0, 0));
                            } catch (Exception e) { throw new AssertionError(e); }
                        }
                    }
                }));
            }
            try (java.util.stream.Stream<Path> reports = Files.list(output.getParent())) {
                assertTrue(reports.anyMatch(p -> p.getFileName().toString().contains("mapping")));
            }
            try (ZipFile original = new ZipFile(f.input.toFile())) { assertNotNull(original.getEntry("res/drawable/hero.png")); }
        }
    }

    @Test
    public void brokenOptionalCompressorStillProducesUnsignedApk() throws Exception {
        try (Fixture f = fixture()) {
            Path output = f.work.resolve("unsigned.apk");
            command(f, output).setEnableCompressImages(true).setImageCompressionToolsDir(f.work.resolve("missing-tools").toString())
                    .setDisableSign(true).build().execute();
            assertTrue(Files.isRegularFile(output));
            assertEquals(resourceIds(table(f, f.input, "before")), resourceIds(table(f, output, "after")));
        }
    }

    @Test
    public void requiredToolFailurePreservesExistingOutputAndInput() throws Exception {
        try (Fixture f = fixture()) {
            Path output = f.work.resolve("guarded.apk");
            byte[] previous = "previous successful artifact".getBytes(StandardCharsets.UTF_8);
            byte[] input = Files.readAllBytes(f.input);
            Files.write(output, previous);
            try {
                command(f, output).setApkBuildToolsDir(f.work.resolve("missing-sdk").toString()).build().execute();
                fail("Missing required SDK must fail");
            } catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Build Tool")); }
            assertArrayEquals(previous, Files.readAllBytes(output));
            assertArrayEquals(input, Files.readAllBytes(f.input));
        }
    }

    @Test
    public void filtersNativeFilesAndIgnoresAabRemovalOptions() throws Exception {
        try (Fixture f = fixture()) {
            Path output = f.work.resolve("filtered.apk");
            command(f, output).setFilterFile(true).setFileFilterRules(Collections.singleton("lib/arm64-v8a/*.so"))
                    .setRemoveRootFiles(true).build().execute();
            try (ZipFile zip = new ZipFile(output.toFile())) {
                assertNull(zip.getEntry("lib/arm64-v8a/libfixture.so"));
                assertArrayEquals("retain me".getBytes(StandardCharsets.UTF_8), read(zip, "META-INF/custom.txt"));
                assertArrayEquals(DEX, read(zip, "classes.dex"));
                assertNotNull(zip.getEntry("AndroidManifest.xml"));
                assertNotNull(zip.getEntry("resources.arsc"));
            }
        }
    }

    @Test
    public void rejectsSplitApkWithoutReplacingExistingOutput() throws Exception {
        try (Fixture f = fixture()) {
            Path manifest = f.work.resolve("AndroidManifest.xml");
            Files.writeString(manifest, Files.readString(manifest).replace("package=", "split=\"feature\" package="));
            Path platform;
            String sdk = System.getenv("ANDROID_HOME");
            if (sdk == null) sdk = System.getenv("ANDROID_SDK_ROOT");
            try (java.util.stream.Stream<Path> platforms = Files.list(Paths.get(sdk).resolve("platforms"))) {
                platform = platforms.map(p -> p.resolve("android.jar")).filter(Files::isRegularFile).findFirst().get();
            }
            f.tools.run("aapt2", "link", "--manifest", manifest.toString(), "-I", platform.toString(), "-o", f.input.toString(), f.work.resolve("compiled.zip").toString());
            Path output = f.work.resolve("guarded.apk");
            Files.writeString(output, "previous");
            try { command(f, output).build().execute(); fail("Split APK must fail"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Split APK")); }
            assertEquals("previous", Files.readString(output));
        }
    }

    @Test
    public void rejectsInputOutputAliasesAndWrongFormat() throws Exception {
        try (Fixture f = fixture()) {
            for (Path output : new Path[]{f.input, f.work.resolve("wrong.aab")}) {
                try { command(f, output).build(); fail("Invalid output must fail"); }
                catch (RuntimeException expected) { assertTrue(expected.getMessage().contains("output") || expected.getMessage().contains("format")); }
            }
        }
    }

    @Test
    public void reusesMappingAndPreservesWhitelistedNamesAcrossRuns() throws Exception {
        try (Fixture f = fixture()) {
            Path first = f.work.resolve("first/guarded.apk");
            Path second = f.work.resolve("second/guarded.apk");
            command(f, first).build().execute();
            command(f, second).setMappingPath(first.getParent().resolve("resources-mapping.txt")).build().execute();
            Resources.ResourceTable a = table(f, first, "first"), b = table(f, second, "second");
            assertEquals(names(a), names(b));
            assertEquals(paths(a), paths(b));
            assertTrue(names(b).containsValue("keep_name"));
        }
    }

    @Test
    public void filtersStringsWithoutChangingOtherStringsSharingTheirValue() throws Exception {
        try (Fixture f = fixture()) {
            Path unused = f.work.resolve("unused.txt");
            Files.writeString(unused, "unused\n");
            Path output = f.work.resolve("strings.apk");
            Resources.ResourceTable before = table(f, f.input, "before");
            command(f, output).setRemoveStr(true).setUnusedStrPath(unused.toString()).build().execute();
            Map<String, String> after = strings(table(f, output, "after"));
            names(before).forEach((id, name) -> {
                if (name.equals("unused")) assertEquals("[value removed]", after.get(id));
                if (name.equals("other")) assertEquals("Same text", after.get(id));
            });
        }
    }

    @Test
    public void processesSparseAndCompactTablesAndFiltersLanguageConfigurations() throws Exception {
        try (Fixture f = fixture(true)) {
            Assume.assumeTrue("Compact-entry fixtures require Build Tools 36+",
                    Integer.parseInt(f.toolsDir.getFileName().toString().split("\\.")[0]) >= 36);
            Path optimized = f.work.resolve("optimized.apk");
            f.tools.run("aapt2", "optimize", "--force-sparse-encoding", "--enable-compact-entries", "-o", optimized.toString(), f.input.toString());
            Files.move(optimized, f.input, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (ZipFile zip = new ZipFile(f.input.toFile())) {
                BinaryResourceTable binary = new BinaryResourceTable(read(zip, "resources.arsc"));
                assertTrue(binary.entries().stream().anyMatch(BinaryResourceTable.Entry::compact));
                assertTrue(binary.entries().stream().anyMatch(entry -> entry.indexEncoding() == 1));
                assertTrue(binary.entries().stream().anyMatch(entry -> entry.indexEncoding() == 2));
            }
            Resources.ResourceTable before = table(f, f.input, "before");
            Path unfiltered = f.work.resolve("modern-unfiltered.apk");
            command(f, unfiltered).build().execute();
            Resources.ResourceTable retained = table(f, unfiltered, "retained");
            assertEquals(resourceIds(before), resourceIds(retained));
            assertEquals(strings(before), strings(retained));
            try (ZipFile zip = new ZipFile(unfiltered.toFile())) {
                assertTrue(new BinaryResourceTable(read(zip, "resources.arsc")).entries().stream()
                        .anyMatch(entry -> entry.indexEncoding() == 1));
            }
            Path output = f.work.resolve("modern.apk");
            command(f, output).setRemoveStr(true).setLanguageWhiteList(Collections.singleton("en")).build().execute();
            Resources.ResourceTable after = table(f, output, "after");
            assertEquals(resourceIds(before), resourceIds(after));
            assertEquals(strings(before), strings(after));
            ResourcesUtils.entries(after).forEach(entry -> {
                if (entry.getType().getName().equals("string"))
                    entry.getEntry().getConfigValueList().forEach(value -> assertFalse(value.getConfig().getLocale().startsWith("es")));
            });
        }
    }

    @Test
    public void rejectsConflictingMappingWithoutReplacingExistingOutput() throws Exception {
        try (Fixture f = fixture()) {
            Path mapping = f.work.resolve("bad-mapping.txt");
            Files.writeString(mapping, "res entries path mapping:\n\t0x7f010000 : res/drawable/hero.png -> res/a/x.png\n\t0x7f010001 : res/drawable/duplicate.png -> res/a/x.png\n");
            Path output = f.work.resolve("guarded.apk");
            Files.writeString(output, "previous");
            try { command(f, output).setMappingPath(mapping).build().execute(); fail("Conflicting paths must fail"); }
            catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
            assertEquals("previous", Files.readString(output));
        }
    }

    private static ObfuscateResourcesCommand.Builder command(Fixture f, Path output) {
        return ObfuscateResourcesCommand.builder().setEnableObfuscate(true).setInputPath(f.input).setOutputPath(output)
                .setWhiteList(Collections.singleton("*.R.string.keep_name")).setBundleMetaDataWhiteList(Collections.emptySet())
                .setApkBuildToolsDir(f.toolsDir.toString()).setDisableSign(true);
    }

    private static Fixture fixture() throws Exception {
        return fixture(false);
    }

    private static Fixture fixture(boolean modern) throws Exception {
        String sdkValue = System.getenv("ANDROID_HOME");
        if (sdkValue == null) sdkValue = System.getenv("ANDROID_SDK_ROOT");
        Assume.assumeTrue("Set ANDROID_HOME to run APK integration tests", sdkValue != null);
        Path sdk = Paths.get(sdkValue);
        Path toolsDir = AndroidBuildTools.find("", sdk);
        AndroidBuildTools tools = new AndroidBuildTools(toolsDir);
        Path platform;
        try (java.util.stream.Stream<Path> platforms = Files.list(sdk.resolve("platforms"))) {
            platform = platforms.map(p -> p.resolve("android.jar")).filter(Files::isRegularFile).findFirst().get();
        }
        Path work = Files.createTempDirectory("apk-integration-");
        Files.createDirectories(work.resolve("res/drawable"));
        Files.createDirectories(work.resolve("res/values"));
        Files.createDirectories(work.resolve("res/layout"));
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) for (int x = 0; x < 64; x++) image.setRGB(x, y, 0xff123456);
        ImageIO.write(image, "png", work.resolve("res/drawable/hero.png").toFile());
        Files.copy(work.resolve("res/drawable/hero.png"), work.resolve("res/drawable/duplicate.png"));
        StringBuilder strings = new StringBuilder("<resources><string name=\"keep_name\">Keep text</string><string name=\"other\">Same text</string><string name=\"unused\">Same text</string><string name=\"styled\"><b>Styled</b> text</string><string name=\"path_text\">res/drawable/hero.png</string>");
        if (modern) {
            for (int i = 0; i < 100; i++) strings.append("<string name=\"filler_").append(i).append("\">Filler ").append(i).append("</string>");
            Files.createDirectories(work.resolve("res/values-es"));
            Files.writeString(work.resolve("res/values-es/strings.xml"), "<resources><string name=\"unused\">Same text</string></resources>");
        }
        Files.writeString(work.resolve("res/values/strings.xml"), strings.append("</resources>").toString());
        Files.writeString(work.resolve("res/layout/main.xml"), "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\" android:layout_width=\"match_parent\" android:layout_height=\"match_parent\" android:background=\"@drawable/hero\"/>");
        Files.writeString(work.resolve("AndroidManifest.xml"), "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\" package=\"com.example.apkfixture\"><uses-sdk android:minSdkVersion=\"21\" android:targetSdkVersion=\"35\"/><application android:label=\"@string/keep_name\" android:icon=\"@drawable/hero\" android:extractNativeLibs=\"false\"/></manifest>");
        if (modern) Files.writeString(work.resolve("AndroidManifest.xml"), Files.readString(work.resolve("AndroidManifest.xml")).replace("minSdkVersion=\"21\"", "minSdkVersion=\"35\""));
        Path compiled = work.resolve("compiled.zip");
        tools.run("aapt2", "compile", "--dir", work.resolve("res").toString(), "-o", compiled.toString());
        Path base = work.resolve("base.apk");
        tools.run("aapt2", "link", "--manifest", work.resolve("AndroidManifest.xml").toString(), "-I", platform.toString(), "-o", base.toString(), compiled.toString());
        Path input = work.resolve("input.apk");
        try (ZipFile apk = new ZipFile(base.toFile()); ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(input))) {
            for (ZipEntry entry : Collections.list(apk.entries())) put(zip, entry.getName(), read(apk, entry.getName()), entry.getMethod());
            put(zip, "classes.dex", DEX, ZipEntry.STORED);
            put(zip, "lib/arm64-v8a/libfixture.so", LIB, ZipEntry.STORED);
            put(zip, "META-INF/custom.txt", "retain me".getBytes(StandardCharsets.UTF_8), ZipEntry.DEFLATED);
            put(zip, "META-INF/OLD.SF", "obsolete signature".getBytes(StandardCharsets.UTF_8), ZipEntry.DEFLATED);
        }
        return new Fixture(work, input, toolsDir, tools);
    }

    private static Resources.ResourceTable table(Fixture f, Path apk, String name) throws Exception {
        Path proto = f.work.resolve(name + ".proto.apk");
        f.tools.run("aapt2", "convert", "--output-format", "proto", "-o", proto.toString(), apk.toString());
        try (ZipFile zip = new ZipFile(proto.toFile())) { return Resources.ResourceTable.parseFrom(read(zip, "resources.pb")); }
    }

    private static Map<String, String> names(Resources.ResourceTable table) {
        Map<String, String> names = new HashMap<>();
        ResourcesUtils.entries(table).forEach(entry -> names.put(entry.getResourceId().toString(), entry.getEntry().getName()));
        return names;
    }

    private static java.util.Set<String> resourceIds(Resources.ResourceTable table) { return names(table).keySet(); }

    private static Map<String, String> paths(Resources.ResourceTable table) {
        Map<String, String> paths = new HashMap<>();
        ResourcesUtils.entries(table).forEach(entry -> entry.getEntry().getConfigValueList().forEach(value -> {
            if (value.getValue().getItem().hasFile()) paths.put(entry.getResourceId().toString(), value.getValue().getItem().getFile().getPath());
        }));
        return paths;
    }

    private static Map<String, String> strings(Resources.ResourceTable table) {
        Map<String, String> strings = new HashMap<>();
        ResourcesUtils.entries(table).forEach(entry -> entry.getEntry().getConfigValueList().forEach(value -> {
            if (value.getValue().getItem().hasStr()) strings.put(entry.getResourceId().toString(), value.getValue().getItem().getStr().getValue());
        }));
        return strings;
    }

    private static byte[] read(ZipFile zip, String name) throws Exception {
        try (java.io.InputStream input = zip.getInputStream(zip.getEntry(name))) { return input.readAllBytes(); }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data, int method) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        entry.setMethod(method);
        if (method == ZipEntry.STORED) {
            CRC32 crc = new CRC32(); crc.update(data);
            entry.setSize(data.length); entry.setCrc(crc.getValue());
        }
        zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
    }

    private static final class Fixture implements AutoCloseable {
        final Path work, input, toolsDir;
        final AndroidBuildTools tools;
        Fixture(Path work, Path input, Path toolsDir, AndroidBuildTools tools) {
            this.work = work; this.input = input; this.toolsDir = toolsDir; this.tools = tools;
        }
        public void close() throws Exception {
            try (java.util.stream.Stream<Path> paths = Files.walk(work)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(path);
            }
        }
    }
}
