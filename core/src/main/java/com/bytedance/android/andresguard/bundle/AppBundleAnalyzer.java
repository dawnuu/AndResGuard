package com.bytedance.android.andresguard.bundle;

import com.android.tools.build.bundletool.model.AppBundle;
import com.bytedance.android.andresguard.utils.TimeClock;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.logging.Logger;
import java.util.zip.ZipFile;

import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;

/**
 * Created by YangJing on 2019/10/10 .
 * Email: yangjing.yeoh@bytedance.com
 */
public class AppBundleAnalyzer implements Closeable {

    private static final Logger logger = Logger.getLogger(AppBundleAnalyzer.class.getName());
    private final Path bundlePath;
    private ZipFile bundleZip;

    public AppBundleAnalyzer(Path bundlePath) {
        checkFileExistsAndReadable(bundlePath);
        this.bundlePath = bundlePath;
    }

    public AppBundle analyze() throws IOException {
        TimeClock timeClock = new TimeClock();
        bundleZip = new ZipFile(bundlePath.toFile());
        AppBundle appBundle = AppBundle.buildFromZip(bundleZip);
        System.out.println(String.format("analyze bundle file done, const %s", timeClock.getCost()));
        return appBundle;
    }

    /** The analyzed bundle reads entries lazily from this zip, so close it only after packaging. */
    @Override
    public void close() throws IOException {
        if (bundleZip != null) bundleZip.close();
    }
}
