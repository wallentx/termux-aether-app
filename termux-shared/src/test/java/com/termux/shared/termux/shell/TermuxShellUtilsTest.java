package com.termux.shared.termux.shell;

import com.termux.shared.termux.TermuxConstants;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertArrayEquals;

public class TermuxShellUtilsTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();
    private final String bin = TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH;

    private String script(String contents) throws Exception {
        File file = files.newFile();
        Files.write(file.toPath(), contents.getBytes(StandardCharsets.UTF_8));
        return file.getPath();
    }

    @Test public void resolvesPrivateShebangBeforeLinkerLaunch() throws Exception {
        String path = script("#!" + bin + "/sh\nprintf ok\n");
        assertArrayEquals(new String[]{bin + "/sh", path, "a b"},
            TermuxShellUtils.setupShellCommandArguments(path, new String[]{"a b"}));
    }

    @Test public void preservesTheSingleOptionalShebangArgument() throws Exception {
        String path = script("#!\t/usr/bin/env\t-S python -u\nprint('ok')\n");
        assertArrayEquals(new String[]{bin + "/env", "-S python -u", path},
            TermuxShellUtils.setupShellCommandArguments(path, null));
    }

    @Test public void handlesScriptsWithoutShebangAndSystemInterpreters() throws Exception {
        String plain = script("echo ok\n");
        assertArrayEquals(new String[]{bin + "/sh", plain}, TermuxShellUtils.setupShellCommandArguments(plain, null));
        String system = script("#!/system/bin/sh\necho ok\n");
        assertArrayEquals(new String[]{"/system/bin/sh", system}, TermuxShellUtils.setupShellCommandArguments(system, null));
    }

    @Test public void leavesElfExecutablesAndMissingFilesForTheLauncher() throws Exception {
        File elf = files.newFile();
        Files.write(elf.toPath(), new byte[]{0x7f, 'E', 'L', 'F'});
        assertArrayEquals(new String[]{elf.getPath(), "--version"},
            TermuxShellUtils.setupShellCommandArguments(elf.getPath(), new String[]{"--version"}));
        String missing = new File(files.getRoot(), "missing").getPath();
        assertArrayEquals(new String[]{missing}, TermuxShellUtils.setupShellCommandArguments(missing, null));
    }
}
