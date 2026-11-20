package ru.winlauncher.bridge;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent local-socket client. One worker serializes calls; queued resize requests coalesce,
 * pending snapshot reads are merged into one request, and the pending queue is bounded.
 */
public final class RootBridge implements Closeable {
    public interface Callback { void onResult(JSONObject result); }

    private static final int MAX_LINE = 512 * 1024;
    private static final int IO_TIMEOUT_MS = 4000; // S2: a hung RootServer must not stall the worker forever
    // L-03: RootServer closes a session that stayed idle for 120 s (RootServer: setSoTimeout(120000)).
    // A LocalSocket can still report itself connected after the peer closed the session, so the
    // client drops such a socket on its own instead of writing a command into a dead session.
    private static final long SESSION_IDLE_TIMEOUT_MS = 120000L;
    // L-01: the pending queue is bounded. The 750 ms task poll used to enqueue one snapshot
    // request per tick; against an unresponsive RootServer the queue grew without bound.
    private static final int MAX_PENDING_REQUESTS = 64;
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
    private volatile long lastIoAt;

    public RootBridge(String token) {
        this.token = token;
        this.uid = Process.myUid();
        this.socketName = "wl_root_" + uid;
        thread = new HandlerThread("WinLauncher-RootBridge");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public void ping(Callback callback) { send("ping", new JSONObject(), null, callback); }
    // L-01: "tasks" is a snapshot read, so several pending snapshots can be merged into a
    // single request instead of piling up in the queue one per 750 ms poll tick.
    public void tasks(Callback callback) { send("tasks", new JSONObject(), "tasks", callback, true); }
    public void setStatusBarHidden(boolean hidden, Callback callback) {
        JSONObject p = new JSONObject(); try { p.put("hide", hidden); } catch (JSONException ignored) { }
        send("status", p, null, callback);
    }
    public void enableAccessibility(Callback callback) { send("accessibility",new JSONObject(),null,callback); }
    public void captureScreenshot(Callback callback) { send("capture",new JSONObject(),null,callback); }
    public void deviceAction(String action,boolean enabled,Callback callback) {
        JSONObject p=new JSONObject();try{p.put("a",action).put("v",enabled);}catch(JSONException ignored){}
        send("device",p,null,callback);
    }
    public void deviceState(String action,Callback callback) {
        JSONObject p=new JSONObject();try{p.put("a",action);}catch(JSONException ignored){}
        send("device_state",p,null,callback);
    }
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
        send(command, payload, coalesceKey, callback, false);
    }

    /**
     * L-01: the pending queue is bounded and snapshot reads are coalesced.
     *
     * <p>A request with a coalesce key replaces the queued request with the same key:
     * <ul>
     *   <li>a mergeable request (a snapshot read) hands its callbacks to the new request,
     *       so every caller still receives an answer but only one request is sent;</li>
     *   <li>a side-effecting request (a resize) supersedes the old one, which is answered
     *       with the "superseded by newer resize" error it always got.</li>
     * </ul>
     * When the queue is full the oldest mergeable request is dropped, and the caller is
     * told that its snapshot was dropped instead of the queue growing without bound.
     */
    private void send(String command, JSONObject payload, String coalesceKey, Callback callback, boolean mergeable) {
        if (closed) { deliver(callback, error("bridge closed")); return; }
        Request request = new Request(ids.getAndIncrement(), command, payload, coalesceKey, callback);
        request.mergeable = mergeable;
        Request superseded = null;
        Request dropped = null;
        boolean rejected = false;
        synchronized (lock) {
            if (coalesceKey != null) {
                Iterator<Request> it = queue.iterator();
                while (it.hasNext()) {
                    Request old = it.next();
                    if (coalesceKey.equals(old.coalesceKey)) { superseded = old; it.remove(); break; }
                }
            }
            if (superseded != null && superseded.mergeable) {
                // The old snapshot has not been sent yet: the new one answers for both.
                request.callbacks.addAll(superseded.callbacks);
                superseded = null;
            }
            if (queue.size() >= MAX_PENDING_REQUESTS) {
                dropped = removeOldestMergeable();
                if (dropped == null) rejected = true;
            }
            if (!rejected) queue.addLast(request);
        }
        if (superseded != null) deliverAll(superseded, error("superseded by newer resize"));
        if (dropped != null) deliverAll(dropped, error("dropped: request queue full"));
        // A rejected request still answers every caller that joined it.
        if (rejected) deliverAll(request, error("request queue full"));
        worker.removeCallbacks(drain);
        worker.post(drain);
    }

    /** Removes the oldest queued snapshot request so that the queue stays bounded. */
    private Request removeOldestMergeable() {
        for (Iterator<Request> it = queue.iterator(); it.hasNext();) {
            Request candidate = it.next();
            if (candidate.mergeable) { it.remove(); return candidate; }
        }
        return null;
    }

    private final Runnable drain = new Runnable() {
        @Override public void run() {
            while (!closed) {
                Request r;
                synchronized (lock) { r = queue.pollFirst(); }
                if (r == null) return;
                deliverAll(r, execute(r));
            }
        }
    };

