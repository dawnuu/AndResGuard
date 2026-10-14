package com.bytedance.android.andresguard.executors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Lossless, same-format image optimization using native command-line tools. */
public class ImageCompressor implements AutoCloseable {
    private final String toolsDirectory;
    private final ImageCompressionCache cache;
    private int cacheHits;
    private boolean cacheWarning;
    private Path work;
    private boolean workFailed;
    private int optimized, unchanged, skipped, failed;
    private long savedBytes;
    private final Set<String> failedFormats = new HashSet<>();

    public ImageCompressor(String toolsDirectory) {
        this(toolsDirectory, null);
    }

    public ImageCompressor(String toolsDirectory, Path cacheDirectory) {
        this.toolsDirectory = toolsDirectory;
        this.cache = cacheDirectory == null ? null : new ImageCompressionCache(cacheDirectory, toolsDirectory);
    }

    public byte[] compressImage(String path, byte[] original) throws InterruptedException {
        return compressImage(path, path, original);
    }

    byte[] compressImage(String path, String displayPath, byte[] original) throws InterruptedException {
        String format = format(path);
        if ((!path.startsWith("res/") && !path.startsWith("assets/")) || format == null) return original;
        String toolFormat = format.equals("jpg") ? "jpeg" : format;
        if (workFailed || failedFormats.contains(toolFormat)) { failed++; return original; }
        if (shouldSkip(path, original)) {
            skipped++;
            System.out.println("[image skipped] " + displayPath);
            return original;
        }
        if (work == null) {
            try { work = Files.createTempDirectory("andresguard-images-"); }
            catch (IOException | SecurityException e) {
                workFailed = true;
                failed++;
                warn("Cannot create image compression temporary directory; keeping original images. " + e);
                return original;
            }
        }
        try {
            byte[] result = optimize(original, format, work);
            if (result.length > 0 && result.length < original.length) {
                optimized++;
                savedBytes += original.length - result.length;
                return result;
            }
            unchanged++;
        } catch (IOException | SecurityException e) {
            failed++;
            failedFormats.add(toolFormat);
            warn("Image compression failed for " + displayPath + "; keeping original image and continuing packaging. Further "
                    + toolFormat + " compression is disabled for this run. " + e);
        }
        return original;
    }

    @Override
    public void close() {
        if (work != null) {
            try (java.util.stream.Stream<Path> files = Files.walk(work)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
            } catch (IOException | java.io.UncheckedIOException | SecurityException e) {
                warn("Cannot clean image compression temporary directory " + work + "; continuing packaging. " + e);
            }
            work = null;
        }
        System.out.printf("Image compression: optimized=%d, unchanged=%d, skipped=%d, failed=%d, cached=%d, saved=%d bytes%n",
                optimized, unchanged, skipped, failed, cacheHits, savedBytes);
    }

    private static void warn(String message) {
        System.err.println("[AndResGuard WARNING] " + message);
    }

    byte[] optimize(byte[] original, String format, Path work) throws IOException, InterruptedException {
        String key = null;
        if (cache != null) {
            try {
                // Stable placeholder paths keep temporary directories out of the cache key.
                key = cache.key(original, command(format, java.nio.file.Paths.get("input." + format),
                        java.nio.file.Paths.get("output." + format)));
                byte[] result = cache.read(key, original.length);
                if (result != null) {
                    cacheHits++;
                    return result;
                }
            } catch (IOException | SecurityException e) {
                warnCache(e);
            }
        }
        byte[] result = optimizeUncached(original, format, work);
        if (key != null) {
            try { cache.write(key, result); }
            catch (IOException | SecurityException e) { warnCache(e); }
        }
        return result;
    }

    private void warnCache(Exception e) {
        if (!cacheWarning) {
            warn("Image cache unavailable; continuing image compression. " + e);
            cacheWarning = true;
        }
    }

