package ru.winlauncher;

import android.content.Context;
import android.graphics.Rect;

import ru.winlauncher.bridge.RootBridge;

/** Lightweight policy: task operations commit immediately; opaque UI uses subtle hover and press feedback. */
public final class WindowAnimator {
    public enum Kind { OPEN, CLOSE, MINIMIZE, RESTORE, SNAP }
    public WindowAnimator(Context context,RootBridge root) { }

    /** Runs the real task operation immediately; the tablet profile keeps visual effects disabled. */
    public void transition(Win window,Rect target,Kind kind,Runnable changeRealTask){
        if(changeRealTask!=null)changeRealTask.run();
    }
}
