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
        String shell = "CLASSPATH=" + quote(apk)
                + " app_process /system/bin --nice-name=wl_root ru.winlauncher.root.RootServer "
                + token + " " + ownerUid;
        String command = "(nohup sh -c " + quote(shell)
                + " </dev/null >/dev/null 2>&1 &); exit 0";
        return Runtime.getRuntime().exec(new String[]{"su", "-c", command});
    }

    private static String quote(String s) { return "'" + s.replace("'", "'\\''") + "'"; }
}