    private byte[] optimizeUncached(byte[] original, String format, Path work) throws IOException, InterruptedException {
        Path input = work.resolve("input." + format);
        Path output = work.resolve("output." + format);
        Path log = work.resolve("tool.log");
        Files.write(input, original);
        Files.deleteIfExists(output);
        List<String> command = toolsDirectory.isEmpty()
                ? new ImageCompressor(BundledImageTools.extract(work).toString()).command(format, input, output)
                : command(format, input, output);
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile());
            if (toolsDirectory.isEmpty() && System.getProperty("os.name").startsWith("Linux")) {
                String directory = java.nio.file.Paths.get(command.get(0)).getParent().toString();
                String oldPath = builder.environment().get("LD_LIBRARY_PATH");
                builder.environment().put("LD_LIBRARY_PATH", directory
                        + (oldPath == null || oldPath.isEmpty() ? "" : ":" + oldPath));
            }
            process = builder.start();
        } catch (IOException e) {
            throw new IOException("Cannot run image compression tool " + command.get(0)
                    + ". Check platform compatibility or imageCompressionToolsDir." + macExecutionHint(), e);
        }
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                throw new IOException("Image compression timed out: " + command.get(0));
            }
            if (process.exitValue() != 0) {
                throw new IOException("Image compression failed: " + command.get(0) + " (exit "
                        + process.exitValue() + "); log: " + new String(Files.readAllBytes(log), StandardCharsets.UTF_8)
                        + ((process.exitValue() == 9 || process.exitValue() == 137 || process.exitValue() == 126)
                        ? macExecutionHint() : ""));
            }
            if (!Files.isRegularFile(output) || Files.size(output) == 0) {
                throw new IOException("Image compression produced no output: " + command.get(0));
            }
            byte[] result = Files.readAllBytes(output);
            return result.length < original.length ? result : original;
        } finally {
            process.destroyForcibly();
        }
    }

    private static String macExecutionHint() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (!os.startsWith("mac") && !os.equals("darwin")) return "";
        return " On macOS, execution permissions, Gatekeeper or an organization security policy may block"
                + " native tools. Bundled tools are not Developer ID-notarized. See https://support.apple.com/102445"
                + " or use imageCompressionToolsDir with trusted installed tools.";
    }

    List<String> command(String format, Path input, Path output) {
        List<String> args;
        switch (format) {
            case "png":
                // Disable color/bit-depth reductions; retain ancillary chunks and original format.
                args = Arrays.asList("optipng", "-o2", "-nb", "-nc", "-np", "-out", output.toString(), input.toString());
                break;
            case "jpg":
            case "jpeg":
                args = Arrays.asList("jpegtran", "-copy", "all", "-optimize", "-outfile", output.toString(), input.toString());
                break;
            case "webp":
                args = Arrays.asList("cwebp", "-lossless", "-exact", "-metadata", "all", input.toString(), "-o", output.toString());
                break;
            case "gif":
                args = Arrays.asList("gifsicle", "-O3", input.toString(), "-o", output.toString());
                break;
            default:
                throw new IllegalArgumentException("Unsupported image format: " + format);
        }
        List<String> command = new ArrayList<>(args);
        String executable = args.get(0) + (System.getProperty("os.name").startsWith("Windows") ? ".exe" : "");
        command.set(0, toolsDirectory.isEmpty() ? executable : java.nio.file.Paths.get(toolsDirectory, executable).toString());
        return command;
    }

    static String format(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return null;
        String format = path.substring(dot + 1).toLowerCase(Locale.ROOT);
        return Arrays.asList("png", "jpg", "jpeg", "webp", "gif").contains(format) ? format : null;
    }

    static boolean shouldSkip(String path, byte[] data) {
        if (path.toLowerCase(Locale.ROOT).endsWith(".9.png")) return true;
        String format = format(path);
        if ("png".equals(format)) {
            int offset = 8;
            while (offset <= data.length - 12) {
                long length = ((data[offset] & 255L) << 24) | ((data[offset + 1] & 255L) << 16)
                        | ((data[offset + 2] & 255L) << 8) | (data[offset + 3] & 255L);
                if (length > data.length - offset - 12) return true;
                String type = new String(data, offset + 4, 4, StandardCharsets.US_ASCII);
                if (type.startsWith("np") || type.equals("acTL")) return true;
                offset += (int) length + 12;
            }
        } else if ("webp".equals(format)) {
            int offset = 12;
            while (offset <= data.length - 8) {
                String type = new String(data, offset, 4, StandardCharsets.US_ASCII);
                if (type.equals("ANIM") || type.equals("ANMF")) return true;
                long length = (data[offset + 4] & 255L) | ((data[offset + 5] & 255L) << 8)
                        | ((data[offset + 6] & 255L) << 16) | ((data[offset + 7] & 255L) << 24);
                long step = 8 + length + (length & 1);
                if (step > data.length - offset) return true;
                offset += (int) step;
            }
        }
        return false;
    }
}
