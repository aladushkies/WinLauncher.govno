package ru.winlauncher;

import android.graphics.Rect;

/** UI-thread-owned model of one Android task managed as a desktop window. */
public final class Win {
    public enum State { NORMAL, MAXIMIZED, FULLSCREEN, SNAPPED, MINIMIZED }

    public final int taskId;
    public String packageName;
    public String component;
    public String title;
    public int stackId;
    public int z;
    public int desktopId;
    public boolean visible;
    public State state = State.NORMAL;
    public State stateBeforeMinimize = State.NORMAL;
    public final Rect bounds = new Rect(); // last RootServer-confirmed bounds
    public final Rect requestedBounds = new Rect(); // latest drag target, awaiting confirmation
    public final Rect normalBounds = new Rect();
    public final Rect minimizeRestoreBounds = new Rect();
    public long resizeSequence;
    public long operationSequence;
    public String snapGroupId;
    public int snapCell = -1;

    public Win(int taskId) { this.taskId = taskId; }

    public boolean hasUsableBounds() { return bounds.width() > 0 && bounds.height() > 0; }
    public Rect copyBounds() { return new Rect(bounds); }
}
