package ru.winlauncher.root;

import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Root-side Binder bridge. Loaded from the installed APK using app_process.
 * Deliberately uses reflection for hidden API calls, so no device-specific hidden-API stubs
 * are required to compile the APK. Runtime compatibility still must be validated on the tablet.
 */
public final class RootServer {
    private static final int MAX_LINE = 64 * 1024;
    private static volatile Object activityManager;
    private static volatile String socketName;
    private static volatile String authToken;
    private static volatile int ownerUid;

    private RootServer() {}

    public static void main(String[] args) {
        if (args == null || args.length != 2) {
            System.err.println("Usage: RootServer <token> <owner-uid>");
            return;
        }
        authToken = args[0];
        ownerUid = Integer.parseInt(args[1]);
        socketName = "wl_root_" + ownerUid;
        try {
            activityManager = getActivityManager();
            serveForever();
        } catch (Throwable t) {
            System.err.println("WinLauncher RootServer stopped: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static Object getActivityManager() throws Exception {
        Class<?> c = Class.forName("android.app.ActivityManagerNative");
        Method m = c.getDeclaredMethod("getDefault");
        m.setAccessible(true);
        Object result = m.invoke(null);
        if (result == null) throw new IllegalStateException("ActivityManagerNative.getDefault() returned null");
        return result;
    }

    private static void serveForever() throws IOException {
        // LocalServerSocket binds the abstract namespace. No TCP listener is created.
        LocalServerSocket server = new LocalServerSocket(socketName); // String form binds the abstract namespace.
        while (true) {
            LocalSocket client = server.accept();
            Thread session = new Thread(new Session(client), "wl-root-session");
            session.setDaemon(true);
            session.start();
        }
    }

    private static final class Session implements Runnable {
        private final LocalSocket socket;
        private BufferedReader in;
        private BufferedWriter out;

        Session(LocalSocket socket) { this.socket = socket; }

        @Override public void run() {
            try {
                int peerUid = peerUid(socket);
                if (peerUid != ownerUid) return;
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                String hello = readLine(in);
                if (hello == null) return;
                JSONObject h = new JSONObject(hello);
                String supplied = h.optString("token", "");
                if (!constantTimeEquals(authToken, supplied) || h.optInt("uid", -1) != ownerUid) {
                    send(new JSONObject().put("ok", false).put("error", "authentication failed"));
                    return;
                }
                send(new JSONObject().put("ready", true).put("protocol", 1));
                String line;
                while ((line = readLine(in)) != null) {
                    try {
                        JSONObject request = new JSONObject(line);
                        JSONObject response = handle(request);
                        response.put("id", request.optLong("id", -1));
                        send(response);
                    } catch (Throwable t) {
                        JSONObject err = new JSONObject().put("ok", false)
                                .put("error", safeMessage(t));
                        send(err);
                    }
                }
            } catch (Throwable ignored) {
                // A client disconnect is an ordinary session end.
            } finally {
                try { socket.close(); } catch (IOException ignored) {}
            }
        }

        private JSONObject handle(JSONObject j) throws Exception {
            String command = j.getString("c");
            switch (command) {
                case "ping":
                    return new JSONObject().put("ok", true).put("uid", android.os.Process.myUid())
                            .put("api", android.os.Build.VERSION.SDK_INT);
                case "tasks":
                    return new JSONObject().put("ok", true).put("tasks", snapshotTasks());
                case "resize": {
                    int[] r = intArray(j.getJSONArray("r"), 4);
                    Rect bounds = new Rect(r[0], r[1], r[2], r[3]);
                    invoke(activityManager, "resizeTask", j.getInt("t"), bounds, j.optInt("m", 0));
                    return new JSONObject().put("ok", true);
                }
                case "front": {
                    int id = j.getInt("t");
                    invoke(activityManager, "moveTaskToFront", id, 0, (Bundle) null);
                    boolean focused = true;
                    try { invoke(activityManager, "setFocusedTask", id); }
                    catch (Exception unsupported) { focused = false; }
                    return new JSONObject().put("ok", true).put("focused", focused);
                }
                case "stack":
                    invoke(activityManager, "moveTaskToStack", j.getInt("t"), j.getInt("s"), j.optBoolean("top", true));
                    return new JSONObject().put("ok", true);
                case "close":
                    invoke(activityManager, "removeTask", j.getInt("t"));
                    return new JSONObject().put("ok", true);
                default:
                    throw new IllegalArgumentException("unsupported command: " + command);
            }
        }

        private void send(JSONObject j) throws IOException {
            synchronized (this) {
                out.write(j.toString());
                out.write('\n');
                out.flush();
            }
        }
    }

    private static JSONArray snapshotTasks() throws Exception {
        Object value = invoke(activityManager, "getAllStackInfos");
        if (!(value instanceof List)) throw new IllegalStateException("getAllStackInfos did not return a List");
        JSONArray result = new JSONArray();
        int z = 0;
        for (Object stack : (List<?>) value) {
            int stackId = intField(stack, "stackId", -1);
            int[] taskIds = intArrayField(stack, "taskIds");
            String[] names = stringArrayField(stack, "taskNames");
            Rect[] bounds = rectArrayField(stack, "taskBounds");
            boolean visible = booleanField(stack, "visible", false);
            for (int i = 0; i < taskIds.length; i++) {
                Rect b = i < bounds.length && bounds[i] != null ? bounds[i] : new Rect();
                JSONArray rect = new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom);
                result.put(new JSONObject()
                        .put("id", taskIds[i])
                        .put("stack", stackId)
                        .put("component", i < names.length && names[i] != null ? names[i] : "")
                        .put("bounds", rect)
                        .put("visible", visible)
                        .put("z", z++));
            }
        }
        return result;
    }

    private static Object invoke(Object target, String name, Object... args) throws Exception {
        Method chosen = null;
        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterTypes().length != args.length) continue;
            Class<?>[] types = m.getParameterTypes();
            boolean match = true;
            for (int i = 0; i < types.length; i++) {
                if (!compatible(types[i], args[i])) { match = false; break; }
            }
            if (match) { chosen = m; break; }
        }
        if (chosen == null) throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);
        try {
            chosen.setAccessible(true);
            return chosen.invoke(target, args);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }
    }

    private static boolean compatible(Class<?> expected, Object value) {
        if (value == null) return !expected.isPrimitive();
        if (!expected.isPrimitive()) return expected.isAssignableFrom(value.getClass());
        return (expected == int.class && value instanceof Integer)
                || (expected == boolean.class && value instanceof Boolean)
                || (expected == long.class && value instanceof Long)
                || (expected == float.class && value instanceof Float)
                || (expected == double.class && value instanceof Double);
    }

    private static int peerUid(LocalSocket socket) throws Exception {
        Method m = LocalSocket.class.getDeclaredMethod("getPeerCredentials");
        m.setAccessible(true);
        Object credentials = m.invoke(socket);
        Method uid = credentials.getClass().getDeclaredMethod("getUid");
        uid.setAccessible(true);
        return ((Number) uid.invoke(credentials)).intValue();
    }

    private static String readLine(BufferedReader reader) throws IOException {
        StringBuilder b = new StringBuilder();
        for (;;) {
            int c = reader.read();
            if (c == -1) return b.length() == 0 ? null : b.toString();
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char) c);
            if (b.length() > MAX_LINE) throw new IOException("protocol line too long");
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String safeMessage(Throwable t) {
        Throwable x = t;
        while (x instanceof InvocationTargetException && ((InvocationTargetException) x).getCause() != null)
            x = ((InvocationTargetException) x).getCause();
        String s = x.getClass().getSimpleName() + (x.getMessage() == null ? "" : ": " + x.getMessage());
        return s.length() > 400 ? s.substring(0, 400) : s;
    }

    private static int[] intArray(JSONArray a, int expected) throws Exception {
        if (a.length() != expected) throw new IllegalArgumentException("expected " + expected + " integers");
        int[] out = new int[expected];
        for (int i = 0; i < expected; i++) out[i] = a.getInt(i);
        return out;
    }
    private static int intField(Object o, String name, int fallback) {
        Object v = field(o, name); return v instanceof Number ? ((Number) v).intValue() : fallback;
    }
    private static boolean booleanField(Object o, String name, boolean fallback) {
        Object v = field(o, name); return v instanceof Boolean ? (Boolean) v : fallback;
    }
    private static int[] intArrayField(Object o, String name) {
        Object v = field(o, name); return v instanceof int[] ? (int[]) v : new int[0];
    }
    private static String[] stringArrayField(Object o, String name) {
        Object v = field(o, name); return v instanceof String[] ? (String[]) v : new String[0];
    }
    private static Rect[] rectArrayField(Object o, String name) {
        Object v = field(o, name);
        if (v instanceof Rect[]) return (Rect[]) v;
        if (v != null && v.getClass().isArray()) {
            int n = Array.getLength(v); Rect[] result = new Rect[n];
            for (int i = 0; i < n; i++) { Object x = Array.get(v, i); if (x instanceof Rect) result[i] = (Rect) x; }
            return result;
        }
        return new Rect[0];
    }
    private static Object field(Object o, String name) {
        try { Field f = o.getClass().getField(name); f.setAccessible(true); return f.get(o); }
        catch (Throwable ignored) {
            try { Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (Throwable ignoredAgain) { return null; }
        }
    }
}
