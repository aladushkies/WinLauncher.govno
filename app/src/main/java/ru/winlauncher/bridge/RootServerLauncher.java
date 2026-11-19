package ru.winlauncher.bridge;

import android.content.Context;

import java.io.File;
import java.security.SecureRandom;

/** Starts the APK's RootServer entry point as uid 0 via su/app_process. */
public final class RootServerLauncher {
    private RootServerLauncher() {}

    public static String getOrCreateToken(Context context) {
        String token = context.getSharedPreferences("root_bridge", Context.MODE_PRIVATE).getString("token", null);
        if (token != null && token.length() == 64) return token;
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder b = new StringBuilder(64);
        for (byte x : bytes) b.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        token = b.toString();
        context.getSharedPreferences("root_bridge", Context.MODE_PRIVATE).edit().putString("token", token).apply();
        return token;
    }

    /** Returns the su process; callers must wait/check it off the UI thread. */
    public static Process start(Context context, String token, int ownerUid) throws Exception {
        String apk = context.getApplicationInfo().sourceDir;
        // S17: app_process inherits almost no environment; without ANDROID_ROOT/ANDROID_DATA the
        // runtime cannot resolve its own data dir and dies before main().
        // BUG-04: the token is no longer passed in argv (readable via /proc/<pid>/cmdline on
        // some firmware). Only the owner uid stays in argv; the token is written to the su
        // process stdin below, and RootServer reads one line from it at startup.
        // R-01: a backgrounded (&) command in POSIX sh gets its stdin forced to /dev/null,
        // so the token cannot flow through the pipe to the nohup'd process directly. Instead
        // the foreground shell first reads the token into $T, stores it in a private temp
        // file (umask 077), and the backgrounded shell binds that file to fd 0 and unlinks
        // it at once; the open descriptor keeps the inode alive for RootServer to read, and
        // no token file remains on disk afterwards.
        // F-05: the printf format uses a double backslash in the Java source, so no raw
        // newline byte ever enters the su -c argument (old SuperSU builds break on it).
        String command = "IFS= read -r T; umask 077; F=/data/local/tmp/wl_tok_$$; "
                + "printf '%s\\n' \"$T\" > \"$F\"; "
                + "(nohup sh -c 'exec < \"$1\"; rm -f \"$1\"; "
                + "exec env ANDROID_DATA=/data ANDROID_ROOT=/system CLASSPATH=\"$2\" "
                + "app_process /system/bin --nice-name=wl_root ru.winlauncher.root.RootServer \"$3\"' "
                + "_ \"$F\" " + quote(apk) + " " + ownerUid + " >/dev/null 2>&1 &); exit 0";
        Process process = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        java.io.OutputStream stdin = process.getOutputStream();
        try {
            stdin.write((token + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
        } finally {
            stdin.close();
        }
        return process;
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
