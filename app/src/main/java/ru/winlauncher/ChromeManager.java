package ru.winlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Own opaque title-bar overlays for visible freeform tasks. Requires SYSTEM_ALERT_WINDOW app-op. */
public final class ChromeManager implements WindowController.Listener {
    public interface StatusListener { void onStatus(String message); }

    private final Context context;
    private final WindowController controller;
    private final WindowManager windowManager;
    private final Map<Integer, Chrome> chromes = new HashMap<>();
    private final List<Integer> zOrder = new ArrayList<>();
    private final int titleHeight;
    private final int buttonWidth;
    private final float density;
    private boolean enabled;
    private View fullscreenTopEdge;
    private Win fullscreenEdgeWin;
    private StatusListener statusListener;

    public ChromeManager(Context context, WindowController controller) {
        this.context = context;
        this.controller = controller;
        this.windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        this.density = context.getResources().getDisplayMetrics().density;
        this.titleHeight = dp(32);
        this.buttonWidth = dp(46);
        controller.addListener(this);
    }

    public void setStatusListener(StatusListener listener) { statusListener = listener; }

    public boolean hasOverlayPermission() {
        return android.os.Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context);
    }

    public void enable() {
        enabled = true;
        if (!hasOverlayPermission()) {
            status("Нет разрешения SYSTEM_ALERT_WINDOW. Выдайте appops SYSTEM_ALERT_WINDOW allow и включите заголовки снова.");
            return;
        }
        render(controller.snapshot());
    }

    public void disable() {
        enabled = false;
        removeAll();
    }

    public boolean isEnabled() { return enabled; }

    @Override public void onWindowsChanged(List<Win> windows) {
        if (enabled && hasOverlayPermission()) render(windows);
    }

    private void render(List<Win> windows) {
        List<Win> eligible = new ArrayList<>();
        for (Win w : windows) {
            if (w.stackId == 2 && w.visible && w.state != Win.State.FULLSCREEN &&
                    w.state != Win.State.MINIMIZED && w.hasUsableBounds()) eligible.add(w);
        }
        Collections.sort(eligible, Comparator.comparingInt(w -> w.z));
        List<Integer> desiredOrder = new ArrayList<>();
        int activeId = eligible.isEmpty() ? -1 : eligible.get(eligible.size() - 1).taskId;
        for (Win w : eligible) desiredOrder.add(w.taskId);

        for (Integer id : new ArrayList<>(chromes.keySet())) {
            if (!desiredOrder.contains(id)) removeChrome(id);
        }
        for (Win w : eligible) {
            Chrome chrome = chromes.get(w.taskId);
            if (chrome == null) {
                chrome = new Chrome(w);
                chromes.put(w.taskId, chrome);
                try { windowManager.addView(chrome.root, chrome.params); }
                catch (SecurityException e) {
                    chromes.remove(w.taskId);
                    status("Не удалось добавить заголовок: overlay permission denied");
                    return;
                } catch (RuntimeException e) {
                    chromes.remove(w.taskId);
                    status("Не удалось добавить заголовок: " + e.getMessage());
                    return;
                }
            }
            chrome.bind(w);
            chrome.setActive(w.taskId == activeId);
            updatePosition(chrome, w.bounds);
        }

        if (!zOrder.equals(desiredOrder)) {
            // WindowManager orders same-type system-alert windows by insertion sequence.
            // Reinsert only when task z-order actually changes, bottom-to-top.
            for (Integer id : desiredOrder) {
                Chrome c = chromes.get(id);
                if (c == null) continue;
                try { windowManager.removeView(c.root); } catch (RuntimeException ignored) { }
                try { windowManager.addView(c.root, c.params); } catch (RuntimeException e) {
                    status("Не удалось обновить порядок заголовков: " + e.getMessage());
                }
            }
            zOrder.clear(); zOrder.addAll(desiredOrder);
        }
        syncFullscreenEdge(windows);
    }

    private void syncFullscreenEdge(List<Win> windows) {
        Win candidate = null;
        for (Win w : windows) {
            if (w.state == Win.State.FULLSCREEN && w.stackId == 1 && w.visible &&
                    (candidate == null || w.z > candidate.z)) candidate = w;
        }
        if (candidate == null) {
            if (fullscreenTopEdge != null) {
                try { windowManager.removeView(fullscreenTopEdge); } catch (RuntimeException ignored) { }
                fullscreenTopEdge = null;
                fullscreenEdgeWin = null;
            }
            return;
        }
        fullscreenEdgeWin = candidate;
        if (fullscreenTopEdge == null) {
            View edge = new View(context);
            edge.setBackgroundColor(0x01000000); // nearly invisible but remains a touch target
            edge.setOnTouchListener(new View.OnTouchListener() {
                float downY;
                @Override public boolean onTouch(View v, MotionEvent event) {
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        downY = event.getRawY();
                        return true;
                    }
                    if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                        if (event.getRawY() - downY > dp(12) && fullscreenEdgeWin != null)
                            controller.maximize(fullscreenEdgeWin);
                        return true;
                    }
                    return true;
                }
            });
            WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, dp(3),
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            p.gravity = Gravity.TOP | Gravity.LEFT;
            p.setTitle("WinLauncher fullscreen reveal edge");
            try {
                windowManager.addView(edge, p);
                fullscreenTopEdge = edge;
            } catch (RuntimeException e) { status("Не удалось создать fullscreen hot-edge: " + e.getMessage()); }
        }
    }

    private void updatePosition(Chrome c, Rect b) {
        int width = Math.max(dp(160), b.width());
        if (c.lastLeft == b.left && c.lastTop == b.top && c.lastWidth == width) return;
        c.params.x = b.left;
        c.params.y = b.top;
        c.params.width = width;
        c.params.height = titleHeight;
        c.lastLeft = b.left; c.lastTop = b.top; c.lastWidth = width;
        try {
            if (c.root.getParent() != null) windowManager.updateViewLayout(c.root, c.params);
        } catch (RuntimeException e) { status("Не удалось обновить заголовок: " + e.getMessage()); }
    }

    private void removeChrome(int id) {
        Chrome c = chromes.remove(id);
        if (c != null) {
            try { if (c.root.getParent() != null) windowManager.removeView(c.root); }
            catch (RuntimeException ignored) { }
        }
        zOrder.remove((Integer) id);
    }

    private void removeAll() {
        for (Integer id : new ArrayList<>(chromes.keySet())) removeChrome(id);
        zOrder.clear();
        if (fullscreenTopEdge != null) {
            try { windowManager.removeView(fullscreenTopEdge); } catch (RuntimeException ignored) { }
            fullscreenTopEdge = null;
            fullscreenEdgeWin = null;
        }
    }

    private void status(String message) { if (statusListener != null) statusListener.onStatus(message); }
    private int dp(float value) { return (int) (value * density + 0.5f); }

    private final class Chrome {
        final LinearLayout root;
        final LinearLayout dragArea;
        final TextView title;
        final TextView minimize;
        final TextView maximize;
        final TextView close;
        final WindowManager.LayoutParams params;
        Win model;
        int lastLeft = Integer.MIN_VALUE, lastTop = Integer.MIN_VALUE, lastWidth = -1;
        boolean active;

        Chrome(Win model) {
            this.model = model;
            root = new LinearLayout(context);
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setGravity(Gravity.CENTER_VERTICAL);
            root.setPadding(dp(10), 0, 0, 0);
            root.setBackgroundColor(0xfff3f3f3);
            root.setElevation(dp(2));

            dragArea = new LinearLayout(context);
            dragArea.setOrientation(LinearLayout.HORIZONTAL);
            dragArea.setGravity(Gravity.CENTER_VERTICAL);
            ImageView icon = new ImageView(context);
            icon.setImageDrawable(loadIcon(model.packageName));
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(16), dp(16));
            dragArea.addView(icon, ip);
            title = new TextView(context);
            title.setTextSize(12);
            title.setSingleLine(true);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setPadding(dp(10), 0, dp(4), 0);
            LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -1, 1f);
            dragArea.addView(title, tp);
            root.addView(dragArea, new LinearLayout.LayoutParams(0, titleHeight, 1f));

            minimize = makeButton("—");
            maximize = makeButton("□");
            close = makeButton("×");
            root.addView(minimize, new LinearLayout.LayoutParams(buttonWidth, titleHeight));
            root.addView(maximize, new LinearLayout.LayoutParams(buttonWidth, titleHeight));
            root.addView(close, new LinearLayout.LayoutParams(buttonWidth, titleHeight));
            minimize.setOnClickListener(v -> controller.minimize(this.model));
            maximize.setOnClickListener(v -> controller.toggleMaximize(this.model));
            close.setOnClickListener(v -> controller.close(this.model));
            dragArea.setOnTouchListener(new DragTouch(this));

            params = new WindowManager.LayoutParams(
                    Math.max(dp(160), model.bounds.width()), titleHeight,
                    WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.LEFT;
            params.x = model.bounds.left;
            params.y = model.bounds.top;
            params.setTitle("WinLauncher chrome " + model.taskId);
        }

        void bind(Win w) {
            model = w;
            title.setText(w.title == null ? "Окно" : w.title);
            Drawable icon = loadIcon(w.packageName);
            // Icon is the first child of dragArea.
            ((ImageView) dragArea.getChildAt(0)).setImageDrawable(icon);
            maximize.setText(w.state == Win.State.MAXIMIZED ? "⛶" : "□");
        }

        void setActive(boolean value) {
            active = value;
            root.setBackgroundColor(value ? 0xfff3f3f3 : 0xfffbfbfb);
            title.setTextColor(value ? 0xff1a1a1a : 0xff5c5c5c);
            minimize.setTextColor(value ? 0xff202020 : 0xff808080);
            maximize.setTextColor(value ? 0xff202020 : 0xff808080);
            close.setTextColor(value ? 0xff202020 : 0xff808080);
        }

        private TextView makeButton(String text) {
            TextView b = new TextView(context);
            b.setText(text); b.setTextSize(17); b.setGravity(Gravity.CENTER);
            b.setTextColor(Color.BLACK);
            b.setBackgroundColor(Color.TRANSPARENT);
            b.setOnHoverListener((v, e) -> {
                if (e.getAction() == MotionEvent.ACTION_HOVER_ENTER) {
                    v.setBackgroundColor(v == close ? 0xffc42b1c : 0x0f000000);
                    if (v == close) ((TextView) v).setTextColor(Color.WHITE);
                } else if (e.getAction() == MotionEvent.ACTION_HOVER_EXIT) {
                    v.setBackgroundColor(Color.TRANSPARENT);
                    ((TextView) v).setTextColor(active ? 0xff202020 : 0xff808080);
                }
                return false;
            });
            return b;
        }

        private Drawable loadIcon(String packageName) {
            try { return context.getPackageManager().getApplicationIcon(packageName); }
            catch (Exception e) { return context.getApplicationInfo().loadIcon(context.getPackageManager()); }
        }
    }

    private final class DragTouch implements View.OnTouchListener {
        private final Chrome chrome;
        private float downX, downY;
        private Rect startBounds = new Rect();
        private boolean moving;
        private long lastTap;

        DragTouch(Chrome chrome) { this.chrome = chrome; }

        @Override public boolean onTouch(View view, MotionEvent event) {
            Win w = chrome.model;
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getRawX(); downY = event.getRawY();
                    startBounds.set(w.bounds); moving = false;
                    controller.focus(w);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float x = event.getRawX(), y = event.getRawY();
                    if (!moving && Math.hypot(x - downX, y - downY) < dp(8)) return true;
                    if (!moving) {
                        moving = true;
                        if (w.state == Win.State.MAXIMIZED || w.state == Win.State.SNAPPED) {
                            Rect restore = restoredRectUnderPointer(w, x, y);
                            controller.restoreToNormal(w, restore);
                            startBounds.set(restore);
                            downX = x; downY = y;
                        }
                    }
                    int dx = Math.round(x - downX), dy = Math.round(y - downY);
                    Rect next = new Rect(startBounds);
                    next.offset(dx, dy);
                    controller.requestMove(w, next);
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    if (moving) {
                        if (event.getRawY() <= dp(8)) controller.maximize(w);
                        else controller.commitMove(w);
                    } else {
                        long now = android.os.SystemClock.uptimeMillis();
                        if (now - lastTap < 350) { controller.toggleMaximize(w); lastTap = 0; }
                        else lastTap = now;
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    if (moving) controller.commitMove(w);
                    return true;
            }
            return true;
        }

        private Rect restoredRectUnderPointer(Win w, float x, float y) {
            Rect r = new Rect(w.normalBounds);
            if (r.width() < dp(240) || r.height() < dp(160)) {
                android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
                ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
                int sw = dm.widthPixels;
                int sh = dm.heightPixels;
                int width = Math.min(dp(640), sw * 2 / 3);
                int height = Math.min(dp(480), sh * 2 / 3);
                r.set(0, 0, width, height);
            }
            float fraction = Math.max(0.1f, Math.min(0.9f, (x - w.bounds.left) / Math.max(1, w.bounds.width())));
            int left = Math.round(x - fraction * r.width());
            int top = Math.max(0, Math.round(y - dp(12)));
            r.offsetTo(left, top);
            return r;
        }
    }
}
