package ru.winlauncher.root;

import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Bundle;
import android.os.Binder;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
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
    private static final int MAX_LINE = 512 * 1024;
    private static volatile Object activityManager;
    private static volatile String socketName;
    private static volatile String authToken;
    private static volatile int ownerUid;
    private static final IBinder statusToken = new Binder();
    private static volatile Object statusBarService;

    private RootServer() {}

    public static void main(String[] args) {
        if (args == null || args.length != 1) {
            System.err.println("Usage: RootServer <owner-uid> (token is read from stdin)");
            return;
        }
        ownerUid = Integer.parseInt(args[0]);
        // BUG-04: the token used to travel in argv, where other processes could read it from
        // /proc/<pid>/cmdline on some Android 7.1 firmware. The launcher now writes it to the
        // app_process stdin and closes the stream; read exactly one line before serving.
        authToken = readTokenFromStdin();
        if (authToken == null || authToken.length() == 0) {
            System.err.println("WinLauncher RootServer: no auth token on stdin");
            return;
        }
        socketName = "wl_root_" + ownerUid;
        try {
            activityManager = getActivityManager();
            serveForever();
        } catch (Throwable t) {
            System.err.println("WinLauncher RootServer stopped: " + t);
            t.printStackTrace(System.err);
        }
    }

    private static String readTokenFromStdin() {
        try {
            BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String line = r.readLine();
            return line == null ? null : line.trim();
        } catch (Exception e) {
            return null;
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
                socket.setSoTimeout(120000); // S2: idle sessions must not pin a thread forever
                int peerUid = peerUid(socket);
                if (peerUid != ownerUid) return;
                in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                String hello = readLine(in);
                if (hello == null) return;
                JSONObject h = new JSONObject(hello);
                String supplied = h.optString("token", "");
                String nonce = h.optString("nonce", "");
                // S15: the client now sends H(token:nonce); a replayed frame from an earlier session
                // cannot authenticate because the nonce is fresh, and the server proves itself below.
                if (nonce.length() < 16
                        || !constantTimeEquals(clientProof(authToken, nonce), supplied)
                        || h.optInt("uid", -1) != ownerUid) {
                    send(new JSONObject().put("ok", false).put("error", "authentication failed"));
                    return;
                }
                send(new JSONObject().put("ready", true).put("protocol", 2)
                        .put("proof", serverProof(authToken, nonce)));
                String line;
                while ((line = readLine(in)) != null) {
                    // D-05: parse the id before the try so error responses carry it too; the
                    // client matches responses to requests by id and drops unmatched ones.
                    long id = -1;
                    try {
                        JSONObject request = new JSONObject(line);
                        id = request.optLong("id", -1);
                        JSONObject response = handle(request);
                        response.put("id", id);
                        send(response);
                    } catch (Throwable t) {
                        JSONObject err = new JSONObject().put("ok", false)
                                .put("id", id)
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
                    // S20: reject degenerate/hostile rectangles before they reach resizeTask.
                    if (bounds.width() < 1 || bounds.height() < 1)
                        throw new IllegalArgumentException("resize bounds must be non-empty");
                    if (bounds.left < -100000 || bounds.top < -100000 || bounds.right > 100000 || bounds.bottom > 100000)
                        throw new IllegalArgumentException("resize bounds out of range");
                    int taskId = j.getInt("t");
                    invoke(activityManager, "resizeTask", taskId, bounds, j.optInt("m", 0));
                    JSONObject response = new JSONObject().put("ok", true);
                    JSONArray actual = findTaskBounds(taskId);
                    if (actual != null) response.put("bounds", actual);
                    return response;
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
                case "status":
                    setStatusBarHidden(j.getBoolean("hide"));
                    return new JSONObject().put("ok", true).put("hidden", j.getBoolean("hide"));
                case "accessibility":
                    enableAccessibilityService();
                    return new JSONObject().put("ok", true).put("enabled", "ru.winlauncher/ru.winlauncher.KeyService");
                case "capture":
                    return captureScreenshot();
                case "device":
                    return performDeviceAction(j.optString("a", ""), j.optBoolean("v", false));
                case "device_state":
                    return readDeviceState(j.optString("a", ""));
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

    private static JSONObject captureScreenshot() throws Exception {
        File dir=new File("/sdcard/Pictures/WinLauncher");
        if(!dir.exists())dir.mkdirs(); // best effort: uid 0 is not in the sdcard group on Android 7.1
        File image=new File(dir,"Screenshot-"+System.currentTimeMillis()+".png");
        String error=captureTo(image);
        boolean usedFallback=false;
        if(error!=null){
            // S36: app_process started as uid 0 has no EXTERNAL_STORAGE/FUSE group, so the public
            // picture directory is often not writable. Fall back to a root-owned path the launcher
            // (and adb) can still read instead of failing every PrtSc press.
            File fallback=new File("/data/local/tmp","wl_Screenshot-"+System.currentTimeMillis()+".png");
            error=captureTo(fallback);
            if(error!=null)throw new IOException("screencap failed: "+error);
            fallback.setReadable(true,false);
            image=fallback;
            usedFallback=true;
        }
        JSONObject response=new JSONObject().put("ok",true).put("path",image.getAbsolutePath());
        // The client warns the user when the screenshot did not land in the public gallery folder.
        if(usedFallback)response.put("fallback",true);
        return response;
    }

    /**
     * F-01: result of one child process. stdout and stderr are merged by the helper,
     * so a command that prints (am broadcast, svc, screencap warnings) can no longer
     * die from SIGPIPE (exit 141) because nobody closed its write end early.
     */
    private static final class ProcessResult {
        final int exit;
        final String output;
        ProcessResult(int exit,String output){
            this.exit=exit;
            this.output=output;
        }
    }

    /**
     * F-01: the only way RootServer starts a child that must run to completion.
     * stderr is redirected into stdout, the child's stdin is closed immediately,
     * the merged stream is drained to EOF, and only then waitFor() runs.
     * The stream and the process are always released in finally.
     */
    private static ProcessResult runProcess(String[] command) throws Exception {
        ProcessBuilder builder=new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        Process process=builder.start();
        java.io.InputStream stream=null;
        try{
            try{
                process.getOutputStream().close();
            }catch(Exception ignored){
            }
            stream=process.getInputStream();
            ByteArrayOutputStream buffer=new ByteArrayOutputStream();
            byte[] chunk=new byte[4096];
            int read=stream.read(chunk);
            while(read>0){
                buffer.write(chunk,0,read);
                read=stream.read(chunk);
            }
            int exit=process.waitFor();
            return new ProcessResult(exit,new String(buffer.toByteArray(),StandardCharsets.UTF_8));
        }finally{
            if(stream!=null){
                try{
                    stream.close();
                }catch(Exception ignored){
                }
            }
            process.destroy();
        }
    }

    /** S36 helper: runs screencap and reports failure as text instead of throwing. */
    private static String captureTo(File image){
        try{
            ProcessResult result=runProcess(new String[]{"/system/bin/screencap","-p",image.getAbsolutePath()});
            if(result.exit!=0)return "exit="+result.exit;
            if(!image.isFile()||image.length()==0)return "empty file";
            return null;
        }catch(Exception e){
            return String.valueOf(e.getMessage());
        }
    }

        private static int execAndWait(String[] command) throws Exception {
        return runProcess(command).exit;
    }

    private static JSONObject readDeviceState(String action) throws Exception {
        String namespace,key;
        if("wifi".equals(action)){namespace="global";key="wifi_on";}
        // D-04: since API 17 bluetooth_on lives in Settings.Global; "secure" is only a
        // fallback for non-standard firmware, otherwise the tile always reads <NULL>.
        else if("bluetooth".equals(action)){namespace="global";key="bluetooth_on";}
        else if("mobile_data".equals(action)){namespace="global";key="mobile_data";}
        else if("airplane".equals(action)){namespace="global";key="airplane_mode_on";}
        else throw new IllegalArgumentException("unsupported device action: "+action);
        String value=readSetting(namespace,key);
        if("<NULL>".equals(value)&&"bluetooth".equals(action))value=readSetting("secure","bluetooth_on");
        boolean known="0".equals(value)||"1".equals(value);
        return new JSONObject().put("ok",true).put("action",action).put("known",known).put("enabled","1".equals(value));
    }

    private static JSONObject performDeviceAction(String action,boolean enabled) throws Exception {
        String[] command;
        if("wifi".equals(action))command=new String[]{"/system/bin/svc","wifi",enabled?"enable":"disable"};
        else if("bluetooth".equals(action))command=new String[]{"/system/bin/svc","bluetooth",enabled?"enable":"disable"};
        else if("mobile_data".equals(action))command=new String[]{"/system/bin/svc","data",enabled?"enable":"disable"};
        else if("airplane".equals(action)){
            // D-11: "&&", not ";" — with ";" the exit code came from the broadcast alone and a
            // failed settings write still reported success.
            command=new String[]{"/system/bin/sh","-c","/system/bin/settings put global airplane_mode_on "+(enabled?"1":"0")+" && /system/bin/am broadcast -a android.intent.action.AIRPLANE_MODE --ez state "+(enabled?"true":"false")};
        }else throw new IllegalArgumentException("unsupported device action: "+action);
        int exit=execAndWait(command);
        if(exit!=0)throw new IOException("device action failed: "+action+" exit="+exit);
        // D-11: report the state actually read back, not the requested one.
        if("airplane".equals(action))return readDeviceState("airplane");
        return new JSONObject().put("ok",true).put("action",action).put("enabled",enabled);
    }

    private static JSONArray findTaskBounds(int taskId) throws Exception {
        JSONArray all = snapshotTasks();
        for (int i = 0; i < all.length(); i++) {
            JSONObject task = all.getJSONObject(i);
            if (task.optInt("id", -1) == taskId) return task.optJSONArray("bounds");
        }
        return null;
    }

        private static void enableAccessibilityService() throws Exception {
        final String component="ru.winlauncher/ru.winlauncher.KeyService";
        ProcessResult read=runProcess(new String[]{"/system/bin/settings","get","secure","enabled_accessibility_services"});
        if(read.exit!=0)throw new IOException("settings get accessibility exit="+read.exit);
        String current=read.output;
        int newline=current.indexOf('\n');
        if(newline>=0)current=current.substring(0,newline);
        current=current.trim();
        if("null".equals(current))current="";
        boolean found=false;
        for(String item:current.split(":")){
            if(component.equals(item.trim())){
                found=true;
            }
        }
        if(!found)current=current.length()==0?component:current+":"+component;
        if(execAndWait(new String[]{"/system/bin/settings","put","secure","enabled_accessibility_services",current})!=0){
            throw new IOException("settings put enabled_accessibility_services failed");
        }
        if(execAndWait(new String[]{"/system/bin/settings","put","secure","accessibility_enabled","1"})!=0){
            throw new IOException("settings put accessibility_enabled failed");
        }
    }

        private static String readSetting(String namespace,String key) throws Exception {
        ProcessResult result=runProcess(new String[]{"/system/bin/settings","get",namespace,key});
        if(result.exit!=0)throw new IOException("settings get failed: "+key);
        String value=result.output;
        int newline=value.indexOf('\n');
        if(newline>=0)value=value.substring(0,newline);
        value=value.trim();
        return value.length()==0||"null".equals(value)?"<NULL>":value;
    }
    private static void writeText(File file,String value) throws IOException {
        FileOutputStream out=new FileOutputStream(file);try{out.write((value==null?"<NULL>":value).getBytes(StandardCharsets.UTF_8));}finally{out.close();}
    }
    private static String readText(File file) throws IOException {
        FileInputStream in=new FileInputStream(file);try{ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[256];int n;while((n=in.read(buf))>0)b.write(buf,0,n);return new String(b.toByteArray(),StandardCharsets.UTF_8);}finally{in.close();}
    }

    /** Hides status UI and disables shade expansion without using arbitrary shell commands. */
    private static void setStatusBarHidden(boolean hidden) throws Exception {
        Exception binderError = null;
        try {
            if (statusBarService == null) {
                Class<?> manager = Class.forName("android.os.ServiceManager");
                Method get = manager.getDeclaredMethod("getService", String.class);
                get.setAccessible(true);
                IBinder binder = (IBinder) get.invoke(null, "statusbar");
                Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
                Method asInterface = stub.getDeclaredMethod("asInterface", IBinder.class);
                asInterface.setAccessible(true);
                statusBarService = asInterface.invoke(null, binder);
            }
            // S1: status bar and notification shade only. The old mask also set the DISABLE2
            // quick-settings bit (bit 23) and the navigation-bar bits, so hiding the status bar also
            // removed Back/Home/Recents and froze the clock on the test tablet.
            int disable = hidden ? (0x00010000 | 0x00020000 | 0x00040000 | 0x00100000) : 0;
            invoke(statusBarService, "disable", disable, statusToken, "ru.winlauncher");
        } catch (Exception e) {
            binderError = e;
        }

        File backup = new File("/data/local/tmp/wl_policy_control_" + ownerUid);
        boolean settingsApplied = true;
        // F-06: a failing settings read or write must degrade to settingsApplied=false instead of
        // throwing over a successful IStatusBarService.disable().
        try {
            if (hidden) {
                if (!backup.exists()) writeText(backup, readSetting("global", "policy_control"));
                settingsApplied = execAndWait(new String[]{"/system/bin/settings", "put", "global", "policy_control", "immersive.status=*"}) == 0;
            } else if (backup.exists()) {
                String original = readText(backup);
                if (original == null || "<NULL>".equals(original)) {
                    settingsApplied = execAndWait(new String[]{"/system/bin/settings", "delete", "global", "policy_control"}) == 0;
                } else {
                    settingsApplied = execAndWait(new String[]{"/system/bin/settings", "put", "global", "policy_control", original}) == 0;
                }
            } else {
                // BUG-13: the backup file can vanish (reboot, /data/local/tmp cleanup) while our
                // immersive value is still in effect; without this branch the status bar would
                // stay hidden forever because there is nothing left to restore from.
                String current = readSetting("global", "policy_control");
                if ("immersive.status=*".equals(current)) {
                    settingsApplied = execAndWait(new String[]{"/system/bin/settings", "delete", "global", "policy_control"}) == 0;
                }
            }
        } catch (Exception settingsError) {
            settingsApplied = false;
        }

        // BUG-13: deleting the backup after a failed restore destroyed the only copy of the
        // user's original policy_control. Only drop it once the restore actually applied.
        if (!hidden && settingsApplied && backup.exists()) backup.delete();
        // S1: one working mechanism is success. Reporting failure while policy_control actually applied
        // made WindowController retry the same write on every 750 ms poll (status-bar retry storm).
        if (binderError != null && !settingsApplied)
            throw new IOException("status-bar disable failed: " + safeMessage(binderError), binderError);
    }

    private static JSONArray snapshotTasks() throws Exception {
        Object value = invoke(activityManager, "getAllStackInfos");
        if (!(value instanceof List)) throw new IllegalStateException("getAllStackInfos did not return a List");
        JSONArray result = new JSONArray();
        List<?> stacks = (List<?>) value;
        // BUG-15: in AOSP 7.1 getAllStackInfos() returns stacks top-first (the topmost stack is
        // element 0) and taskIds bottom-to-top inside a stack. The old flat z++ therefore gave
        // lower stacks higher z values than the top stack, inverting cross-stack priority.
        // Rank stacks in reverse so the top stack wins; task order inside a stack is kept.
        // ASSUMPTION: the top-first order still has to be confirmed on the SM-T555 (E-5).
        for (int stackIndex = 0; stackIndex < stacks.size(); stackIndex++) {
            Object stack = stacks.get(stackIndex);
            int stackRank = stacks.size() - 1 - stackIndex;
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
                        .put("z", stackRank * 1000 + i));
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

    // BUG-04: domain-separated proofs, identical to the client in RootBridge. One shared
    // H(token:nonce) in both directions allowed a reflection attack on S15.
    private static String clientProof(String token, String nonce) {
        return sha256Hex("client:" + token + ":" + nonce);
    }

    private static String serverProof(String token, String nonce) {
        return sha256Hex("server:" + token + ":" + nonce);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder(64);
            for (byte x : digest) b.append(String.format(java.util.Locale.US, "%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return "";
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
