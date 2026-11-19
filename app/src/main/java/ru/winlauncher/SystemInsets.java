package ru.winlauncher;

import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

/**
 * Snapshot of the system-bar and display-cutout insets of the default display.
 *
 * The launcher draws its overlays in the full display coordinate space (FLAG_LAYOUT_IN_SCREEN),
 * so every absolutely positioned surface has to know how much of the screen the system bars
 * and a display cutout cover. The values are read once per display change and handed to the
 * overlays through {@link WindowController}, which keeps a single source of truth for the
 * geometry instead of letting every overlay guess.
 */
public final class SystemInsets {
    /** Height of the status bar, in pixels. */
    public final int statusBar;
    /** Height of the navigation bar (or the gesture handle area), in pixels. */
    public final int navigationBar;
    /** Safe inset of a display cutout at the top, in pixels. */
    public final int cutoutTop;
    /** Safe inset of a display cutout at the bottom, in pixels. */
    public final int cutoutBottom;
    /** Safe inset of a display cutout on the left, in pixels. */
    public final int cutoutLeft;
    /** Safe inset of a display cutout on the right, in pixels. */
    public final int cutoutRight;

    public SystemInsets(int statusBar, int navigationBar, int cutoutTop, int cutoutBottom, int cutoutLeft, int cutoutRight) {
        this.statusBar = statusBar;
        this.navigationBar = navigationBar;
        this.cutoutTop = cutoutTop;
        this.cutoutBottom = cutoutBottom;
        this.cutoutLeft = cutoutLeft;
        this.cutoutRight = cutoutRight;
    }

    /** An empty inset set, used before the display geometry is known. */
    public static SystemInsets empty() { return new SystemInsets(0, 0, 0, 0, 0, 0); }

    /**
     * Reads the insets of the given display. The cutout is only available from API 28 on;
     * on older devices the status and navigation bars are the only obstacles.
     */
    public static SystemInsets read(Context context, Display display) {
        int status = dimension(context, "status_bar_height");
        int navigation = dimension(context, "navigation_bar_height");
        int top = 0, bottom = 0, left = 0, right = 0;
        if (Build.VERSION.SDK_INT >= 28 && display != null) {
            android.view.DisplayCutout cutout = display.getCutout();
            if (cutout != null) {
                top = cutout.getSafeInsetTop();
                bottom = cutout.getSafeInsetBottom();
                left = cutout.getSafeInsetLeft();
                right = cutout.getSafeInsetRight();
            }
        }
        return new SystemInsets(status, navigation, top, bottom, left, right);
    }

    /** Reads the insets of the default display of the window manager. */
    public static SystemInsets read(Context context) {
        Display display = null;
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) display = wm.getDefaultDisplay();
        } catch (RuntimeException ignored) {
        }
        return read(context, display);
    }

    private static int dimension(Context context, String name) {
        if (context == null) return 0;
        int id = context.getResources().getIdentifier(name, "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 0;
    }

    /**
     * The inset that freeform windows and top-anchored overlays leave free.
     * A hidden status bar frees its space, but a cutout never disappears.
     */
    public int top(boolean hideStatusBar) { return Math.max(hideStatusBar ? 0 : statusBar, cutoutTop); }

    /** The inset that bottom-anchored overlays leave free. */
    public int bottom() { return Math.max(navigationBar, cutoutBottom); }

    /** The inset that left-anchored content leaves free. */
    public int left() { return cutoutLeft; }

    /** The inset that right-anchored content leaves free. */
    public int right() { return cutoutRight; }

    /** True when the display has nothing but the system bars around it. */
    public boolean isEmpty() {
        return statusBar == 0 && navigationBar == 0 && cutoutTop == 0 && cutoutBottom == 0 && cutoutLeft == 0 && cutoutRight == 0;
    }
}
