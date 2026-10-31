package com.termux.app.session;
interface ISessionCallback {
    oneway void onExit(int status);
}
