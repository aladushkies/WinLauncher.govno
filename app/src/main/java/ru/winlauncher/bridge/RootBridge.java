package ru.winlauncher.bridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent local-socket client. One worker serializes calls; queued resize requests coalesce. */
public final class RootBridge implements Closeable {
    public interface Callback { void onResult(JSONObject result); }

    private static final int MAX_LINE = 64 * 1024;
    private final String socketName;
    private final String token;
    private final int uid;
    private final HandlerThread thread;
    private final Handler worker;
    private final Handler main = new Handler(android.os.Looper.getMainLooper());
    private final ArrayDeque<Request> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final AtomicLong ids = new AtomicLong(1);
    private volatile boolean closed;
    private volatile LocalSocket socket;
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;

    public RootBridge(String token) {
        this.token = token;
        this.uid = Process.myUid();
        this.socketName = "wl_root_" + uid;
        thread = new HandlerThread("WinLauncher-RootBridge");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public void ping(Callback callback) { send("ping", new JSONObject(), null, callback); }
    public void tasks(Callback callback) { send("tasks", new JSONObject(), null, callback); }
    public void resize(int taskId, int left, int top, int right, int bottom, int mode, Callback callback) {
        JSONObject p = new JSONObject();
        try { p.put("t", taskId).put("r", new JSONArray().put(left).put(top).put(right).put(bottom)).put("m", mode); }
        catch (JSONException impossible) { }
        send("resize", p, "resize:" + taskId, callback);
    }
    public void front(int taskId, Callback callback) {
        JSONObject p = new JSONObject(); try { p.put("t", taskId); } catch (JSONException ignored) { }
        send("front", p, null, callback);
    }
    public void moveToStack(int taskId, int stackId, boolean toTop, Callback callback) {
        JSONObject p = new JSONObject();
        try { p.put("t", taskId).put("s", stackId).put("top", toTop); } catch (JSONException ignored) { }
        send("stack", p, null, callback);
    }
    public void closeTask(int taskId, Callback callback) {
        JSONObject p = new JSONObject(); try { p.put("t", taskId); } catch (JSONException ignored) { }
        send("close", p, null, callback);
    }

    private void send(String command, JSONObject payload, String coalesceKey, Callback callback) {
        if (closed) { deliver(callback, error("bridge closed")); return; }
        Request request = new Request(ids.getAndIncrement(), command, payload, coalesceKey, callback);
        synchronized (lock) {
            if (coalesceKey != null) {
                Iterator<Request> it = queue.iterator();
                while (it.hasNext()) {
                    Request old = it.next();
                    if (coalesceKey.equals(old.coalesceKey)) { it.remove(); break; }
                }
            }
            queue.addLast(request);
        }
        worker.removeCallbacks(drain);
        worker.post(drain);
    }

    private final Runnable drain = new Runnable() {
        @Override public void run() {
            while (!closed) {
                Request r;
                synchronized (lock) { r = queue.pollFirst(); }
                if (r == null) return;
                JSONObject result;
                try {
                    ensureConnected();
                    JSONObject wire = new JSONObject().put("id", r.id).put("c", r.command);
                    for (Iterator<String> it = r.payload.keys(); it.hasNext();) {
                        String k = it.next(); wire.put(k, r.payload.get(k));
                    }
                    writer.write(wire.toString()); writer.write('\n'); writer.flush();
                    String line = readLine(reader);
                    if (line == null) throw new IOException("RootServer disconnected");
                    result = new JSONObject(line);
                    if (!result.optBoolean("ok", false)) throw new IOException(result.optString("error", "request failed"));
                } catch (Throwable t) {
                    disconnect(); result = error(t.getClass().getSimpleName() + ": " + t.getMessage());
                }
                deliver(r.callback, result);
            }
        }
    };

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected()) return;
        LocalSocket s = new LocalSocket();
        s.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
        BufferedWriter out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
        out.write(new JSONObject().put("token", token).put("uid", uid).toString()); out.write('\n'); out.flush();
        String hello = readLine(in);
        if (hello == null) { s.close(); throw new IOException("empty handshake"); }
        JSONObject response = new JSONObject(hello);
        if (!response.optBoolean("ready", false)) { s.close(); throw new IOException(response.optString("error", "auth failed")); }
        socket = s; reader = in; writer = out;
    }

    private static String readLine(BufferedReader r) throws IOException {
        StringBuilder b = new StringBuilder();
        for (;;) {
            int c = r.read();
            if (c == -1) return b.length() == 0 ? null : b.toString();
            if (c == '\n') return b.toString();
            if (c != '\r') b.append((char)c);
            if (b.length() > MAX_LINE) throw new IOException("protocol line too long");
        }
    }

    private void deliver(Callback callback, JSONObject value) {
        if (callback != null) main.post(() -> callback.onResult(value));
    }
    private static JSONObject error(String s) {
        try { return new JSONObject().put("ok", false).put("error", s == null ? "unknown error" : s); }
        catch (JSONException e) { return new JSONObject(); }
    }
    private void disconnect() {
        try { if (socket != null) socket.close(); } catch (IOException ignored) { }
        socket = null; reader = null; writer = null;
    }
    @Override public void close() {
        closed = true;
        synchronized (lock) { queue.clear(); }
        disconnect();
        thread.quitSafely();
    }
    private static final class Request {
        final long id; final String command; final JSONObject payload; final String coalesceKey; final Callback callback;
        Request(long id, String c, JSONObject p, String key, Callback cb) {
            this.id = id; command = c; payload = p; coalesceKey = key; callback = cb;
        }
    }
}
