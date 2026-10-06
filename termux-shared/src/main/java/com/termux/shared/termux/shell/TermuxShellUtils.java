package com.termux.shared.termux.shell;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.shared.errors.Error;
import com.termux.shared.file.filesystem.FileTypes;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.file.FileUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import org.apache.commons.io.filefilter.TrueFileFilter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class TermuxShellUtils {

    private static final String LOG_TAG = "TermuxShellUtils";

    /**
     * Setup shell command arguments for the execute. The file interpreter may be prefixed to
     * command arguments if needed.
     */
    @NonNull
    public static String[] setupShellCommandArguments(@NonNull String executable, @Nullable String[] arguments) {
        String interpreter = null;
        String interpreterArgument = null;
        try (FileInputStream in = new FileInputStream(executable)) {
            byte[] buffer = new byte[256];
            int bytesRead = in.read(buffer);
            boolean elf = bytesRead >= 4 && buffer[0] == 0x7F && buffer[1] == 'E'
                && buffer[2] == 'L' && buffer[3] == 'F';
            if (!elf) {
                interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/sh";
                if (bytesRead >= 2 && buffer[0] == '#' && buffer[1] == '!') {
                    String line = new String(buffer, 2, bytesRead - 2, StandardCharsets.UTF_8).split("\\n", 2)[0].trim();
                    // Like the kernel, pass the optional shebang argument as one argument. This
                    // also supports /usr/bin/env -S; env itself splits the remaining string.
                    String[] shebang = line.split("[ \\t]+", 2);
                    if (!shebang[0].isEmpty()) {
                        interpreter = shebang[0];
                        if (interpreter.startsWith("/usr/") || interpreter.startsWith("/bin/"))
                            interpreter = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/" + new File(interpreter).getName();
                        if (shebang.length > 1) interpreterArgument = shebang[1];
                    }
                }
            }
        } catch (IOException e) {
            // Keep the original executable so the launcher reports the actual open/exec error.
        }

        List<String> result = new ArrayList<>();
        if (interpreter != null) result.add(interpreter);
        if (interpreterArgument != null) result.add(interpreterArgument);
        result.add(executable);
        if (arguments != null) Collections.addAll(result, arguments);
        return result.toArray(new String[0]);
    }

    /** Clear files under {@link TermuxConstants#TERMUX_TMP_PREFIX_DIR_PATH}. */
    public static void clearTermuxTMPDIR(boolean onlyIfExists) {
        // Existence check before clearing may be required since clearDirectory() will automatically
        // re-create empty directory if doesn't exist, which should not be done for things like
        // termux-reset (d6eb5e35). Moreover, TMPDIR must be a directory and not a symlink, this can
        // also allow users who don't want TMPDIR to be cleared automatically on termux exit, since
        // it may remove files still being used by background processes (#1159).
        if(onlyIfExists && !FileUtils.directoryFileExists(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, false))
            return;

        Error error;

        TermuxAppSharedProperties properties = TermuxAppSharedProperties.getProperties();
        int days = properties.getDeleteTMPDIRFilesOlderThanXDaysOnExit();

        // Disable currently until FileUtils.deleteFilesOlderThanXDays() is fixed.
        if (days > 0)
            days = 0;

        if (days < 0) {
            Logger.logInfo(LOG_TAG, "Not clearing termux $TMPDIR");
        } else if (days == 0) {
            error = FileUtils.clearDirectory("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null));
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to clear termux $TMPDIR\n" + error);
            }
        } else {
            error = FileUtils.deleteFilesOlderThanXDays("$TMPDIR",
                FileUtils.getCanonicalPath(TermuxConstants.TERMUX_TMP_PREFIX_DIR_PATH, null),
                TrueFileFilter.INSTANCE, days, true, FileTypes.FILE_TYPE_ANY_FLAGS);
            if (error != null) {
                Logger.logErrorExtended(LOG_TAG, "Failed to delete files from termux $TMPDIR older than " + days + " days\n" + error);
            }
        }
    }

}
