package ru.winlauncher;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/**
 * Accessibility hook used only for Samsung caption calibration and F11. Enable it from the
 * system Accessibility screen; it does not read or persist application text/content.
 */
public final class KeyService extends AccessibilityService {
    @Override protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED
                    | AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED;
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
            setServiceInfo(info);
        }
    }

    private long lastCaptionScan;

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        LauncherPrefs prefs = new LauncherPrefs(this);
        if (prefs.isCaptionCalibrated()) return;
        // D-14: walking every window's node tree on every accessibility event is wasteful and
        // risks calibrating from a transient layout. Scan at most once per 1.5 s and only on
        // window-state changes.
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastCaptionScan < 1500) return;
        lastCaptionScan = now;
        try {
            for (AccessibilityWindowInfo window : getWindows()) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                Rect windowBounds = new Rect();
                window.getBoundsInScreen(windowBounds);
                AccessibilityNodeInfo caption = findCaption(root);
                if (caption != null) {
                    Rect b = new Rect();
                    caption.getBoundsInScreen(b);
                    int height = b.height();
                    if (height >= 24 && height <= 96) {
                        prefs.setCaptionCalibration(height,
                                Math.max(0, b.left - windowBounds.left),
                                Math.max(0, windowBounds.right - b.right));
                    }
                    caption.recycle();
                    if (prefs.isCaptionCalibrated()) { root.recycle(); return; }
                }
                root.recycle();
            }
        } catch (Throwable ignored) { }
    }

    private AccessibilityNodeInfo findCaption(AccessibilityNodeInfo node) {
        if (node == null) return null;
        String id = node.getViewIdResourceName();
        // D-14: exact Samsung framework id only. The old ":id/caption" suffix match could lock
        // onto a third-party app's own caption view and persist a wrong calibration forever.
        if (id != null && id.equals("com.samsung.android.framework.res:id/caption")) return AccessibilityNodeInfo.obtain(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            AccessibilityNodeInfo found = findCaption(child);
            if (child != null) child.recycle();
            if (found != null) return found;
        }
        return null;
    }

    private boolean escapeWasHandled;
    // BUG-12: key codes whose ACTION_DOWN we swallowed; their ACTION_UP is swallowed too,
    // even when the user released the modifier first (the old code re-derived the shortcut
    // from the ACTION_UP event's meta state and lost it in exactly that case).
    private final java.util.Set<Integer> swallowedKeys = new java.util.HashSet<>();

    @Override protected boolean onKeyEvent(KeyEvent event) {
        WindowController controller=WindowController.active();
        if(controller!=null&&controller.handleSystemMenuKey(event))return true;
        int key=event.getKeyCode(),action=event.getAction(),meta=event.getMetaState();
        // F-07(b): release swallowed keys before any early return, so a controller or task view
        // that disappears mid-press can never leave a key stuck in swallowedKeys.
        if(action==KeyEvent.ACTION_UP&&swallowedKeys.remove(key))return true;
        boolean alt=(meta&KeyEvent.META_ALT_ON)!=0,win=(meta&KeyEvent.META_META_ON)!=0;
        boolean ctrl=(meta&KeyEvent.META_CTRL_ON)!=0,shift=(meta&KeyEvent.META_SHIFT_ON)!=0;
        boolean ctrlShiftEsc=ctrl&&shift&&key==KeyEvent.KEYCODE_ESCAPE;
        // F-07(a): Ctrl+Shift+Esc now uses the same ACTION_DOWN + swallowedKeys scheme as every
        // other shortcut; releasing Ctrl/Shift before Esc can no longer lose the gesture.
        if(ctrlShiftEsc){
            if(action==KeyEvent.ACTION_DOWN){
                swallowedKeys.add(key);
                if(event.getRepeatCount()==0){
                    android.content.Intent taskManager=new android.content.Intent(this,TaskManagerActivity.class);
                    taskManager.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(taskManager);
                }
            }
            return true;
        }
        if(key==KeyEvent.KEYCODE_ESCAPE){
            if(action==KeyEvent.ACTION_DOWN){
                escapeWasHandled=(SnapController.active()!=null&&SnapController.active().hasAssist())
                        ||(SnapController.active()!=null&&SnapController.active().hasLayoutsPopup())
                        ||TaskViewOverlay.isShowing()||ActionCenterOverlay.isShowing()
                        ||FullscreenEdgeManager.isMiniShowing()||ChromeManager.hasEscapeOverlay()
                        ||TaskbarOverlay.isJumpMenuShowing()
                        ||controller!=null&&controller.willHandleEscapeFullscreen();
                return escapeWasHandled;
            }
            if(action==KeyEvent.ACTION_UP){
                boolean handled=escapeWasHandled;escapeWasHandled=false;
                if(!handled)return false;
                if(SnapController.active()!=null&&SnapController.active().dismissLayoutsForEscape())return true;
                if(SnapController.active()!=null&&SnapController.active().hasAssist()){SnapController.active().cancelAssist();return true;}
                if(TaskViewOverlay.dismissForEscape())return true;
                if(ActionCenterOverlay.dismissActiveForEscape())return true;
                if(FullscreenEdgeManager.dismissMiniForEscape())return true;
                if(TaskbarOverlay.dismissJumpMenuForEscape())return true;
                if(ChromeManager.dismissOverlayForEscape())return true;
                return controller!=null&&controller.handleEscapeFullscreen();
            }
            return escapeWasHandled;
        }
        boolean chromeEnabled=ChromeManager.isChromeEnabled();
        // M-07(4): plain F11 only — Ctrl/Shift/Win+F11 are other chords, not fullscreen.
        boolean f11=key==KeyEvent.KEYCODE_F11&&!ctrl&&!shift&&!win;
        // M-07(4): Alt+Space opens a chrome menu; with the chrome off it must pass through.
        boolean altSpace=alt&&key==KeyEvent.KEYCODE_SPACE&&chromeEnabled;
        boolean altF11=alt&&f11;
        boolean altTab=alt&&key==KeyEvent.KEYCODE_TAB;
        boolean altF4=alt&&key==KeyEvent.KEYCODE_F4;
        boolean winCtrlDesktop=win&&ctrl&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT);
        boolean winArrow=win&&!ctrl&&(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT
                ||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN);
        boolean winDigit=win&&key>=KeyEvent.KEYCODE_1&&key<=KeyEvent.KEYCODE_9;
        boolean winZ=win&&key==KeyEvent.KEYCODE_Z&&chromeEnabled; // M-07(4)
        boolean ctrlD=ctrl&&!alt&&!shift&&!win&&key==KeyEvent.KEYCODE_D;
        boolean handledShortcut=f11||altSpace||altF11||altTab||altF4||winArrow||winCtrlDesktop||winDigit||winZ
                ||win&&(key==KeyEvent.KEYCODE_D||key==KeyEvent.KEYCODE_A||key==KeyEvent.KEYCODE_N
                ||key==KeyEvent.KEYCODE_I||key==KeyEvent.KEYCODE_TAB
                ||key==KeyEvent.KEYCODE_T||key==KeyEvent.KEYCODE_MOVE_HOME)
                ||ctrlD||key==KeyEvent.KEYCODE_SYSRQ;
        boolean needsController = f11 || altF4 || altSpace || winArrow || winCtrlDesktop || winDigit
                || ctrlD || key == KeyEvent.KEYCODE_SYSRQ
                || (win && (key == KeyEvent.KEYCODE_D || key == KeyEvent.KEYCODE_T || key == KeyEvent.KEYCODE_MOVE_HOME || key == KeyEvent.KEYCODE_Z));
        if (controller == null && needsController) return false;
        boolean needsTaskView = altTab || (win && key == KeyEvent.KEYCODE_TAB);
        if (needsTaskView && DockService.taskView() == null) return false;
        if(action!=KeyEvent.ACTION_DOWN||!handledShortcut)return false;
        swallowedKeys.add(key);
        if(event.getRepeatCount()>0)return true;
        if(f11&&controller!=null){if(altF11)controller.exitDockedPair();else controller.toggleFocusedFullscreen();return true;}
        if(altSpace){ChromeManager.showSystemMenuForFocused();return true;}
        if(altTab){if(DockService.taskView()!=null)DockService.taskView().show();return true;}
        if(altF4&&controller!=null){Win focused=controller.focusedWindow();if(focused!=null)controller.close(focused);return true;}
        if(key==KeyEvent.KEYCODE_SYSRQ&&controller!=null){
            controller.captureScreenshot(result->android.widget.Toast.makeText(this,
                    result.optBoolean("ok",false)?(result.optBoolean("fallback",false)?"Снимок сохранён во временную папку (нет доступа к галерее): ":"Снимок сохранён: ")+result.optString("path"):"Ошибка снимка: "+result.optString("error"),
                    android.widget.Toast.LENGTH_LONG).show());return true;
        }
        if(controller!=null&&winCtrlDesktop){controller.switchVirtualDesktop(controller.currentDesktopId()+(key==KeyEvent.KEYCODE_DPAD_RIGHT?1:-1));return true;}
        if(controller!=null&&winArrow){
            switch(key){case KeyEvent.KEYCODE_DPAD_LEFT:controller.keyboardSnap(true);break;case KeyEvent.KEYCODE_DPAD_RIGHT:controller.keyboardSnap(false);break;case KeyEvent.KEYCODE_DPAD_UP:controller.keyboardMaximizeUp();break;case KeyEvent.KEYCODE_DPAD_DOWN:controller.keyboardRestoreDown();break;}return true;
        }
        if(win&&winDigit&&controller!=null){controller.focusTaskbarIndex(key-KeyEvent.KEYCODE_1);return true;}
        if(ctrlD){if(controller!=null)controller.toggleShowDesktop();return true;}
        if(win){
            switch(key){
                case KeyEvent.KEYCODE_D:if(controller!=null)controller.toggleShowDesktop();return true;
                case KeyEvent.KEYCODE_A:case KeyEvent.KEYCODE_N:ActionCenterOverlay.showSingleton(this);return true;
                case KeyEvent.KEYCODE_I:startActivity(new android.content.Intent(this,LauncherSettingsActivity.class).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));return true;
                case KeyEvent.KEYCODE_TAB:if(DockService.taskView()!=null)DockService.taskView().show();return true;
                case KeyEvent.KEYCODE_Z:ChromeManager.showLayoutsForFocused();return true;
                case KeyEvent.KEYCODE_T:if(controller!=null)controller.cycleTaskbarWindow();return true;
                case KeyEvent.KEYCODE_MOVE_HOME:if(controller!=null){Win focused=controller.focusedWindow();if(focused!=null)controller.aeroShake(focused);}return true;
            }
        }
        return false;
    }

    @Override public void onInterrupt() { }
}
