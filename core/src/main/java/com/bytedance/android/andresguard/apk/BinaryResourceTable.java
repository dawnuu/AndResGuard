package com.bytedance.android.andresguard.apk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Edits resources.arsc without decoding XML or rebuilding resource IDs.
 * Uses the string-pool/entry rewriting approach of shwenzhang/AndResGuard:
 * https://github.com/shwenzhang/AndResGuard (Apache-2.0).
 * Binary layouts follow Android's androidfw/ResourceTypes.h, including
 * sparse indices, offset16 indices and compact entries.
 */
final class BinaryResourceTable {
    private final byte[] header;
    private final List<Chunk> chunks;
    private final StringPool strings;
    private final List<ResPackage> packages = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    BinaryResourceTable(byte[] data) throws IOException {
        check(data.length >= 12 && u16(data, 0) == 2 && i32(data, 4) == data.length, "Invalid resources.arsc");
        int headerSize = u16(data, 2);
        check(headerSize >= 12 && headerSize <= data.length, "Invalid resource table header");
        header = Arrays.copyOf(data, headerSize);
        chunks = chunks(data, headerSize, data.length);
        StringPool global = null;
        for (Chunk chunk : chunks) {
            if (chunk.type == 1 && global == null) global = new StringPool(chunk.data);
        }
        check(global != null, "Resource table has no string pool");
        strings = global;
        for (Chunk chunk : chunks) {
            if (chunk.type == 0x200) {
                ResPackage pkg = new ResPackage(chunk);
                packages.add(pkg);
                entries.addAll(pkg.entries);
            }
        }
        check(packages.size() == i32(header, 8), "Resource package count mismatch");
    }

    List<Entry> entries() { return Collections.unmodifiableList(entries); }

    byte[] write() throws IOException {
        for (ResPackage pkg : packages) pkg.rewriteKeys();
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        result.write(header);
        for (Chunk chunk : chunks) {
            if (chunk.type == 1 && chunk.data == strings.original) result.write(strings.write());
            else if (chunk.type == 0x200) {
                ResPackage pkg = packages.stream().filter(p -> p.chunk == chunk).findFirst().get();
                result.write(pkg.write());
            } else result.write(chunk.data);
        }
        byte[] output = result.toByteArray();
        put32(output, 4, output.length);
        return output;
    }

    void replaceFilePaths(Map<String, String> replacements) throws IOException {
        Map<Integer, String> paths = new LinkedHashMap<>();
        java.util.Set<Integer> textIndices = new java.util.HashSet<>();
        for (Entry entry : entries) {
            if (entry.removed) continue;
            if (entry.valueType() == 3) {
                int index = entry.valueIndex();
                String value = strings.get(index);
                if (!entry.type.equals("string") && replacements.containsKey(value)) paths.put(index, replacements.get(value));
                else textIndices.add(index);
            } else if (entry.complex()) {
                int count = i32(entry.chunk.data, entry.offset + 12);
                int start = entry.offset + u16(entry.chunk.data, entry.offset);
                check(count >= 0 && (long) start + (long) count * 12 <= entry.chunk.data.length, "Invalid complex entry");
                for (int i = 0; i < count; i++) {
                    int value = start + i * 12 + 4;
                    if ((entry.chunk.data[value + 3] & 255) == 3) textIndices.add(i32(entry.chunk.data, value + 4));
                }
            }
        }
        for (Map.Entry<Integer, String> path : paths.entrySet()) {
            int oldIndex = path.getKey();
            if (!textIndices.contains(oldIndex)) strings.values.set(oldIndex, path.getValue());
            else {
                int newIndex = strings.add(path.getValue());
                for (Entry entry : entries) {
                    if (!entry.removed && entry.valueType() == 3 && entry.valueIndex() == oldIndex && !entry.type.equals("string"))
                        entry.setValueIndex(newIndex);
                }
            }
        }
    }

    void replaceString(Entry entry, String value) {
        entry.setValueIndex(strings.add(value));
    }

