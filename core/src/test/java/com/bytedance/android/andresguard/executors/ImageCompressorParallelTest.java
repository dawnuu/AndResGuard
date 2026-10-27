package com.bytedance.android.andresguard.executors;

import org.junit.Assume;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ImageCompressorParallelTest {
    private static final int IMAGES = 12;

    @Test
    public void parallelResultsKeepRequestOrderAndRespectParallelism() throws Exception {
        assertEquals(3, run(3));
    }

    @Test
    public void parallelismOfOneIsSerial() throws Exception {
        assertEquals(1, run(1));
    }

    /** Returns the highest number of tool processes that ran at the same time. */
    private int run(int parallelism) throws Exception {
        Assume.assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        Path root = Files.createTempDirectory("image-parallel-test-");
        try {
            Path tools = Files.createDirectories(root.resolve("tools"));
            Path running = Files.createDirectories(root.resolve("running"));
            Path log = root.resolve("concurrency.log");
            // Records how many tool processes are running, then keeps the first 8 bytes as the "compressed" image.
            String script = "#!/bin/sh\n"
                    + "touch '" + running + "'/$$\n"
                    + "ls '" + running + "' | wc -l >> '" + log + "'\n"
                    + "sleep 0.3\n"
                    + "head -c 8 \"$7\" > \"$6\"\n"
                    + "rm -f '" + running + "'/$$\n";
            Path tool = tools.resolve("optipng");
            Files.write(tool, script.getBytes(StandardCharsets.UTF_8));
            assertTrue(tool.toFile().setExecutable(true));

            List<ImageCompressor.Request> requests = new ArrayList<>();
            for (int i = 0; i < IMAGES; i++) {
                byte[] original = original(i);
                requests.add(new ImageCompressor.Request("res/drawable/img" + i + ".png", "img" + i, () -> original));
            }
            try (ImageCompressor compressor = new ImageCompressor(tools.toString());
                 ImageCompressor.OrderedCompression images = compressor.compressInOrder(requests, parallelism)) {
                for (int i = 0; i < IMAGES; i++) {
                    assertArrayEquals("image " + i, Arrays.copyOf(original(i), 8), images.next());
                }
            }
            return Files.readAllLines(log).stream().mapToInt(line -> Integer.parseInt(line.trim())).max().orElse(0);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(root)) {
                for (Path file : (Iterable<Path>) files.sorted(java.util.Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
            }
        }
    }

    /** A PNG-like payload whose first 8 bytes identify the image; the rest is zeros so it is not skipped. */
    private static byte[] original(int index) {
        byte[] data = new byte[100];
        Arrays.fill(data, 0, 8, (byte) (index + 1));
        return data;
    }
}
