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
    public BundleImageCompressor(String toolsDirectory) { super(toolsDirectory); }
    public BundleImageCompressor(String toolsDirectory, Path cacheDirectory) { super(toolsDirectory, cacheDirectory); }

    public AppBundle compress(AppBundle bundle) throws IOException, InterruptedException {
        ImmutableMap.Builder<BundleModuleName, BundleModule> modules = ImmutableMap.builder();
        try (ImageCompressor ignored = this) {
            for (BundleModule module : bundle.getModules().values()) {
                List<ModuleEntry> entries = new ArrayList<>();
                for (ModuleEntry entry : module.getEntries()) {
                    String path = entry.getPath().toString();
                    if ((!path.startsWith("res/") && !path.startsWith("assets/")) || format(path) == null) {
                        entries.add(entry);
                        continue;
                    }
                    byte[] original = entry.getContent().read();
                    byte[] result = compressImage(path, module.getName() + "/" + path, original);
                    entries.add(result == original ? entry : entry.toBuilder().setContent(ByteSource.wrap(result))
                            .setFileLocation(Optional.empty()).build());
                }
                modules.put(module.getName(), module.toBuilder().setRawEntries(entries).build());
            }
        }
        return bundle.toBuilder().setModules(modules.build()).build();
    }
}