    final class Entry {
        final ResPackage pkg;
        final Chunk chunk;
        final int offset, indexSlot, indexId, id;
        final String type, originalName, locale;
        String name;
        boolean removed;
        Entry(ResPackage pkg, Chunk chunk, int offset, int indexSlot, int indexId, String type, String locale) throws IOException {
            this.pkg = pkg; this.chunk = chunk; this.offset = offset; this.indexSlot = indexSlot; this.indexId = indexId;
            this.type = type; this.locale = locale;
            int typeId = (chunk.data[8] & 255) + pkg.typeIdOffset;
            check(typeId > 0 && typeId <= 255 && indexId <= 65535, "Invalid resource ID");
            id = (pkg.id << 24) | (typeId << 16) | indexId;
            check(offset >= i32(chunk.data, 16) && offset <= chunk.data.length - 8, "Invalid resource entry offset");
            int keyIndex = compact() ? u16(chunk.data, offset) : i32(chunk.data, offset + 4);
            name = originalName = pkg.keys.get(keyIndex);
            if (!compact()) {
                int size = u16(chunk.data, offset);
                check(size >= (complex() ? 16 : 8) && offset + size <= chunk.data.length, "Invalid resource entry header");
                if (!complex()) check(offset + size + 8 <= chunk.data.length, "Invalid resource value");
            }
        }
        String fullName() { return pkg.name + ".R." + type + "." + originalName; }
        String packageName() { return pkg.name; }
        int packageId() { return pkg.id; }
        String resourceId() { return String.format("0x%08x", id); }
        boolean compact() { return (u16(chunk.data, offset + 2) & 8) != 0; }
        int indexEncoding() { return chunk.data[9] & 255; }
        boolean complex() { return (u16(chunk.data, offset + 2) & 1) != 0; }
        int valueType() {
            if (complex()) return -1;
            return compact() ? (u16(chunk.data, offset + 2) >>> 8) : (chunk.data[offset + u16(chunk.data, offset) + 3] & 255);
        }
        int valueIndex() { return i32(chunk.data, compact() ? offset + 4 : offset + u16(chunk.data, offset) + 4); }
        void setValueIndex(int index) { put32(chunk.data, compact() ? offset + 4 : offset + u16(chunk.data, offset) + 4, index); }
        String filePath() throws IOException {
            if (valueType() != 3 || type.equals("string")) return null;
            String value = strings.get(valueIndex());
            return value.startsWith("res/") ? value : null;
        }
        void removeConfig() { removed = true; }
    }

