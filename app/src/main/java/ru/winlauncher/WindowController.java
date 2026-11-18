package ru.winlauncher;

import android.content.Context;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import ru.winlauncher.bridge.RootBridge;

/**
 * Small first-pass window state machine. All model mutations and listener callbacks are on the
 * main thread; Binder work is serialized by RootBridge. Samsung stack IDs remain configurable.
 */
public final class WindowController {
    public interface Listener { void onWindowsChanged(List<Win> windows); }
    public interface Result { void onResult(boolean ok, String message); }
    public interface ErrorListener { void onError(String message); }

    private final Context context;
    private final RootBridge root;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Win> windows = new LinkedHashMap<>();
    private final List<Listener> listeners = new ArrayList<>();
    private ErrorListener errorListener;
    private final int freeformStackId;
    private final int fullscreenStackId;
    private int screenWidth;
    private int screenHeight;
    private int taskbarHeight = 48;
    private boolean hideStatusBar = true;
    private boolean monitoring;
    private long generation;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!monitoring) return;
            refreshTasks(null);
            main.postDelayed(this, 750L);
        }
    };

    public WindowController(Context context, RootBridge root) {
        this(context, root, 2, 1);
    }

    public WindowController(Context context, RootBridge root, int freeformStackId, int fullscreenStackId) {
        this.context = context.getApplicationContext();
        this.root = root;
        this.freeformStackId = freeformStackId;
        this.fullscreenStackId = fullscreenStackId;
        updateDisplayGeometry();
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.onWindowsChanged(snapshot());
    }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void setErrorListener(ErrorListener listener) { errorListener = listener; }

    public void setTaskbarHeight(int px) { taskbarHeight = Math.max(0, px); }
    public void setHideStatusBar(boolean hide) { hideStatusBar = hide; }

    public void startMonitoring() {
        if (monitoring) return;
        monitoring = true;
        refreshTasks(null);
        main.removeCallbacks(poll);
        main.postDelayed(poll, 750L);
    }

    public void stopMonitoring() {
        monitoring = false;
        main.removeCallbacks(poll);
    }

    public void updateDisplayGeometry() {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        android.view.Display display = ((android.view.WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
        display.getRealMetrics(dm); // task bounds and overlay coordinates use the whole display
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        generation++;
    }

    public Rect workArea() {
        int top = hideStatusBar ? 0 : statusBarHeight();
        return new Rect(0, top, screenWidth, Math.max(top, screenHeight - taskbarHeight));
    }

    private int statusBarHeight() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 24;
    }

    public List<Win> snapshot() {
        ArrayList<Win> out = new ArrayList<>(windows.values());
        Collections.sort(out, Comparator.comparingInt(w -> w.z));
        return out;
    }

    public Win get(int taskId) { return windows.get(taskId); }
    public long geometryGeneration() { return generation; }

    public void refreshTasks(Result callback) {
        root.tasks(result -> {
            if (!result.optBoolean("ok", false)) {
                if (callback != null) callback.onResult(false, result.optString("error", "tasks failed"));
                return;
            }
            try {
                JSONArray tasks = result.getJSONArray("tasks");
                adoptTasks(tasks);
                if (callback != null) callback.onResult(true, "Задач: " + tasks.length());
            } catch (Exception e) {
                if (callback != null) callback.onResult(false, "Некорректный ответ tasks: " + e.getMessage());
            }
        });
    }

    private void adoptTasks(JSONArray tasks) throws Exception {
        Map<Integer, Boolean> seen = new LinkedHashMap<>();
        String ownPackage = context.getPackageName();
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject row = tasks.getJSONObject(i);
            int id = row.getInt("id");
            int stack = row.optInt("stack", -1);
            String component = row.optString("component", "");
            String pkg = componentPackage(component);
            if (pkg.equals(ownPackage)) continue;
            Win w = windows.get(id);
            if (w == null) {
                // Initially manage only freeform tasks; avoid mistaking background fullscreen tasks
                // for minimized desktop windows before we have observed their state ourselves.
                if (stack != freeformStackId) continue;
                w = new Win(id);
                w.packageName = pkg;
                w.component = component;
                w.title = resolveTitle(pkg, component);
                w.stackId = stack;
                w.visible = row.optBoolean("visible", false);
                w.z = row.optInt("z", i);
                readBounds(w.bounds, row.optJSONArray("bounds"));
                if (sameRect(w.bounds, workArea())) w.state = Win.State.MAXIMIZED;
                else w.state = Win.State.NORMAL;
                if (w.state == Win.State.NORMAL) w.normalBounds.set(w.bounds);
                windows.put(id, w);
            } else {
                w.packageName = pkg;
                w.component = component;
                w.title = resolveTitle(pkg, component);
                w.stackId = stack;
                w.visible = row.optBoolean("visible", false);
                w.z = row.optInt("z", i);
                Rect actual = new Rect();
                readBounds(actual, row.optJSONArray("bounds"));
                if (actual.width() > 0 && actual.height() > 0) w.bounds.set(actual);
                if (stack == freeformStackId && w.state == Win.State.FULLSCREEN) w.state = Win.State.MAXIMIZED;
                if (stack == fullscreenStackId && w.state != Win.State.MINIMIZED) w.state = Win.State.FULLSCREEN;
            }
            seen.put(id, true);
        }
        ArrayList<Integer> remove = new ArrayList<>();
        for (Integer id : windows.keySet()) if (!seen.containsKey(id)) remove.add(id);
        for (Integer id : remove) windows.remove(id);
        notifyListeners();
    }

    private String resolveTitle(String pkg, String component) {
        try {
            CharSequence label = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(pkg, 0));
            if (label != null) return label.toString();
        } catch (Exception ignored) { }
        if (component != null && component.contains("/")) return component.substring(component.indexOf('/') + 1);
        return pkg == null || pkg.length() == 0 ? "Окно" : pkg;
    }

    private static String componentPackage(String component) {
        if (component == null) return "";
        int slash = component.indexOf('/');
        return slash >= 0 ? component.substring(0, slash) : component;
    }

    private static void readBounds(Rect out, JSONArray a) {
        if (a == null || a.length() != 4) return;
        out.set(a.optInt(0), a.optInt(1), a.optInt(2), a.optInt(3));
    }

    private static boolean sameRect(Rect a, Rect b) {
        return Math.abs(a.left - b.left) <= 2 && Math.abs(a.top - b.top) <= 2
                && Math.abs(a.right - b.right) <= 2 && Math.abs(a.bottom - b.bottom) <= 2;
    }

    public void focus(Win w) {
        root.front(w.taskId, result -> {
            if (result.optBoolean("ok", false)) {
                w.visible = true;
                for (Win other : windows.values()) other.z = other == w ? Integer.MAX_VALUE : other.z;
                notifyListeners();
            } else report(result, null);
        });
    }

    public void maximize(Win w) {
        if (w.state == Win.State.NORMAL && w.hasUsableBounds()) w.normalBounds.set(w.bounds);
        moveToStackThenResize(w, freeformStackId, true, workArea(), Win.State.MAXIMIZED, null);
    }

    public void fullscreen(Win w) {
        root.moveToStack(w.taskId, fullscreenStackId, true, result -> {
            if (!result.optBoolean("ok", false)) { report(result, null); return; }
            w.state = Win.State.FULLSCREEN;
            w.stackId = fullscreenStackId;
            notifyListeners();
        });
    }

    public void minimize(Win w) {
        if (w.state == Win.State.MINIMIZED) return;
        if (w.state == Win.State.NORMAL && w.hasUsableBounds()) w.normalBounds.set(w.bounds);
        w.stateBeforeMinimize = w.state;
        root.moveToStack(w.taskId, fullscreenStackId, false, result -> {
            if (!result.optBoolean("ok", false)) { report(result, null); return; }
            w.state = Win.State.MINIMIZED;
            w.stackId = fullscreenStackId;
            w.visible = false;
            notifyListeners();
        });
    }

    public void restore(Win w) {
        if (w.stateBeforeMinimize == Win.State.FULLSCREEN) {
            root.moveToStack(w.taskId, fullscreenStackId, true, result -> {
                if (!result.optBoolean("ok", false)) { report(result, null); return; }
                w.stackId = fullscreenStackId;
                w.state = Win.State.FULLSCREEN;
                w.visible = true;
                root.front(w.taskId, null);
                notifyListeners();
            });
            return;
        }
        Rect target = w.stateBeforeMinimize == Win.State.MAXIMIZED ? workArea() :
                (w.normalBounds.width() > 0 && w.normalBounds.height() > 0 ? new Rect(w.normalBounds) : fallbackNormalBounds());
        moveToStackThenResize(w, freeformStackId, true, target, w.stateBeforeMinimize, () -> focus(w));
    }

    public void toggleMaximize(Win w) {
        switch (w.state) {
            case NORMAL:
            case SNAPPED: maximize(w); break;
            case MAXIMIZED: fullscreen(w); break;
            case FULLSCREEN: maximize(w); break;
            case MINIMIZED: restore(w); break;
        }
    }

    /** Returns a maximized/snapped task to its saved window rectangle under the pointer. */
    public void restoreToNormal(Win w, Rect bounds) {
        Rect target = clamp(bounds);
        w.normalBounds.set(target);
        w.state = Win.State.NORMAL;
        w.stackId = freeformStackId;
        w.bounds.set(target);
        root.resize(w.taskId, target.left, target.top, target.right, target.bottom, 0, result -> {
            if (!result.optBoolean("ok", false)) report(result, null);
            notifyListeners();
        });
        notifyListeners();
    }

    public void requestMove(Win w, Rect bounds) {
        Rect target = clamp(bounds);
        w.bounds.set(target);
        root.resize(w.taskId, target.left, target.top, target.right, target.bottom, 1, null);
        notifyListeners();
    }

    public void commitMove(Win w) {
        Rect b = clamp(w.bounds);
        w.bounds.set(b);
        if (w.state == Win.State.NORMAL) w.normalBounds.set(b);
        root.resize(w.taskId, b.left, b.top, b.right, b.bottom, 0, result -> {
            if (!result.optBoolean("ok", false)) report(result, null);
            refreshTasks(null);
        });
    }

    public void close(Win w) {
        root.closeTask(w.taskId, result -> {
            if (!result.optBoolean("ok", false)) { report(result, null); return; }
            windows.remove(w.taskId);
            notifyListeners();
        });
    }

    private void moveToStackThenResize(Win w, int stack, boolean top, Rect target, Win.State targetState, Runnable after) {
        Runnable resize = () -> root.resize(w.taskId, target.left, target.top, target.right, target.bottom, 0, result -> {
            if (!result.optBoolean("ok", false)) { report(result, null); return; }
            w.bounds.set(target);
            w.stackId = stack;
            w.state = targetState;
            w.visible = true;
            notifyListeners();
            if (after != null) after.run();
        });
        if (w.stackId != stack) {
            root.moveToStack(w.taskId, stack, top, result -> {
                if (!result.optBoolean("ok", false)) { report(result, null); return; }
                resize.run();
            });
        } else resize.run();
    }

    private Rect fallbackNormalBounds() {
        Rect work = workArea();
        int width = Math.min(640, Math.max(240, work.width() * 2 / 3));
        int height = Math.min(480, Math.max(160, work.height() * 2 / 3));
        int left = Math.max(work.left, (work.width() - width) / 2);
        int top = Math.max(work.top, (work.height() - height) / 3);
        return new Rect(left, top, Math.min(work.right, left + width), Math.min(work.bottom, top + height));
    }

    private Rect clamp(Rect r) {
        Rect work = workArea();
        int minW = Math.min(240, work.width());
        int minH = Math.min(160, work.height());
        int width = Math.max(minW, Math.min(r.width(), work.width()));
        int height = Math.max(minH, Math.min(r.height(), work.height()));
        int left = Math.max(work.left - width + 48, Math.min(r.left, work.right - 48));
        int top = Math.max(work.top, Math.min(r.top, work.bottom - Math.min(32, height)));
        left = Math.min(left, work.right - 48);
        top = Math.min(top, work.bottom - minH);
        return new Rect(left, top, left + width, top + height);
    }

    private void report(JSONObject result, Result callback) {
        String error = result.optString("error", "root operation failed");
        if (callback != null) callback.onResult(false, error);
        else if (errorListener != null) errorListener.onError(error);
    }

    private void notifyListeners() {
        List<Win> copy = snapshot();
        for (Listener listener : new ArrayList<>(listeners)) listener.onWindowsChanged(copy);
    }
}
