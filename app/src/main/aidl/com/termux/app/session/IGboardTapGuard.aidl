package com.termux.app.session;
/** A tap is allowed only while its originating input request remains active. */
interface IGboardTapGuard {
    boolean isAllowed();
}