    /** D-05: matches the response id to the request id (skipping late answers to timed-out
     *  requests), always drops the socket on any failure so a stale response can never be read
     *  as the next command's answer, and retries an idempotent read command exactly once after
     *  reconnecting — the server closes sessions idle for 120 s, so the first command after a
     *  pause used to fail with "RootServer disconnected". Commands with side effects
     *  (resize/stack/close/device/status) are never retried automatically. */
    private JSONObject execute(Request r) {
        boolean idempotent = "ping".equals(r.command) || "tasks".equals(r.command)
                || "device_state".equals(r.command);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                ensureConnected();
                JSONObject wire = new JSONObject().put("id", r.id).put("c", r.command);
                for (Iterator<String> it = r.payload.keys(); it.hasNext();) {
                    String k = it.next(); wire.put(k, r.payload.get(k));
                }
                writer.write(wire.toString()); writer.write('\n'); writer.flush();
                lastIoAt = SystemClock.elapsedRealtime();
                JSONObject parsed = null;
                for (int skipped = 0; skipped < 4; skipped++) {
                    String line = readLine(reader);
                    if (line == null) throw new IOException("RootServer disconnected");
                    parsed = new JSONObject(line);
                    // BUG-14: a protocol-level error frame (id -1, ok false) belongs to no
                    // request; return it at once instead of waiting out the 4 s timeout.
                    if (parsed.optLong("id", -1) == -1 && !parsed.optBoolean("ok", true)) return parsed;
                    if (parsed.optLong("id", -1) == r.id) break;
                    parsed = null; // late answer to an earlier request: skip it
                }
                if (parsed == null) throw new IOException("response id mismatch");
                return parsed;
            } catch (Throwable t) {
                disconnect();
                if (attempt == 0 && idempotent) continue;
                return error(t instanceof java.net.SocketTimeoutException
                        ? "timeout"
                        : t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }
        return error("unreachable");
    }

    private void ensureConnected() throws Exception {
        if (socket != null && socket.isConnected()) {
            // L-03: RootServer closes a session that stayed idle for 120 s, but a LocalSocket
            // can still report itself connected after the peer closed the session. A command
            // written into that socket is lost, and a side-effecting command is never retried.
            // Drop the stale session and reconnect instead.
            if (SystemClock.elapsedRealtime() - lastIoAt <= SESSION_IDLE_TIMEOUT_MS) return;
            disconnect();
        }
        LocalSocket s = new LocalSocket();
        BufferedReader in;
        BufferedWriter out;
        // BUG-14: the socket field is only assigned after a successful handshake, so a failure
        // mid-handshake used to leak the local socket. Close it on any failure path.
        try {
            s.setSoTimeout(IO_TIMEOUT_MS);
            s.connect(new LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT));
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new BufferedWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));
            String nonce = newNonce();
            JSONObject helloMessage = new JSONObject().put("uid", uid).put("nonce", nonce)
                    .put("token", clientProof(token, nonce));
            out.write(helloMessage.toString()); out.write('\n'); out.flush();
            String hello = readLine(in);
            if (hello == null) throw new IOException("empty handshake");
            JSONObject response = new JSONObject(hello);
            // S15: mutual authentication. An impostor bound to the same socket name cannot produce the
            // token-bound proof, so a hostile app_process cannot impersonate root and drive the model.
            if (!response.optBoolean("ready", false)
                    || !constantTimeEquals(response.optString("proof", ""), serverProof(token, nonce))) {
                throw new IOException(response.optString("error", "handshake rejected"));
            }
        } catch (Throwable t) {
            try { s.close(); } catch (IOException ignored) { }
            if (t instanceof Exception) throw (Exception) t;
            throw new IOException(String.valueOf(t));
        }
        socket = s; reader = in; writer = out;
        lastIoAt = SystemClock.elapsedRealtime();
    }

    // BUG-04: S15 originally used one shared H(token:nonce) in both directions, so any process
    // that squatted on the socket name could reflect the client's proof back as the server
    // proof and pass the client check without knowing the token. The two directions now hash
    // different domain prefixes, so a reflected value never verifies.
    private static String clientProof(String token, String nonce) {
        return sha256Hex("client:" + token + ":" + nonce);
    }

    private static String serverProof(String token, String nonce) {
        return sha256Hex("server:" + token + ":" + nonce);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : digest) b.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String newNonce() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder b = new StringBuilder(32);
        for (byte x : bytes) b.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
        return b.toString();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
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
    /** L-01: a merged request answers every caller that joined it. */
    private void deliverAll(Request request, JSONObject value) {
        for (Callback callback : request.callbacks) deliver(callback, value);
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
        ArrayDeque<Request> pending;
        synchronized (lock) { pending = new ArrayDeque<>(queue); queue.clear(); }
        // L-02: the pending queue used to be cleared without answering the callers, so a
        // caller that waited for a result (a task refresh, a resize) never heard back.
        for (Request request : pending) deliverAll(request, error("bridge closed"));
        disconnect();
        thread.quitSafely();
    }
    private static final class Request {
        final long id; final String command; final JSONObject payload; final String coalesceKey;
        final List<Callback> callbacks = new ArrayList<>();
        boolean mergeable;
        Request(long id, String c, JSONObject p, String key, Callback cb) {
            this.id = id; command = c; payload = p; coalesceKey = key;
            if (cb != null) callbacks.add(cb);
        }
    }
}