    private final class ResPackage {
        final Chunk chunk;
        final int id, typeIdOffset;
        final String name;
        final List<Chunk> children;
        final StringPool types, keys;
        final Chunk typePoolChunk, keyPoolChunk;
        final List<Entry> entries = new ArrayList<>();
        ResPackage(Chunk chunk) throws IOException {
            this.chunk = chunk;
            int size = u16(chunk.data, 2);
            check(size >= 284 && size <= chunk.data.length, "Invalid resource package header");
            id = i32(chunk.data, 8);
            check(id >= 0 && id <= 255, "Invalid resource package ID");
            name = utf16Name(chunk.data, 12, 128);
            typeIdOffset = size >= 288 ? i32(chunk.data, 284) : 0;
            children = chunks(chunk.data, size, chunk.data.length);
            typePoolChunk = childAt(i32(chunk.data, 268));
            keyPoolChunk = childAt(i32(chunk.data, 276));
            check(typePoolChunk != null && keyPoolChunk != null && typePoolChunk.type == 1 && keyPoolChunk.type == 1, "Invalid package string pools");
            types = new StringPool(typePoolChunk.data);
            keys = new StringPool(keyPoolChunk.data);
            check(keys.styleCount == 0, "Styled resource key pool is unsupported");
            for (Chunk child : children) if (child.type == 0x201) readType(child);
        }
        Chunk childAt(int offset) {
            int position = u16(chunk.data, 2);
            for (Chunk child : children) {
                if (position == offset) return child;
                position += child.data.length;
            }
            return null;
        }
        void readType(Chunk child) throws IOException {
            byte[] data = child.data;
            int headerSize = u16(data, 2);
            check(headerSize >= 24 && headerSize <= data.length, "Invalid resource type header");
            int typeId = data[8] & 255;
            String type = types.get(typeId - 1);
            int flags = data[9] & 255;
            check((flags & ~3) == 0 && flags != 3, "Unsupported resource offset flags: " + flags);
            int count = i32(data, 12), start = i32(data, 16);
            int stride = (flags & 2) != 0 ? 2 : 4;
            check(count >= 0 && start >= headerSize && start <= data.length
                    && (long) headerSize + (long) count * stride <= start, "Invalid resource offsets");
            int configSize = i32(data, 20);
            check(configSize >= 4 && 20L + configSize <= headerSize, "Invalid resource configuration");
            String locale = configSize >= 12 ? language(data[28], data[29]) : "";
            for (int i = 0; i < count; i++) {
                int slot = headerSize + i * stride;
                int entryId = (flags & 1) != 0 ? u16(data, slot) : i;
                int offset = (flags & 1) != 0 ? u16(data, slot + 2) * 4
                        : (flags & 2) != 0 ? (u16(data, slot) == 65535 ? -1 : u16(data, slot) * 4) : i32(data, slot);
                if (offset == -1) continue;
                check(offset >= 0 && (long) start + offset <= data.length - 8, "Invalid entry offset");
                entries.add(new Entry(this, child, start + offset, slot, entryId, type, locale));
            }
        }
        void rewriteKeys() throws IOException {
            // Omit configurations with no remaining resources rather than emitting an empty sparse type.
            children.removeIf(child -> child.type == 0x201
                    && entries.stream().anyMatch(entry -> entry.chunk == child && entry.removed)
                    && entries.stream().noneMatch(entry -> entry.chunk == child && !entry.removed));
            List<String> names = new ArrayList<>();
            Map<String, Integer> indices = new LinkedHashMap<>();
            for (Entry entry : entries) {
                // Retain keys for removed configurations so all other chunk metadata remains valid.
                Integer index = indices.get(entry.name);
                if (index == null) { index = names.size(); names.add(entry.name); indices.put(entry.name, index); }
                if (entry.compact()) {
                    check(index <= 65535, "Too many keys for compact resource entries");
                    put16(entry.chunk.data, entry.offset, index);
                } else put32(entry.chunk.data, entry.offset + 4, index);
            }
            keys.values.clear();
            keys.values.addAll(names);
            for (Chunk child : children) {
                if (child.type != 0x201) continue;
                int flags = child.data[9] & 255;
                List<Entry> removed = new ArrayList<>();
                for (Entry entry : entries) if (entry.chunk == child && entry.removed) removed.add(entry);
                if ((flags & 1) == 0) {
                    for (Entry entry : removed) {
                        if ((flags & 2) != 0) put16(child.data, entry.indexSlot, 65535);
                        else put32(child.data, entry.indexSlot, -1);
                    }
                } else if (!removed.isEmpty()) {
                    int headerSize = u16(child.data, 2);
                    int count = i32(child.data, 12), kept = 0;
                    java.util.Set<Integer> removedIds = new java.util.HashSet<>();
                    for (Entry entry : removed) removedIds.add(entry.indexId);
                    for (int i = 0; i < count; i++) {
                        int slot = headerSize + i * 4;
                        if (!removedIds.contains(u16(child.data, slot))) {
                            System.arraycopy(child.data, slot, child.data, headerSize + kept * 4, 4);
                            kept++;
                        }
                    }
                    Arrays.fill(child.data, headerSize + kept * 4, headerSize + count * 4, (byte) 0);
                    put32(child.data, 12, kept);
                }
            }
        }
        byte[] write() throws IOException {
            int headerSize = u16(chunk.data, 2);
            byte[] pkgHeader = Arrays.copyOf(chunk.data, headerSize);
            ByteArrayOutputStream result = new ByteArrayOutputStream();
            result.write(pkgHeader);
            for (Chunk child : children) {
                if (child == typePoolChunk) put32(pkgHeader, 268, result.size());
                if (child == keyPoolChunk) put32(pkgHeader, 276, result.size());
                result.write(child == keyPoolChunk ? keys.write() : child.data);
            }
            byte[] output = result.toByteArray();
            System.arraycopy(pkgHeader, 0, output, 0, headerSize);
            put32(output, 4, output.length);
            // All public keys now lie inside the rebuilt key pool.
            put32(output, 280, keys.values.size());
            return output;
        }
    }

