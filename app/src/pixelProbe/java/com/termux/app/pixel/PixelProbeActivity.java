package com.termux.app.pixel;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.util.AtomicFile;
import android.widget.ScrollView;
import android.widget.TextView;

import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;
import com.termux.terminal.PixelProbeProcess;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Fixed, read-mostly diagnostics. Included only with -PpixelProbe=true. */
public final class PixelProbeActivity extends Activity {
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setTextIsSelectable(true);
        text.setPadding(24, 64, 24, 48);
        text.setText("Checking CPU features and native execution. Each command has a 10-second timeout.");
        ScrollView scroll = new ScrollView(this);
        scroll.addView(text);
        setContentView(scroll);
        // Serializes repeated launches/recreation so fixture and report writes cannot overlap.
        WORKER.execute(() -> {
            String display;
            try {
                JSONObject report = collectReport();
                File directory = new File(getFilesDir(), "pixel-probe");
                Files.createDirectories(directory.toPath());
                AtomicFile reportFile = new AtomicFile(new File(directory, "report.json"));
                FileOutputStream output = reportFile.startWrite();
                try {
                    output.write((report.toString(2) + "\n").getBytes(StandardCharsets.UTF_8));
                    reportFile.finishWrite(output);
                } catch (Exception failure) {
                    reportFile.failWrite(output);
                    throw failure;
                }
                display = report.toString(2);
            } catch (Exception | LinkageError failure) {
                display = "Probe failed: " + failure;
            }
            String result = display;
            runOnUiThread(() -> {
                if (!isDestroyed()) text.setText(result);
            });
        });
    }

    private JSONObject collectReport() throws Exception {
        File directory = new File(getFilesDir(), "pixel-probe");
        Files.createDirectories(directory.toPath());
        // A failed new run must not leave an older success looking like fresh evidence.
        new AtomicFile(new File(directory, "report.json")).delete();
        JSONObject report = new JSONObject();
        report.put("schema_version", 1);
        report.put("collected_at_ms", System.currentTimeMillis());
        report.put("package", getPackageName());
        report.put("uid", Process.myUid());
        report.put("model", Build.MODEL);
        report.put("fingerprint", Build.FINGERPRINT);
        report.put("sdk", Build.VERSION.SDK_INT);
        report.put("sdk_full", Build.VERSION.SDK_INT_FULL);
        report.put("target_sdk", getApplicationInfo().targetSdkVersion);
        report.put("is_64_bit", Process.is64Bit());
        report.put("cpu", new JSONObject(PixelProbeProcess.capabilities()));
        report.put("scope", "Capability and launch smoke tests; not a workload or SIMD benchmark");
        JSONArray execution = new JSONArray();
        report.put("execution", execution);

        // The same dynamic system ELF in an executable system path and a writable app path.
        File fixture = new File(directory, "toybox");
        Files.copy(new File("/system/bin/toybox").toPath(), fixture.toPath(),
            StandardCopyOption.REPLACE_EXISTING);
        if (!fixture.setExecutable(true, true)) throw new IllegalStateException("Cannot chmod fixture");
        String cwd = directory.getAbsolutePath();
        String[] systemEnv = {"PATH=/system/bin", "HOME=" + cwd, "TMPDIR=" + cwd, "LANG=C.UTF-8"};
        try {
            run(execution, "system_control", "/system/bin/toybox", cwd,
                new String[]{"toybox", "true"}, systemEnv);
            run(execution, "private_direct", fixture.getAbsolutePath(), cwd,
                new String[]{"toybox", "true"}, systemEnv);
            run(execution, "private_linker", "/system/bin/linker64", cwd,
                new String[]{"linker64", fixture.getAbsolutePath(), "true"}, systemEnv);
        } finally {
            Files.deleteIfExists(fixture.toPath());
        }

        File shell = new File(getFilesDir(), "usr/bin/sh");
        File preload = new File(getFilesDir(), "usr/lib/libtermux-exec-ld-preload.so");
        if (!preload.isFile()) preload = new File(getFilesDir(), "usr/lib/libtermux-exec.so");
        report.put("bootstrap_shell_present", shell.isFile());
        report.put("termux_exec_present", preload.isFile());
        report.put("termux_exec_path", preload.isFile() ? preload.getAbsolutePath() : JSONObject.NULL);
        if (!shell.isFile()) {
            report.put("bootstrap_status", "not_tested_missing_shell");
            return report;
        }
        HashMap<String, String> environment = new TermuxShellEnvironment().getEnvironment(this, false);
        String path = shell.getAbsolutePath();
        String builtin = "printf 'pixel-shell-ok\\n'";
        run(execution, "bootstrap_direct", path, cwd,
            new String[]{"sh", "-c", builtin}, toArray(environment));
        run(execution, "bootstrap_linker", "/system/bin/linker64", cwd,
            new String[]{"linker64", path, "-c", builtin}, toArray(environment));
        if (preload.isFile()) {
            environment.put("LD_PRELOAD", preload.getAbsolutePath());
            // Let the installed termux-exec choose its normal target-SDK-aware mode.
            String child = "\"$PREFIX/bin/sh\" -c \"printf 'pixel-child-ok\\\\n'\"";
            run(execution, "bootstrap_preload_child", "/system/bin/linker64", cwd,
                new String[]{"linker64", path, "-c", child}, toArray(environment));
        }
        report.put("bootstrap_status", "smoke_tests_only");
        return report;
    }

    private static String[] toArray(Map<String, String> environment) {
        return environment.entrySet().stream()
            .map(entry -> entry.getKey() + "=" + entry.getValue()).toArray(String[]::new);
    }

    private static void run(JSONArray results, String name, String command, String cwd,
                            String[] args, String[] environment) throws Exception {
        JSONObject result;
        try {
            result = PixelProbeProcess.run(command, cwd, args, environment);
        } catch (RuntimeException failure) {
            result = new JSONObject().put("error", failure.toString());
        }
        result.put("name", name);
        results.put(result);
    }
}
