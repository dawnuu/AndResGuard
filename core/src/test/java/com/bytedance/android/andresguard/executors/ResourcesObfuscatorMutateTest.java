package com.bytedance.android.andresguard.executors;

import com.google.common.io.ByteSource;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

import static org.junit.Assert.*;

public class ResourcesObfuscatorMutateTest {
    @Test
    public void lazyMutationMatchesInMemoryAppend() throws Exception {
        byte[] data = new byte[100_000];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update("seed:res/a.png:".getBytes(StandardCharsets.UTF_8));
        digest.update(data);
        byte[] expected = Arrays.copyOf(data, data.length + 1);
        expected[data.length] = digest.digest()[0];

        ByteSource mutated = ResourcesObfuscator.mutateData("seed", "res/a.png", ByteSource.wrap(data));
        assertArrayEquals(expected, mutated.read());
        assertEquals(data.length + 1, mutated.size());
    }

    @Test
    public void emptyContentIsNotMutated() throws Exception {
        ByteSource empty = ByteSource.wrap(new byte[0]);
        assertSame(empty, ResourcesObfuscator.mutateData("seed", "res/a.png", empty));
    }
}
