package ru.winlauncher;

import android.content.Context;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PointerIcon;
import android.view.View;
import android.view.WindowManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Eight invisible resize hit areas, attached only to the currently focused NORMAL/SNAPPED task. */
public final class ResizeGripManager {
    private static final int[] DIRS = {1,2,3,4,5,6,7,8}; // L,R,T,B,TL,TR,BL,BR
    private final Context context;
    private final WindowManager wm;
    private final WindowController controller;
    private final float density;
    private final Map<Integer, Grip> grips = new HashMap<>();
    private boolean enabled;
    private final LauncherPrefs prefs; // M-04: cached instead of allocating one per update()

    public ResizeGripManager(Context context, WindowController controller) {
        this.context = context.getApplicationContext(); this.controller = controller;
        this.wm = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
        this.density = this.context.getResources().getDisplayMetrics().density;
        this.prefs = new LauncherPrefs(this.context);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) removeAll();
    }

    public void update(List<Win> windows, int activeTaskId) {
        if (!enabled) return;
        Win active = null;
        for (Win w : windows) if (w.taskId == activeTaskId) active = w;
        if (active == null || (active.state != Win.State.NORMAL && active.state != Win.State.SNAPPED)
                || !active.visible || !active.hasUsableBounds()) { removeAll(); return; }
        // Opaque accent grips stay narrow to avoid hiding app content on the low-memory profile.
        int grip = dp(prefs.mouseProfile() ? 4 : 8);
        for (int direction : DIRS) {
            Grip g = grips.get(direction);
            if (g == null) {
                g = new Grip(direction);
                grips.put(direction, g);
                try { wm.addView(g.view, g.params); } catch (RuntimeException e) { grips.remove(direction); return; }
            }
            // M-04: a grip mid-drag keeps its window; the others follow the active one.
            if (g.dragWin == null) g.win = active;
            Win anchor = g.dragWin != null ? g.dragWin : active;
            setBounds(g, anchor.bounds, grip, direction);
        }
    }

    private void setBounds(Grip g, Rect b, int size, int d) {
        int x=b.left, y=b.top, w=b.width(), h=b.height();
        switch (d) {
            case 1: x=b.left-size/2; y=b.top+size; w=size; h=Math.max(1,b.height()-2*size); break;
            case 2: x=b.right-size/2; y=b.top+size; w=size; h=Math.max(1,b.height()-2*size); break;
            case 3: x=b.left+size; y=b.top-size/2; w=Math.max(1,b.width()-2*size); h=size; break;
            case 4: x=b.left+size; y=b.bottom-size/2; w=Math.max(1,b.width()-2*size); h=size; break;
            case 5: x=b.left-size/2; y=b.top-size/2; w=size; h=size; break;
            case 6: x=b.right-size/2; y=b.top-size/2; w=size; h=size; break;
            case 7: x=b.left-size/2; y=b.bottom-size/2; w=size; h=size; break;
            case 8: x=b.right-size/2; y=b.bottom-size/2; w=size; h=size; break;
        }
        g.params.x=x; g.params.y=y; g.params.width=w; g.params.height=h;
        try { if (g.view.getParent()!=null) wm.updateViewLayout(g.view,g.params); } catch (RuntimeException ignored) { }
    }

    private void removeAll() {
        for (Grip g : new ArrayList<>(grips.values())) {
            g.dragWin = null;
            try { if (g.view.getParent()!=null) wm.removeView(g.view); } catch (RuntimeException ignored) { }
        }
        grips.clear();
    }

    private int dp(float v) { return (int)(v*density+0.5f); }

    private final class Grip implements View.OnTouchListener {
        final int direction;
        final View view;
        final WindowManager.LayoutParams params;
        Win win;
        Win dragWin; // M-04: fixed on ACTION_DOWN for the whole gesture
        Rect start = new Rect();
        float downX, downY;
        Grip(int direction) {
            this.direction=direction;
            view = new View(context);
            view.setBackgroundColor(0xff0067c0);
            if (Build.VERSION.SDK_INT >= 24) view.setPointerIcon(PointerIcon.getSystemIcon(context, pointerType(direction)));
            view.setOnTouchListener(this);
            params = new WindowManager.LayoutParams(1,1,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.OPAQUE);
            params.gravity=Gravity.TOP|Gravity.LEFT;
        }
        @Override public boolean onTouch(View v, MotionEvent e) {
            if (e.getActionMasked()==MotionEvent.ACTION_DOWN) {
                if (win == null) return false;
                // M-04: the gesture is bound to this window until UP/CANCEL.
                dragWin = win;
                start.set(dragWin.bounds); downX=e.getRawX(); downY=e.getRawY(); controller.focus(dragWin); return true;
            }
            if (dragWin == null) return false;
            if (e.getActionMasked()==MotionEvent.ACTION_MOVE) {
                Rect next=new Rect(start); int dx=Math.round(e.getRawX()-downX), dy=Math.round(e.getRawY()-downY);
                if (direction==1||direction==5||direction==7) next.left+=dx;
                if (direction==2||direction==6||direction==8) next.right+=dx;
                if (direction==3||direction==5||direction==6) next.top+=dy;
                if (direction==4||direction==7||direction==8) next.bottom+=dy;
                if (next.width()<dp(240)) { if (direction==1||direction==5||direction==7) next.left=next.right-dp(240); else next.right=next.left+dp(240); }
                if (next.height()<dp(160)) { if (direction==3||direction==5||direction==6) next.top=next.bottom-dp(160); else next.bottom=next.top+dp(160); }
                if (dragWin.state==Win.State.SNAPPED && controller.snapController()!=null)
                    controller.snapController().resizeSnapped(dragWin,direction,dx,dy,start);
                else controller.requestMove(dragWin,next);
                return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_UP) {
                Win finished = dragWin; dragWin = null;
                if (finished.state==Win.State.SNAPPED && controller.snapController()!=null) controller.snapController().finishDividerDrag();
                else controller.commitMove(finished);
                return true;
            }
            if (e.getActionMasked()==MotionEvent.ACTION_CANCEL) {
                Win cancelled = dragWin; dragWin = null;
                if (cancelled.state==Win.State.SNAPPED && controller.snapController()!=null) controller.snapController().finishDividerDrag();
                // M-04 / I-09: roll back to the start bounds and commit with mode 0.
                else { controller.requestMove(cancelled,new Rect(start)); controller.commitMove(cancelled); }
                return true;
            }
            return true;
        }
        private int pointerType(int d) {
            if (d==1||d==2) return PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW;
            if (d==3||d==4) return PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW;
            if (d==5||d==8) return PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW;
            return PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW;
        }
    }
}
