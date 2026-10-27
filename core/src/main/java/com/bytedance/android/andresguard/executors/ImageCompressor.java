package com.bytedance.android.andresguard.executors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Lossless, same-format image optimization using native command-line tools. */
public class ImageCompressor implements AutoCloseable {
    private final String toolsDirectory;
    private final ImageCompressionCache cache;
    // Images may be compressed on several threads, so all shared state is thread-safe.
    private final AtomicInteger cacheHits = new AtomicInteger();
    private final AtomicBoolean cacheWarning = new AtomicBoolean();
    private Path work;
    private Path bundledTools;
    private volatile boolean workFailed;
    private final AtomicInteger optimized = new AtomicInteger(), unchanged = new AtomicInteger(),
            skipped = new AtomicInteger(), failed = new AtomicInteger();
    private final AtomicLong savedBytes = new AtomicLong();
    private final Set<String> failedFormats = ConcurrentHashMap.newKeySet();

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
        if (workFailed || failedFormats.contains(toolFormat)) { failed.incrementAndGet(); return original; }
        if (shouldSkip(path, original)) {
            skipped.incrementAndGet();
            System.out.println("[image skipped] " + displayPath);
            return original;
        }
        Path work = workDirectory();
        if (work == null) { failed.incrementAndGet(); return original; }
        Path job = null;
        try {
            // Each image gets its own directory so parallel jobs never share input/output file names.
            job = Files.createTempDirectory(work, "job-");
            byte[] result = optimize(original, format, job);
            if (result.length > 0 && result.length < original.length) {
                optimized.incrementAndGet();
                savedBytes.addAndGet(original.length - result.length);
                return result;
            }
            unchanged.incrementAndGet();
        } catch (IOException | SecurityException e) {
            failed.incrementAndGet();
            // Jobs already running when a tool fails may fail too; report each format once.
            if (failedFormats.add(toolFormat)) {
                warn("Image compression failed for " + displayPath + "; keeping original image and continuing packaging. Further "
                        + toolFormat + " compression is disabled for this run. " + e);
            }
        } finally {
            if (job != null) delete(job);
        }
        return original;
    }

    /** One image to compress; its bytes are read on the worker thread. */
    public interface ImageSource {
        byte[] read() throws IOException;
    }

    /**
     * Compresses images on up to {@code parallelism} threads and hands the results back in request
     * order, so output is identical to serial processing. Only about {@code parallelism} images are
     * read into memory at a time. {@code parallelism <= 0} picks a default from the CPU count.
     */
    public final class OrderedCompression implements AutoCloseable {
        private final ExecutorService executor;
        private final Iterator<Request> pending;
        private final ArrayDeque<Future<byte[]>> inFlight = new ArrayDeque<>();
        private final int window;

        private OrderedCompression(List<Request> requests, int parallelism) {
            window = parallelism > 0 ? parallelism : defaultParallelism();
            executor = Executors.newFixedThreadPool(window, runnable -> {
                Thread thread = new Thread(runnable, "andresguard-image");
                thread.setDaemon(true);
                return thread;
            });
            pending = requests.iterator();
            fill();
        }

        /**
         * Returns the compressed bytes for the next request, in the order the requests were given,
         * or {@code null} when the image is kept unchanged.
         */
        public byte[] next() throws IOException, InterruptedException {
            Future<byte[]> head = inFlight.poll();
            if (head == null) throw new IllegalStateException("No more images to compress");
            fill();
            try {
                return head.get();
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) throw (IOException) cause;
                if (cause instanceof InterruptedException) throw (InterruptedException) cause;
                if (cause instanceof RuntimeException) throw (RuntimeException) cause;
                if (cause instanceof Error) throw (Error) cause;
                throw new IOException(cause);
            }
        }

        private void fill() {
            while (inFlight.size() < window && pending.hasNext()) {
                Request request = pending.next();
                inFlight.add(executor.submit(() -> {
                    byte[] original = request.source.read();
                    byte[] result = compressImage(request.path, request.displayPath, original);
                    return result == original ? null : result;
                }));
            }
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }

    public OrderedCompression compressInOrder(List<Request> requests, int parallelism) {
        return new OrderedCompression(requests, parallelism);
    }

    public static final class Request {
        final String path;
        final String displayPath;
        final ImageSource source;

        public Request(String path, String displayPath, ImageSource source) {
            this.path = path;
            this.displayPath = displayPath;
            this.source = source;
        }
    }

    static int defaultParallelism() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    private synchronized Path workDirectory() {
        if (work == null && !workFailed) {
            try { work = Files.createTempDirectory("andresguard-images-"); }
            catch (IOException | SecurityException e) {
                workFailed = true;
                warn("Cannot create image compression temporary directory; keeping original images. " + e);
            }
        }
        return work;
    }

    private synchronized Path bundledTools(Path fallback) throws IOException {
        // Extract once: extracting again would overwrite tools that other threads are running.
        if (bundledTools == null) bundledTools = BundledImageTools.extract(work != null ? work : fallback);
        return bundledTools;
    }

    @Override
    public synchronized void close() {
        if (work != null) {
            delete(work);
            work = null;
            bundledTools = null;
        }
        System.out.printf("Image compression: optimized=%d, unchanged=%d, skipped=%d, failed=%d, cached=%d, saved=%d bytes%n",
                optimized.get(), unchanged.get(), skipped.get(), failed.get(), cacheHits.get(), savedBytes.get());
    }

    private static void delete(Path directory) {
        try (java.util.stream.Stream<Path> files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
        } catch (IOException | java.io.UncheckedIOException | SecurityException e) {
            warn("Cannot clean image compression temporary directory " + directory + "; continuing packaging. " + e);
        }
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
                    cacheHits.incrementAndGet();
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
        if (cacheWarning.compareAndSet(false, true)) {
            warn("Image cache unavailable; continuing image compression. " + e);
        }
    }

    private byte[] optimizeUncached(byte[] original, String format, Path work) throws IOException, InterruptedException {
        Path input = work.resolve("input." + format);
        Path output = work.resolve("output." + format);
        Path log = work.resolve("tool.log");
        Files.write(input, original);
        Files.deleteIfExists(output);
        List<String> command = toolsDirectory.isEmpty()
                ? new ImageCompressor(bundledTools(work).toString()).command(format, input, output)
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
