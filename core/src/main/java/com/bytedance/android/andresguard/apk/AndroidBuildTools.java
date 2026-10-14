package com.bytedance.android.andresguard.apk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/** Uses the installed SDK; never invokes a shell or puts signing passwords in argv. */
public final class AndroidBuildTools {
    private final Path directory;

    public AndroidBuildTools(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath();
        for (Path file : Arrays.asList(tool("aapt2"), tool("zipalign"), signerJar())) {
            if (!Files.isRegularFile(file)) {
                throw new IOException("Missing Android Build Tool: " + file + ". Install Build Tools 35+ or set apkBuildToolsDir/--build-tools-dir.");
            }
        }
        Path properties = this.directory.resolve("source.properties");
        if (Files.isRegularFile(properties)) {
            java.util.Properties info = new java.util.Properties();
            try (java.io.InputStream input = Files.newInputStream(properties)) { info.load(input); }
            String revision = info.getProperty("Pkg.Revision", "");
            if (!revision.matches("(?:3[5-9]|[4-9]\\d|\\d{3,})\\..*")) {
                throw new IOException("APK support requires Build Tools 35+ for 16 KB alignment, found " + revision + " in " + directory);
            }
        }
    }

    public static Path find(String override, Path sdk) throws IOException {
        if (override != null && !override.trim().isEmpty()) return Paths.get(override);
        if (sdk == null) {
            String root = System.getenv("ANDROID_HOME");
            if (root == null || root.isEmpty()) root = System.getenv("ANDROID_SDK_ROOT");
            if (root != null && !root.isEmpty()) sdk = Paths.get(root);
        }
        if (sdk != null && Files.isDirectory(sdk.resolve("build-tools"))) {
            try (Stream<Path> dirs = Files.list(sdk.resolve("build-tools"))) {
                Path latest = dirs.filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().matches("\\d+\\.\\d+\\.\\d+"))
                        .max(Comparator.comparing(p -> versionKey(p.getFileName().toString()))).orElse(null);
                if (latest != null) return latest;
            }
        }
        throw new IOException("APK support requires Android SDK Build Tools 35+. Set ANDROID_HOME or apkBuildToolsDir/--build-tools-dir.");
    }

    private static String versionKey(String version) {
        StringBuilder key = new StringBuilder();
        for (String part : version.split("\\.")) key.append(String.format("%08d", Integer.parseInt(part)));
        return key.toString();
    }

    public Path tool(String name) {
        return directory.resolve(name + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : ""));
    }

    public Path signerJar() { return directory.resolve("lib/apksigner.jar"); }

    public void run(String tool, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(tool(tool).toString());
        command.addAll(Arrays.asList(args));
        run(command, java.util.Collections.emptyMap());
    }

    public void signer(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(Paths.get(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        command.add("-jar");
        command.add(signerJar().toString());
        command.addAll(Arrays.asList(args));
        run(command, env);
    }

    private void run(List<String> command, Map<String, String> env) throws IOException, InterruptedException {
        Path log = Files.createTempFile("andresguard-tool-", ".log");
        Process process = null;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(env);
            process = builder.start();
            if (!process.waitFor(5, TimeUnit.MINUTES)) throw new IOException("Android Build Tool timed out: " + command.get(0));
            if (process.exitValue() != 0) {
                String detail = new String(Files.readAllBytes(log), java.nio.charset.StandardCharsets.UTF_8);
                for (String secret : env.values()) if (!secret.isEmpty()) detail = detail.replace(secret, "[redacted]");
                throw new IOException("Android Build Tool failed (exit " + process.exitValue() + "): " + detail);
            }
        } finally {
            if (process != null && process.isAlive()) process.destroyForcibly();
            Files.deleteIfExists(log);
        }
    }
}
