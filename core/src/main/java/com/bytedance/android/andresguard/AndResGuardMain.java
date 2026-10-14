package com.bytedance.android.andresguard;


import com.android.tools.build.bundletool.flags.FlagParser;
import com.android.tools.build.bundletool.flags.ParsedFlags;
import com.bytedance.android.andresguard.commands.CommandHelp;
import com.bytedance.android.andresguard.commands.DuplicatedResourcesMergerCommand;
import com.bytedance.android.andresguard.commands.FileFilterCommand;
import com.bytedance.android.andresguard.commands.ObfuscateResourcesCommand;
import com.bytedance.android.andresguard.commands.StringFilterCommand;
import com.bytedance.android.andresguard.model.version.AndResGuardVersion;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

/**
 * Main entry point of the AndResGuard.
 * <p>
 * Created by YangJing on 2019/10/09 .
 * Email: yangjing.yeoh@bytedance.com
 */
public class AndResGuardMain {
    private static final String HELP_CMD = "help";

    public static void main(String[] args) {
        main(args, Runtime.getRuntime());
    }

    /**
     * Parses the flags and routes to the appropriate commands handler.
     */
    private static void main(String[] args, Runtime runtime) {
        final ParsedFlags flags;
        try {
            flags = new FlagParser().parse(args);
        } catch (FlagParser.FlagParseException e) {
            System.err.println("Error while parsing the flags: " + e.getMessage());
            runtime.exit(1);
            return;
        }
        Optional<String> command = flags.getMainCommand();
        if (!command.isPresent()) {
            System.err.println("Error: You have to specify a commands.");
            help();
            runtime.exit(1);
            return;
        }
        try {
            switch (command.get()) {
                case ObfuscateResourcesCommand.COMMAND_NAME:
                    ObfuscateResourcesCommand.fromFlags(flags).execute();
                    break;
                case DuplicatedResourcesMergerCommand.COMMAND_NAME:
                    DuplicatedResourcesMergerCommand.fromFlags(flags).execute();
                    break;
                case FileFilterCommand.COMMAND_NAME:
                    FileFilterCommand.fromFlags(flags).execute();
                    break;
                case StringFilterCommand.COMMAND_NAME:
                    StringFilterCommand.fromFlags(flags).execute();
                    break;
                case HELP_CMD:
                    if (flags.getSubCommand().isPresent()) {
                        help(flags.getSubCommand().get(), runtime);
                    } else {
                        help();
                    }
                    break;
                default:
                    System.err.printf("Error: Unrecognized command '%s'.%n%n%n", command.get());
                    help();
                    runtime.exit(1);
                    return;
            }
        } catch (Exception e) {
            System.err.println(
                    "[BT:" + AndResGuardVersion.getCurrentVersion() + "] Error: " + e.getMessage());
            e.printStackTrace();
            runtime.exit(1);
            return;
        }
        runtime.exit(0);
    }

    /**
     * Displays a general help.
     */
    public static void help() {
        ImmutableList<CommandHelp> commandHelps =
                ImmutableList.of(
                        ObfuscateResourcesCommand.help(),
                        DuplicatedResourcesMergerCommand.help(),
                        FileFilterCommand.help(),
                        StringFilterCommand.help()
                );
        System.out.println("Synopsis: andResGuard <command> ...");
        System.out.println("AAB/APK: obfuscate --input=app.aab|app.apk --output=guarded.aab|guarded.apk --config=config.xml");
        System.out.println();
        System.out.println("Use 'andResGuard help <command>' to learn more about the given command.");
        System.out.println();
        commandHelps.forEach(commandHelp -> commandHelp.printSummary(System.out));
    }

    /**
     * Displays help about a given commands.
     */
    public static void help(String commandName, Runtime runtime) {
        CommandHelp commandHelp;
        switch (commandName) {
            case ObfuscateResourcesCommand.COMMAND_NAME:
                commandHelp = ObfuscateResourcesCommand.help();
                break;
            case DuplicatedResourcesMergerCommand.COMMAND_NAME:
                commandHelp = DuplicatedResourcesMergerCommand.help();
                break;
            case FileFilterCommand.COMMAND_NAME:
                commandHelp = FileFilterCommand.help();
                break;
                case StringFilterCommand.COMMAND_NAME:
                commandHelp = StringFilterCommand.help();
                break;
            default:
                System.err.printf("Error: Unrecognized command '%s'.%n%n%n", commandName);
                help();
                runtime.exit(1);
                return;
        }
        commandHelp.printDetails(System.out);
    }
}
