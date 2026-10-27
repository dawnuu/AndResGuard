package com.bytedance.android.andresguard.commands;

import static com.android.tools.build.bundletool.model.utils.files.FilePreconditions.checkFileExistsAndReadable;
import static com.bytedance.android.andresguard.utils.FileOperation.getNetFileSizeDescription;
import static com.bytedance.android.andresguard.utils.exception.CommandExceptionPreconditions.commandExecutionException;
import static com.bytedance.android.andresguard.utils.exception.CommandExceptionPreconditions.checkFlagPresent;

import com.android.tools.build.bundletool.flags.Flag;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.android.tools.build.bundletool.model.AppBundle;
import com.bytedance.android.andresguard.android.JarSigner;
import com.bytedance.android.andresguard.apk.ApkProcessor;
import com.bytedance.android.andresguard.bundle.AppBundleAnalyzer;
import com.bytedance.android.andresguard.bundle.AppBundlePackager;
import com.bytedance.android.andresguard.bundle.AppBundleSigner;
import com.bytedance.android.andresguard.executors.BaseRootFileRemove;
import com.bytedance.android.andresguard.executors.BundleFileFilter;
import com.bytedance.android.andresguard.executors.BundleMetadataRemove;
import com.bytedance.android.andresguard.executors.BundleStringFilter;
import com.bytedance.android.andresguard.executors.BundleImageCompressor;
import com.bytedance.android.andresguard.executors.DuplicatedResourcesMerger;
import com.bytedance.android.andresguard.executors.ResourcesObfuscator;
import com.bytedance.android.andresguard.model.xml.AndResGuardConfig;
import com.bytedance.android.andresguard.parser.AndResGuardXmlParser;
import com.bytedance.android.andresguard.utils.FileOperation;
import com.bytedance.android.andresguard.utils.TimeClock;
import com.bytedance.android.andresguard.utils.VirusTotalUploader;
import com.google.auto.value.AutoValue;
import com.google.common.io.Closer;

import org.dom4j.DocumentException;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Unified resource-processing command for AAB and APK inputs.
 * <p>
 * Created by YangJing on 2019/10/09 .
 * Email: yangjing.yeoh@bytedance.com
 */
@AutoValue
public abstract class ObfuscateResourcesCommand {
    public static final String COMMAND_NAME = "obfuscate";
    private static final Logger logger = Logger.getLogger(ObfuscateResourcesCommand.class.getName());

    private static final Flag<Path> INPUT_LOCATION_FLAG = Flag.path("input");
    private static final Flag<String> BUILD_TOOLS_FLAG = Flag.string("build-tools-dir");
    private static final Flag<Path> OUTPUT_FILE_FLAG = Flag.path("output");
    private static final Flag<Path> CONFIG_FLAG = Flag.path("config");
    private static final Flag<Path> MAPPING_FLAG = Flag.path("mapping");

    private static final Flag<Boolean> MERGE_DUPLICATED_RES_FLAG = Flag.booleanFlag("merge-duplicated-res");
    private static final Flag<Boolean> COMPRESS_IMAGES_FLAG = Flag.booleanFlag("compress-images");
    private static final Flag<String> IMAGE_TOOLS_DIR_FLAG = Flag.string("image-compression-tools-dir");
    private static final Flag<Boolean> REMOVE_BUNDLE_METADATA_FLAG = Flag.booleanFlag("remove-bundle-metadata");
    private static final Flag<Boolean> REMOVE_ROOT_FILES_FLAG = Flag.booleanFlag("remove-root-files");

    private static final Flag<Boolean> DISABLE_SIGN_FLAG = Flag.booleanFlag("disable-sign");
    private static final Flag<Path> STORE_FILE_FLAG = Flag.path("storeFile");
    private static final Flag<String> STORE_PASSWORD_FLAG = Flag.string("storePassword");
    private static final Flag<String> KEY_ALIAS_FLAG = Flag.string("keyAlias");
    private static final Flag<String> KEY_PASSWORD_FLAG = Flag.string("keyPassword");

