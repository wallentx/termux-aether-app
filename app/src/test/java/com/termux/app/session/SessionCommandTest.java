package com.termux.app.session;

import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class SessionCommandTest {
    @Test public void restoresTempDirectoryAfterSecureEnvironmentFiltering() throws Exception {
        String shell = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/bash";
        File directory = Files.createTempDirectory("aether ' tmp").toFile();
        try {
            String script = SessionCommand.script(shell, directory.getPath(),
                new String[]{"temp-probe", "-c", "printf '%s' \"$TMPDIR\""},
                new String[]{"TMPDIR=" + directory.getPath()});
            ProcessBuilder builder = new ProcessBuilder(shell, "-c", script).redirectErrorStream(true);
            // Android strips TMPDIR while entering the privileged run-as executable.
            builder.environment().remove("TMPDIR");
            Process process = builder.start();
            java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
            assertEquals(0, process.waitFor());
            assertEquals(directory.getPath(), new String(output.toByteArray(), StandardCharsets.UTF_8));
        } finally {
            assertTrue(directory.delete());
        }
    }

    @Test public void transportsLiteralArgumentsEnvironmentAndQuotedWorkingDirectory() throws Exception {
        String shell = new File("/system/bin/sh").exists() ? "/system/bin/sh" : "/bin/bash";
        File cwd = Files.createTempDirectory("aether ' session").toFile();
        File marker = new File(cwd, "must-not-exist");
        String literal = "space ' quote\n\u00e6 \ud83d\ude80 $(touch " + marker + ") `false`";
        String script = SessionCommand.script(shell, cwd.getPath(),
            new String[]{"aether-argv-zero", "-c", "printf '%s\\n%s\\n%s' \"$0\" \"$PAYLOAD\" \"$PWD\""},
            new String[]{"PAYLOAD=" + literal, "PATH=/system/bin:/usr/bin:/bin"});
        ProcessBuilder builder = new ProcessBuilder(shell, "-c", script).redirectErrorStream(true);
        builder.environment().put("PAYLOAD", literal);
        Process process = builder.start();
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = process.getInputStream().read(buffer)) != -1) output.write(buffer, 0, count);
        assertEquals(0, process.waitFor());
        assertEquals("aether-argv-zero\n" + literal + "\n" + cwd.getPath(),
            new String(output.toByteArray(), StandardCharsets.UTF_8));
        assertFalse(marker.exists());
        assertTrue(cwd.delete());
    }

    @Test public void doesNotPutUnmodifiedSecretEnvironmentInProcessArguments() {
        String script = SessionCommand.script("/bin/sh", "/", new String[]{"sh"},
            new String[]{"API_TOKEN=private-token", "HOME=/home/owner", "PATH=/bin"});
        assertFalse(script.contains("private-token"));
        assertFalse(script.contains("API_TOKEN"));
        assertTrue(script.contains("export HOME="));
    }

    @Test public void bootstrapKeepsAndroidRuntimeButRemovesPrivateLoaderState() {
        assertArrayEquals(new String[]{"BOOTCLASSPATH=framework.jar", "ANDROID_ROOT=/system", "PATH=/prefix/bin"},
            SessionCommand.bootstrapEnvironment(new String[]{"LD_PRELOAD=/private/lib.so", "LD_LIBRARY_PATH=/private",
                "TERMUX_EXEC__PROC_SELF_EXE=/private/go", "BOOTCLASSPATH=framework.jar", "ANDROID_ROOT=/system", "PATH=/prefix/bin"}));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNulArguments() {
        SessionCommand.script("/bin/sh", "/", new String[]{"sh", "bad\0argument"}, new String[0]);
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsEnvironmentKeyInjection() {
        SessionCommand.script("/bin/sh", "/", new String[]{"sh"}, new String[]{"X; touch bad=value"});
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNulInUnmodifiedEnvironment() {
        SessionCommand.script("/bin/sh", "/", new String[]{"sh"}, new String[]{"PAYLOAD=bad\0value"});
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsOversizedCommandBeforeStartingAnything() {
        char[] large = new char[121000];
        java.util.Arrays.fill(large, 'x');
        SessionCommand.script("/bin/sh", "/", new String[]{"sh", new String(large)}, new String[0]);
    }
}
