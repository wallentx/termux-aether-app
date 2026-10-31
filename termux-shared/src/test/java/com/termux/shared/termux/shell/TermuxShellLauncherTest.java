package com.termux.shared.termux.shell;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.file.Files;
import java.util.HashMap;

import static org.junit.Assert.*;

public class TermuxShellLauncherTest {
    @Rule public TemporaryFolder files = new TemporaryFolder();

    @Test public void wrapsPrivateLoginShellAndPreservesArguments() throws Exception {
        File data = files.newFolder("data");
        String executable = new File(data, "bash").getPath();
        assertArrayEquals(new String[]{"/system/bin/linker64", executable, "-l", "-c", "printf 'a b'"},
            TermuxShellLauncher.prepare(new String[]{executable, "-c", "printf 'a b'"}, true, true, true, data));
    }

    @Test public void preservesSystemAndLegacyCommands() throws Exception {
        File data = files.newFolder("data");
        String[] system = {"/system/bin/sh", "-c", "true"};
        assertSame(system, TermuxShellLauncher.prepare(system, false, true, true, data));
        String[] legacy = {new File(data, "bash").getPath()};
        assertSame(legacy, TermuxShellLauncher.prepare(legacy, true, false, true, data));
        String[] sibling = {new File(data.getParent(), "data-other/bash").getPath()};
        assertSame(sibling, TermuxShellLauncher.prepare(sibling, false, true, true, data));
    }

    @Test public void followsSymlinksAndSupportsTheRetained32BitProfile() throws Exception {
        File data = files.newFolder("data");
        File program = new File(data, "sh");
        assertTrue(program.createNewFile());
        File alias = new File(files.getRoot(), "alias");
        Files.createSymbolicLink(alias.toPath(), data.toPath());
        String executable = new File(alias, "sh").getPath();
        assertArrayEquals(new String[]{"/system/bin/linker", executable},
            TermuxShellLauncher.prepare(new String[]{executable}, false, true, false, data));
    }

    @Test public void preservesExistingPreloadsWithoutAddingDuplicates() throws Exception {
        HashMap<String, String> environment = new HashMap<>();
        File library = files.newFile("preload.so");
        environment.put("LD_PRELOAD", "/custom.so");
        TermuxShellLauncher.addExecPreload(environment, library);
        assertEquals(library.getPath() + ":/custom.so", environment.get("LD_PRELOAD"));
        TermuxShellLauncher.addExecPreload(environment, library);
        assertEquals(library.getPath() + ":/custom.so", environment.get("LD_PRELOAD"));
        TermuxShellLauncher.addExecPreload(environment, new File(files.getRoot(), "missing.so"));
        assertEquals(library.getPath() + ":/custom.so", environment.get("LD_PRELOAD"));
    }
}
