package com.bytedance.android.andresguard.executors;

import com.android.tools.build.bundletool.model.AppBundle;
import com.android.tools.build.bundletool.model.BundleModule;
import com.android.tools.build.bundletool.model.BundleModuleName;
import com.android.tools.build.bundletool.model.ModuleEntry;
import com.google.common.collect.ImmutableMap;
import com.google.common.io.ByteSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** AAB adapter for the format-independent image compressor. */
public final class BundleImageCompressor extends ImageCompressor {
    private final int parallelism;

    public BundleImageCompressor(String toolsDirectory) { this(toolsDirectory, null, 0); }
    public BundleImageCompressor(String toolsDirectory, Path cacheDirectory) { this(toolsDirectory, cacheDirectory, 0); }
    /** {@code parallelism <= 0} picks a default from the CPU count; 1 compresses serially. */
    public BundleImageCompressor(String toolsDirectory, Path cacheDirectory, int parallelism) {
        super(toolsDirectory, cacheDirectory);
        this.parallelism = parallelism;
    }

    public AppBundle compress(AppBundle bundle) throws IOException, InterruptedException {
        ImmutableMap.Builder<BundleModuleName, BundleModule> modules = ImmutableMap.builder();
        List<Request> requests = new ArrayList<>();
        for (BundleModule module : bundle.getModules().values()) {
            for (ModuleEntry entry : module.getEntries()) {
                String path = entry.getPath().toString();
                if (isImage(path)) requests.add(new Request(path, module.getName() + "/" + path, entry.getContent()::read));
            }
        }
        try (ImageCompressor ignored = this; OrderedCompression images = compressInOrder(requests, parallelism)) {
            for (BundleModule module : bundle.getModules().values()) {
                List<ModuleEntry> entries = new ArrayList<>();
                for (ModuleEntry entry : module.getEntries()) {
                    if (!isImage(entry.getPath().toString())) {
                        entries.add(entry);
                        continue;
                    }
                    byte[] result = images.next();
                    // Unchanged images keep their original (zip-backed) content.
                    entries.add(result == null ? entry : entry.toBuilder()
                            .setContent(ByteSource.wrap(result)).setFileLocation(Optional.empty()).build());
                }
                modules.put(module.getName(), module.toBuilder().setRawEntries(entries).build());
            }
        }
        return bundle.toBuilder().setModules(modules.build()).build();
    }

    private static boolean isImage(String path) {
        return (path.startsWith("res/") || path.startsWith("assets/")) && format(path) != null;
    }
}