    static void checkStandaloneManifest(byte[] data) throws IOException {
        check(data.length >= 8 && u16(data, 0) == 3 && i32(data, 4) == data.length, "Invalid binary AndroidManifest.xml");
        StringPool pool = null;
        for (Chunk chunk : chunks(data, u16(data, 2), data.length)) {
            if (chunk.type == 1) pool = new StringPool(chunk.data);
            if (chunk.type != 0x102) continue;
            check(pool != null && chunk.data.length >= 36, "Invalid manifest element");
            int extension = u16(chunk.data, 2);
            check(extension >= 16 && extension + 20 <= chunk.data.length, "Invalid manifest node header");
            if (!pool.get(i32(chunk.data, extension + 4)).equals("manifest")) continue;
            int start = extension + u16(chunk.data, extension + 8);
            int stride = u16(chunk.data, extension + 10), count = u16(chunk.data, extension + 12);
            check(stride >= 20 && start >= extension + 20 && (long) start + (long) stride * count <= chunk.data.length, "Invalid manifest attributes");
            for (int i = 0; i < count; i++) {
                int attr = start + i * stride;
                if (!pool.get(i32(chunk.data, attr + 4)).equals("split")) continue;
                int raw = i32(chunk.data, attr + 8);
                if ((raw != -1 && !pool.get(raw).isEmpty())
                        || ((chunk.data[attr + 15] & 255) == 3 && !pool.get(i32(chunk.data, attr + 16)).isEmpty()))
                    throw new IOException("Split APKs are not supported. Supply a standalone/universal APK.");
            }
        }
    }

    private static List<Chunk> chunks(byte[] data, int start, int end) throws IOException {
        List<Chunk> result = new ArrayList<>();
        check(start >= 8 && start <= end && end <= data.length, "Invalid chunk range");
        for (int offset = start; offset < end; ) {
            check(offset <= end - 8, "Truncated resource chunk");
            int size = i32(data, offset + 4), header = u16(data, offset + 2);
            check(size >= 8 && header >= 8 && header <= size && (long) offset + size <= end, "Invalid resource chunk size");
            result.add(new Chunk(u16(data, offset), Arrays.copyOfRange(data, offset, offset + size)));
            offset += size;
        }
        return result;
    }

    private static final class Chunk {
        final int type;
        final byte[] data;
        Chunk(int type, byte[] data) { this.type = type; this.data = data; }
    }

    private static final class StringPool {
        final byte[] original;
        final List<String> values = new ArrayList<>();
        final int headerSize, styleCount, flags;
        final byte[] styleOffsets, styles;
        StringPool(byte[] data) throws IOException {
            original = data;
            check(data.length >= 28 && u16(data, 0) == 1, "Invalid string pool");
            headerSize = u16(data, 2);
            int count = i32(data, 8);
            styleCount = i32(data, 12);
            flags = i32(data, 16);
            int stringsStart = i32(data, 20), stylesStart = i32(data, 24);
            check(count >= 0 && styleCount >= 0 && styleCount <= count && headerSize >= 28
                    && headerSize + 4L * (count + (long) styleCount) <= stringsStart
                    && stringsStart <= data.length
                    && (styleCount == 0 || (stylesStart >= stringsStart && stylesStart <= data.length)), "Invalid string pool offsets");
            int end = stylesStart > 0 ? stylesStart : data.length;
            for (int i = 0; i < count; i++) {
                int relative = i32(data, headerSize + i * 4);
                check(relative >= 0 && (long) stringsStart + relative < end, "Invalid string offset");
                int[] pos = {stringsStart + relative};
                if ((flags & 0x100) != 0) {
                    length8(data, pos, end);
                    int bytes = length8(data, pos, end);
                    check(bytes >= 0 && (long) pos[0] + bytes < end && data[pos[0] + bytes] == 0, "Invalid UTF-8 resource string");
                    values.add(new String(data, pos[0], bytes, StandardCharsets.UTF_8));
                } else {
                    int length = length16(data, pos, end);
                    check(length >= 0 && pos[0] + 2L * length + 2 <= end && u16(data, pos[0] + length * 2) == 0, "Invalid UTF-16 resource string");
                    values.add(new String(data, pos[0], length * 2, StandardCharsets.UTF_16LE));
                }
            }
            styleOffsets = Arrays.copyOfRange(data, headerSize + count * 4, headerSize + (count + styleCount) * 4);
            styles = stylesStart > 0 ? Arrays.copyOfRange(data, stylesStart, data.length) : new byte[0];
        }
        String get(int index) throws IOException {
            check(index >= 0 && index < values.size(), "Invalid resource string index: " + index);
            return values.get(index);
        }
        int add(String value) { int index = values.indexOf(value); if (index >= 0) return index; values.add(value); return values.size() - 1; }
        byte[] write() throws IOException {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            int[] offsets = new int[values.size()];
            for (int i = 0; i < values.size(); i++) {
                String value = values.get(i);
                offsets[i] = contents.size();
                if ((flags & 0x100) != 0) {
                    byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
                    writeLength8(contents, value.length()); writeLength8(contents, utf8.length);
                    contents.write(utf8); contents.write(0);
                } else {
                    writeLength16(contents, value.length());
                    contents.write(value.getBytes(StandardCharsets.UTF_16LE)); contents.write(0); contents.write(0);
                }
            }
            while (contents.size() % 4 != 0) contents.write(0);
            int stringsStart = headerSize + (values.size() + styleCount) * 4;
            int stylesStart = styles.length == 0 ? 0 : stringsStart + contents.size();
            byte[] result = new byte[stringsStart + contents.size() + styles.length];
            System.arraycopy(original, 0, result, 0, headerSize);
            put32(result, 4, result.length); put32(result, 8, values.size());
            put32(result, 16, flags & ~1); // Rewritten strings are no longer guaranteed to be sorted.
            put32(result, 20, stringsStart); put32(result, 24, stylesStart);
            for (int i = 0; i < offsets.length; i++) put32(result, headerSize + i * 4, offsets[i]);
            System.arraycopy(styleOffsets, 0, result, headerSize + values.size() * 4, styleOffsets.length);
            System.arraycopy(contents.toByteArray(), 0, result, stringsStart, contents.size());
            if (styles.length > 0) System.arraycopy(styles, 0, result, stylesStart, styles.length);
            return result;
        }
    }

