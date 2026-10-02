package com.termux.app.session;
import com.termux.app.session.SessionHandle;
import com.termux.app.session.ISessionCallback;
import com.termux.app.session.BackgroundHandle;
interface ISessionService {
    SessionHandle startSession(String executable, String cwd, in String[] argv, in String[] environment,
        int rows, int columns, int cellWidth, int cellHeight, ISessionCallback callback) = 0;
    oneway void stopSession(String token) = 1;
    String getCwd(String token) = 2;
    BackgroundHandle startBackground(String executable, String cwd, in String[] argv,
        in String[] environment, ISessionCallback callback) = 3;
    void destroy() = 16777114;
}
