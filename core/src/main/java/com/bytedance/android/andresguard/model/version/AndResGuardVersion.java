package com.bytedance.android.andresguard.model.version;

import com.android.tools.build.bundletool.model.version.Version;

/**
 * Versions of AndResGuard.
 * <p>
 * Created by YangJing on 2019/10/09 .
 * Email: yangjing.yeoh@bytedance.com
 */
public class AndResGuardVersion {

    private static final String CURRENT_VERSION = "0.9.0";

    /**
     * Returns the version of BundleTool being run.
     */
    public static Version getCurrentVersion() {
        return Version.of(CURRENT_VERSION);
    }
}