    public static CommandHelp help() {
        return CommandHelp.builder()
                .setCommandName(COMMAND_NAME)
                .setCommandDescription(
                        CommandHelp.CommandDescription.builder()
                                .setShortDescription("Obfuscates resources in an AAB or APK. APK requires Android SDK Build Tools 35+.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(INPUT_LOCATION_FLAG.getName())
                                .setExampleValue("bundle.aab")
                                .setDescription("Input Android App Bundle (.aab) or standalone APK (.apk).")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(OUTPUT_FILE_FLAG.getName())
                                .setExampleValue("obfuscated.aab")
                                .setDescription("Output AAB or APK path, matching the input format.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(BUILD_TOOLS_FLAG.getName())
                                .setOptional(true)
                                .setDescription("APK only: Build Tools directory containing aapt2, zipalign and lib/apksigner.jar. Defaults to ANDROID_HOME/ANDROID_SDK_ROOT.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(CONFIG_FLAG.getName())
                                .setExampleValue("config.xml")
                                .setDescription(
                                        "Path of the Obfuscate configuration parser file, priority is lower than the command " +
                                                "line.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(MAPPING_FLAG.getName())
                                .setExampleValue("resources-mapping.txt")
                                .setOptional(true)
                                .setDescription("The mapping file path for resource increment obfuscation.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(MERGE_DUPLICATED_RES_FLAG.getName())
                                .setExampleValue("merge-duplicated-res=true")
                                .setOptional(true)
                                .setDescription("If set, the duplicate resource files will be removed.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(COMPRESS_IMAGES_FLAG.getName())
                                .setOptional(true)
                                .setDescription("Losslessly optimize PNG, JPEG, GIF and static WebP using bundled native tools.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(IMAGE_TOOLS_DIR_FLAG.getName())
                                .setOptional(true)
                                .setDescription("Optional directory overriding bundled optipng, jpegtran, cwebp and gifsicle.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(DISABLE_SIGN_FLAG.getName())
                                .setExampleValue("disable-sign=true")
                                .setOptional(true)
                                .setDescription("If true, do not sign the output AAB or APK after packaging.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(STORE_FILE_FLAG.getName())
                                .setExampleValue("store.keystore")
                                .setOptional(true)
                                .setDescription("Path of the keystore file.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(STORE_PASSWORD_FLAG.getName())
                                .setOptional(true)
                                .setDescription("Path of the keystore password.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(KEY_ALIAS_FLAG.getName())
                                .setOptional(true)
                                .setDescription("Path of the key alias name.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(KEY_PASSWORD_FLAG.getName())
                                .setOptional(true)
                                .setDescription("Path of the key password.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(REMOVE_BUNDLE_METADATA_FLAG.getName())
                                .setExampleValue("remove-bundle-metadata=true")
                                .setOptional(true)
                                .setDescription("AAB only: remove bundle metadata when true.")
                                .build())
                .addFlag(
                        CommandHelp.FlagDescription.builder()
                                .setFlagName(REMOVE_ROOT_FILES_FLAG.getName())
                                .setExampleValue("remove-root-files=true")
                                .setOptional(true)
                                .setDescription("AAB only: remove files in base/root when true. Test application stability before enabling.")
                                .build())
                .build();
    }

    public static Builder builder() {
        return new AutoValue_ObfuscateResourcesCommand.Builder();
    }

    public static ObfuscateResourcesCommand fromFlags(ParsedFlags flags) throws DocumentException {
        Builder builder = builder();
        builder.setEnableObfuscate(true);
        builder.setInputPath(INPUT_LOCATION_FLAG.getRequiredValue(flags));
        BUILD_TOOLS_FLAG.getValue(flags).ifPresent(builder::setApkBuildToolsDir);
        REMOVE_BUNDLE_METADATA_FLAG.getValue(flags).ifPresent(builder::setRemoveBundleMetadata);
        REMOVE_ROOT_FILES_FLAG.getValue(flags).ifPresent(builder::setRemoveRootFiles);
        // config
        Path path = CONFIG_FLAG.getRequiredValue(flags);
        AndResGuardConfig config = new AndResGuardXmlParser(path).parse();
        builder.setWhiteList(config.getWhiteList());
        if (config.getFileFilter() != null) {
            builder.setFilterFile(config.getFileFilter().isActive());
            builder.setFileFilterRules(config.getFileFilter().getRules());
        }
        MAPPING_FLAG.getValue(flags).ifPresent(builder::setMappingPath);

        if (config.getStringFilterConfig() != null) {
            builder.setRemoveStr(config.getStringFilterConfig().isActive());
            builder.setUnusedStrPath(config.getStringFilterConfig().getPath());
            builder.setLanguageWhiteList(config.getStringFilterConfig().getLanguageWhiteList());
        }

        builder.setOutputPath(OUTPUT_FILE_FLAG.getRequiredValue(flags));
        builder.setBundleMetaDataWhiteList(new HashSet<>());

        MERGE_DUPLICATED_RES_FLAG.getValue(flags).ifPresent(builder::setMergeDuplicatedResources);
        COMPRESS_IMAGES_FLAG.getValue(flags).ifPresent(builder::setEnableCompressImages);
        IMAGE_TOOLS_DIR_FLAG.getValue(flags).ifPresent(builder::setImageCompressionToolsDir);

        DISABLE_SIGN_FLAG.getValue(flags).ifPresent(builder::setDisableSign);
        STORE_FILE_FLAG.getValue(flags).ifPresent(builder::setStoreFile);
        STORE_PASSWORD_FLAG.getValue(flags).ifPresent(builder::setStorePassword);
        KEY_ALIAS_FLAG.getValue(flags).ifPresent(builder::setKeyAlias);
        KEY_PASSWORD_FLAG.getValue(flags).ifPresent(builder::setKeyPassword);
        return builder.build();
    }

    public Path execute() throws IOException, InterruptedException {
        Files.createDirectories(getOutputPath().getParent());
        if (getInputPath().getFileName().toString().endsWith(".apk")) {
            return new ApkProcessor(this).execute();
        }
        // Stages read the input AAB lazily until packaging; close every zip handle once at the end.
        try (Closer closer = Closer.create()) {
            return executeBundle(closer);
        }
    }

    private Path executeBundle(Closer closer) throws IOException, InterruptedException {
        TimeClock timeClock = new TimeClock();

        AppBundle appBundle = closer.register(new AppBundleAnalyzer(getInputPath())).analyze();
        // filter file
        if (getFilterFile().isPresent() && getFilterFile().get()) {
            Set<String> fileFilterRules = new HashSet<>();
            if (getFileFilterRules().isPresent()) {
                fileFilterRules = getFileFilterRules().get();
            }
            BundleFileFilter filter = closer.register(new BundleFileFilter(getInputPath(), appBundle, fileFilterRules));
            appBundle = filter.filter();
        }

        // remove unused strings, need execute before obfuscate
        if (getRemoveStr().isPresent() && getRemoveStr().get()) {
            File unusedFile = new File("");
            if (getUnusedStrPath().isPresent()) {
                File file = new File(getUnusedStrPath().get());
                if (file.exists()) {
                    unusedFile = file;
                } else {
                    System.out.println("unusedFile is not exists!");
                }
            }
            Set<String> languageWhiteList = new HashSet<>();
            if (getLanguageWhiteList().isPresent()) {
                languageWhiteList = getLanguageWhiteList().get();
            }
            BundleStringFilter filter = closer.register(
                    new BundleStringFilter(getInputPath(), appBundle, unusedFile.getPath(), languageWhiteList));
            appBundle = filter.filter();
        }

        // merge duplicated resources
        if (getMergeDuplicatedResources().isPresent() && getMergeDuplicatedResources().get()) {
            DuplicatedResourcesMerger merger = closer.register(
                    new DuplicatedResourcesMerger(getInputPath(), appBundle, getOutputPath().getParent()));
            appBundle = merger.merge();
        }

        // Compress before obfuscation/MD5 mutation, preserving original image names for exclusions.
        if (getEnableCompressImages().orElse(false)) {
            appBundle = new BundleImageCompressor(getImageCompressionToolsDir().orElse(""),
                    getImageCompressionCacheDir().orElse(null)).compress(appBundle);
        }

        // obfuscate bundle
        if (getEnableObfuscate()) {
            Path mappingPath = null;
            if (getMappingPath().isPresent()) {
                mappingPath = getMappingPath().get();
            }
            ResourcesObfuscator obfuscator = new ResourcesObfuscator(
                    getInputPath(),
                    appBundle,
                    getWhiteList(),
                    getOutputPath().getParent(),
                    mappingPath,
                    getUseRandomName().orElse(false),
                    getEnableMutateMd5().orElse(false),
                    getMutateMd5Seed().orElse("")
            );
            appBundle = obfuscator.obfuscate();
        }

        //remove BUNDLE-METADATA
        if (getRemoveBundleMetadata().isPresent() && getRemoveBundleMetadata().get()) {
            BundleMetadataRemove bundleMetadataRemove = closer.register(
                    new BundleMetadataRemove(getInputPath(), appBundle, getBundleMetaDataWhiteList()));
            appBundle = bundleMetadataRemove.remove();
        }

        // remove root
        if (getRemoveRootFiles().isPresent() && getRemoveRootFiles().get()) {
            BaseRootFileRemove baseRootFileRemove = new BaseRootFileRemove(getInputPath(), appBundle);
            appBundle = baseRootFileRemove.remove();
        }

        // package bundle to temporary staging path, then atomically replace output
        Path stagingDir = Files.createTempDirectory(getOutputPath().getParent(), "andresguard-staging-");
        Path stagingOutput = stagingDir.resolve(getOutputPath().getFileName());
        try {
            AppBundlePackager packager = new AppBundlePackager(appBundle, stagingOutput);
            packager.execute();
            // sign bundle
            if (!getDisableSign().isPresent() || !getDisableSign().get()) {
                AppBundleSigner signer = new AppBundleSigner(stagingOutput);
                getStoreFile().ifPresent(storeFile -> {
                    signer.setBundleSignature(new JarSigner.Signature(
                            storeFile, getStorePassword().get(), getKeyAlias().get(), getKeyPassword().get()
                    ));
                });
                signer.execute();
            }
            // atomically replace output with staged file
            try {
                Files.move(stagingOutput, getOutputPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(stagingOutput, getOutputPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            // cleanup staging directory
            try (java.util.stream.Stream<Path> walk = Files.walk(stagingDir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (IOException ignored) {}
                });
            } catch (IOException ignored) {}
        }

        // VirusTotal Upload - 明确在此处调用
        if (getEnableVirusTotalUpload().orElse(false)) {
            System.out.println(">>> VirusTotal Upload is ENABLED. Starting upload...");
            VirusTotalUploader.upload(getVirusTotalApiKey().orElse(""), getOutputPath().toFile());
        }

        long rawSize = FileOperation.getFileSizes(getInputPath().toFile());
        long filteredSize = FileOperation.getFileSizes(getOutputPath().toFile());
        System.out.println(String.format(
                "obfuscate resources done, cost %s\n" +
                        "-----------------------------------------\n" +
                        "Reduce bundle file size: %s, %s -> %s\n" +
                        "-----------------------------------------",
                timeClock.getCost(),
                getNetFileSizeDescription(rawSize - filteredSize),
                getNetFileSizeDescription(rawSize),
                getNetFileSizeDescription(filteredSize)
        ));
        return getOutputPath();
    }

    public abstract Boolean getEnableObfuscate();

    public abstract Path getInputPath();

    public abstract Path getOutputPath();

    public abstract Optional<Path> getMappingPath();

    public abstract Optional<Path> getStoreFile();

    public abstract Optional<String> getStorePassword();

    public abstract Optional<String> getKeyAlias();

    public abstract Optional<String> getKeyPassword();

    public abstract Optional<Boolean> getMergeDuplicatedResources();

    public abstract Optional<Boolean> getEnableCompressImages();

    public abstract Optional<String> getImageCompressionToolsDir();

    public abstract Optional<Path> getImageCompressionCacheDir();

    public abstract Optional<String> getApkBuildToolsDir();

    public abstract Builder toBuilder();

    public abstract Optional<Boolean> getRemoveRootFiles();

    public abstract Optional<Boolean> getRemoveBundleMetadata();

    public abstract Optional<Boolean> getDisableSign();

    public abstract Set<String> getWhiteList();

    public abstract Optional<Set<String>> getFileFilterRules();

    public abstract Optional<Boolean> getFilterFile();

    public abstract Optional<Boolean> getRemoveStr();

    public abstract Optional<String> getUnusedStrPath();

    public abstract Optional<Set<String>> getLanguageWhiteList();

    public abstract Set<String> getBundleMetaDataWhiteList();

    public abstract Optional<Boolean> getUseRandomName();

    public abstract Optional<Boolean> getEnableMutateMd5();

    public abstract Optional<String> getMutateMd5Seed();

    public abstract Optional<Boolean> getEnableVirusTotalUpload();

    public abstract Optional<String> getVirusTotalApiKey();


    @AutoValue.Builder
    public abstract static class Builder {
        public abstract Builder setBundleMetaDataWhiteList(Set<String> whiteList);

        public abstract Builder setRemoveBundleMetadata(Boolean enable);

        public abstract Builder setRemoveRootFiles(Boolean enable);

        public abstract Builder setEnableObfuscate(Boolean enable);

        public abstract Builder setInputPath(Path bundlePath);

        public abstract Builder setOutputPath(Path outputPath);

        public abstract Builder setWhiteList(Set<String> whiteList);

        public abstract Builder setRemoveStr(Boolean removeStr);

        public abstract Builder setUnusedStrPath(String unusedStrPath);

        public abstract Builder setLanguageWhiteList(Set<String> countryFilterSet);

        public abstract Builder setFilterFile(Boolean filterFile);

        public abstract Builder setFileFilterRules(Set<String> fileFilterRules);

        public abstract Builder setMappingPath(Path configPath);

        public abstract Builder setMergeDuplicatedResources(Boolean mergeDuplicatedResources);

        public abstract Builder setEnableCompressImages(Boolean enable);

        public abstract Builder setImageCompressionToolsDir(String directory);

        public abstract Builder setImageCompressionCacheDir(Path directory);

        public abstract Builder setApkBuildToolsDir(String directory);

        public abstract Builder setDisableSign(Boolean disableSign);

        public abstract Builder setStoreFile(Path storeFile);

        public abstract Builder setStorePassword(String storePassword);

        public abstract Builder setKeyAlias(String keyAlias);

        public abstract Builder setKeyPassword(String keyPassword);

        public abstract Builder setUseRandomName(Boolean useRandomName);

        public abstract Builder setEnableMutateMd5(Boolean enableMutateMd5);

        public abstract Builder setMutateMd5Seed(String seed);

        public abstract Builder setEnableVirusTotalUpload(Boolean enable);

        public abstract Builder setVirusTotalApiKey(String apiKey);

        abstract ObfuscateResourcesCommand autoBuild();

        public ObfuscateResourcesCommand build() {
            ObfuscateResourcesCommand command = autoBuild();
            checkFileExistsAndReadable(command.getInputPath());
            // Normalize output path to absolute
            Path normalizedOutputPath = command.getOutputPath().toAbsolutePath().normalize();
            if (!command.getOutputPath().equals(normalizedOutputPath)) {
                setOutputPath(normalizedOutputPath);
                command = autoBuild();
            }

            // Reject input/output identity (including normalized paths)
            if (command.getInputPath().toAbsolutePath().normalize()
                    .equals(command.getOutputPath().toAbsolutePath().normalize())) {
                throw commandExecutionException(
                        "Input and output paths must be different: %s",
                        INPUT_LOCATION_FLAG);
            }
            // Reject symlink/hardlink aliases
            try {
                if (Files.isSameFile(command.getInputPath(), command.getOutputPath())) {
                    throw commandExecutionException(
                            "Input and output paths must be different: %s",
                            INPUT_LOCATION_FLAG);
                }
            } catch (java.nio.file.NoSuchFileException e) {
                // Output file doesn't exist yet, safe to proceed
            } catch (IOException e) {
                throw commandExecutionException(
                        "Failed to inspect output path: %s",
                        OUTPUT_FILE_FLAG);
            }

            String extension = command.getInputPath().toFile().getName().endsWith(".apk") ? ".apk" : ".aab";
            if (!command.getInputPath().toFile().getName().endsWith(extension)) {
                throw commandExecutionException("Wrong properties: %s must end with '.aab' or '.apk'.",
                        INPUT_LOCATION_FLAG);
            }
            if (!command.getOutputPath().toFile().getName().endsWith(extension)) {
                throw commandExecutionException("Wrong properties: %s must match the input format (.aab or .apk).",
                        OUTPUT_FILE_FLAG);
            }
            if (command.getMappingPath().isPresent()) {
                File file = command.getMappingPath().get().toFile();
                checkFileExistsAndReadable(file.toPath());
                if (!file.getName().endsWith(".txt")) {
                    throw commandExecutionException("Wrong properties: %s must end with '.txt'.",
                            MAPPING_FLAG);
                }
            }

            if (command.getStoreFile().isPresent()) {
                checkFlagPresent(command.getKeyAlias(), KEY_ALIAS_FLAG);
                checkFlagPresent(command.getKeyPassword(), KEY_PASSWORD_FLAG);
                checkFlagPresent(command.getStorePassword(), STORE_PASSWORD_FLAG);
            }
            return command;
        }
    }
}