    private static int length8(byte[] data, int[] pos, int end) throws IOException {
        check(pos[0] < end, "Truncated UTF-8 string length");
        int first = data[pos[0]++] & 255;
        if ((first & 128) == 0) return first;
        check(pos[0] < end, "Truncated UTF-8 string length");
        return ((first & 127) << 8) | (data[pos[0]++] & 255);
    }
    private static int length16(byte[] data, int[] pos, int end) throws IOException {
        check(pos[0] + 2 <= end, "Truncated UTF-16 string length");
        int first = u16(data, pos[0]); pos[0] += 2;
        if ((first & 32768) == 0) return first;
        check(pos[0] + 2 <= end, "Truncated UTF-16 string length");
        int last = u16(data, pos[0]); pos[0] += 2;
        return ((first & 32767) << 16) | last;
    }
    private static void writeLength8(ByteArrayOutputStream out, int length) throws IOException {
        check(length <= 32767, "UTF-8 resource string is too long");
        if (length > 127) out.write((length >>> 8) | 128);
        out.write(length & 255);
    }
    private static void writeLength16(ByteArrayOutputStream out, int length) {
        if (length > 32767) { out.write(((length >>> 16) & 255)); out.write(((length >>> 24) & 127) | 128); }
        out.write(length & 255); out.write((length >>> 8) & 255);
    }
    private static String utf16Name(byte[] data, int start, int count) {
        int length = 0;
        while (length < count && u16(data, start + length * 2) != 0) length++;
        return new String(data, start, length * 2, StandardCharsets.UTF_16LE);
    }
    private static String language(byte first, byte second) {
        int a = first & 255, b = second & 255;
        if (a == 0 && b == 0) return "";
        if ((a & 128) == 0) return "" + (char) a + (char) b;
        return "" + (char) ('a' + (b & 31)) + (char) ('a' + ((b >>> 5) | ((a & 3) << 3))) + (char) ('a' + ((a & 124) >>> 2));
    }
    private static int u16(byte[] data, int offset) { return (data[offset] & 255) | ((data[offset + 1] & 255) << 8); }
    private static int i32(byte[] data, int offset) { return u16(data, offset) | (u16(data, offset + 2) << 16); }
    private static void put16(byte[] data, int offset, int value) { data[offset] = (byte) value; data[offset + 1] = (byte) (value >>> 8); }
    private static void put32(byte[] data, int offset, int value) { put16(data, offset, value); put16(data, offset + 2, value >>> 16); }
    private static void check(boolean condition, String message) throws IOException { if (!condition) throw new IOException(message); }
}
