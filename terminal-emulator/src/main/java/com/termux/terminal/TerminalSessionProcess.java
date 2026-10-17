package com.termux.terminal;

/** A PTY process owned by an external execution service. The terminal owns the returned master FD. */
public interface TerminalSessionProcess {
    int getPid();
    int takeMasterFd();
    int waitFor();
    void stop();
    String getCwd();
    void close();

    interface Factory {
        TerminalSessionProcess start(String executable, String cwd, String[] argv, String[] environment,
                                     int rows, int columns, int cellWidth, int cellHeight) throws Exception;
    }
}
