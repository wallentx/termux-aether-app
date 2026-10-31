package com.termux.shared.shell.command.runner.app;

import java.io.IOException;

/** An app-owned process whose PID and cancellation are supplied by its launcher. */
public abstract class AppShellProcess extends Process {
    public interface Factory {
        Process start(String[] command, String[] environment, String cwd) throws IOException;
    }

    public abstract int getPid();
    public abstract void kill();
}
