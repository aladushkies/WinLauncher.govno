package ru.winlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Snap layouts, Assist, shared dividers and cell swapping for freeform tasks. */
public final class SnapController {
    public enum Layout { TWO_HALF, LEFT_66, RIGHT_66, THREE_MAIN, THREE_COLUMNS, FOUR_GRID }
    private static volatile SnapController active;
    public static SnapController active() { return active; }

    public static final class Group {
        public final String id;
        public final Layout layout;
        public final Map<Integer, RectF> cells = new LinkedHashMap<>();
        public final Map<Integer, Integer> tasks = new LinkedHashMap<>();
        Group(String id, Layout layout) { this.id=id; this.layout=layout; }
    }

    private final Context context;
    private final WindowController controller;
    private final WindowManager wm;
    private final float density;
    private final LauncherPrefs prefs; // S5: one instance instead of a new SharedPreferences per call
    private final Map<String, Group> groups = new HashMap<>();
    private final List<View> dividerViews = new ArrayList<>();
    private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
    private View groupHintView;
    private final Runnable hideGroupHint;
    private Assist assist;
    private PopupWindow layoutsPopup;
    private Group activeDividerGroup;
    private boolean fullscreenSuppressed; // H-05: dividers hidden while a fullscreen window is visible
    private View activeDividerView;
    private WindowManager.LayoutParams activeDividerParams;
    private boolean activeDividerVertical;
    private float activeDividerFrom,activeDividerTo;
    private int dividerOrientation;
    private float dividerOldFraction;

    public SnapController(Context context, WindowController controller) {
        this.context=context; this.controller=controller;
        this.wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        this.hideGroupHint=()->{if(groupHintView!=null)try{wm.removeView(groupHintView);}catch(RuntimeException ignored){}groupHintView=null;};
        this.density=context.getResources().getDisplayMetrics().density;
        this.prefs=new LauncherPrefs(context);
        active=this;
    }

    public Group group(String id) { return groups.get(id); }
    public List<Win> groupMembers(Win member){
        ArrayList<Win> result=new ArrayList<>();
        Group g=member==null||member.snapGroupId==null?null:groups.get(member.snapGroupId);
        if(g==null){if(member!=null)result.add(member);return result;}
        for(Integer cell:g.tasks.keySet()){Win w=controller.get(g.tasks.get(cell));if(w!=null)result.add(w);}
        return result;
    }
    public int groupSize(Win member){return groupMembers(member).size();}
    public void restorePersistedMember(Win w,String id,Layout layout,int cell,RectF savedCell){
        if(w==null||id==null||id.length()==0||layout==null)return;
        List<RectF> cells=template(layout);if(cell<0||cell>=cells.size())return;
        Group g=groups.get(id);
        if(g==null){g=new Group(id,layout);for(int i=0;i<cells.size();i++)g.cells.put(i,new RectF(cells.get(i)));groups.put(id,g);}
        if(g.layout!=layout)return;
        Integer old=g.tasks.get(cell);if(old!=null&&old!=w.taskId)return;
        if(savedCell!=null&&savedCell.width()>0&&savedCell.height()>0)g.cells.put(cell,new RectF(savedCell));
        g.tasks.put(cell,w.taskId);w.snapGroupId=id;w.snapCell=cell;
    }
    public void refreshGroup(String id){Group g=groups.get(id);if(g!=null)showDividers(g);}
    public void minimizeGroup(Win member){controller.minimize(member);}
    private void showGroupHint(Group g){
        if(g.tasks.size()<2)return;
        ui.removeCallbacks(hideGroupHint);hideGroupHint.run();
        StringBuilder text=new StringBuilder("Snap group · ");int count=0;
        for(Integer id:g.tasks.values()){Win w=controller.get(id);if(w==null)continue;if(count++>0)text.append(" + ");text.append(w.title==null?"Окно":w.title);}
        boolean dark=prefs.darkTheme();
        TextView hint=new TextView(context);hint.setText(text.toString());hint.setTextColor(UiTokens.textOnSurface(dark));hint.setTextSize(UiTokens.TEXT_BUTTON);hint.setPadding(dp(14),dp(8),dp(14),dp(8));
        GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff2b2b2b:0xfff9f9f9);bg.setCornerRadius(0);bg.setStroke(dp(1),dark?0xff777777:0xff888888);hint.setBackground(bg);
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=controller.topInset()+dp(52);p.setTitle("WinLauncher Snap group hint");
        try{wm.addView(hint,p);groupHintView=hint;ui.postDelayed(hideGroupHint,1600L);}catch(RuntimeException ignored){groupHintView=null;}
    }
    public Rect boundsFor(String groupId,int cell){Group g=groups.get(groupId);RectF r=g==null?null:g.cells.get(cell);return r==null?null:cellRect(r,controller.workArea());}
    public void releaseActive() { if(active==this)active=null; dismissAssist();hidePreview();removeDividers();ui.removeCallbacks(hideGroupHint);hideGroupHint.run(); }

    public void settingsChanged(){
        Rect work=controller.workArea();
        for(Group g:new ArrayList<>(groups.values()))for(Map.Entry<Integer,Integer> e:new ArrayList<>(g.tasks.entrySet())){
            Win w=controller.get(e.getValue());RectF cell=g.cells.get(e.getKey());if(w!=null&&cell!=null&&w.state!=Win.State.MINIMIZED)controller.applySnapBounds(w,cellRect(cell,work),g.id,e.getKey());
        }
        for(Group g:new ArrayList<>(groups.values()))showDividers(g);
    }
    public void displayChanged(boolean orientationChanged){
        Rect work=controller.workArea();
        for(Group g:new ArrayList<>(groups.values())){
            boolean hasVisibleMember=false;for(Integer id:g.tasks.values()){Win w=controller.get(id);if(w!=null&&w.visible&&w.state!=Win.State.MINIMIZED){hasVisibleMember=true;break;}}
            if(!hasVisibleMember)continue;
            if(orientationChanged){List<RectF> cells=template(g.layout);for(int i=0;i<cells.size();i++)g.cells.put(i,new RectF(cells.get(i)));}
            for(Map.Entry<Integer,Integer> e:new ArrayList<>(g.tasks.entrySet())){
                // S42: after a rotation the cell map can briefly lack an index; never cellRect(null).
                Win w=controller.get(e.getValue());RectF cell=g.cells.get(e.getKey());
                if(w!=null&&cell!=null&&w.state!=Win.State.MINIMIZED)controller.applySnapBounds(w,cellRect(cell,work),g.id,e.getKey());
            }
        }
        for(Group g:new ArrayList<>(groups.values()))showDividers(g);
    }

    public List<Layout> layouts() {
        ArrayList<Layout> out=new ArrayList<>();
        out.add(Layout.TWO_HALF); out.add(Layout.LEFT_66); out.add(Layout.RIGHT_66);
        out.add(Layout.THREE_MAIN); out.add(Layout.THREE_COLUMNS); out.add(Layout.FOUR_GRID);
        return out;
    }

    public void snapHalf(Win w, boolean left) { snap(w, Layout.TWO_HALF, left ? 0 : 1, true); }
    public boolean snapAt(Win w,float x,float y) {
        Group current=w.snapGroupId==null?null:groups.get(w.snapGroupId);
        Rect work=controller.workArea();
        if(current!=null) {
            float nx=(x-work.left)/Math.max(1,work.width()), ny=(y-work.top)/Math.max(1,work.height());
            for(Map.Entry<Integer,RectF> e:current.cells.entrySet()) if(e.getKey()!=w.snapCell&&e.getValue().contains(nx,ny)) {
                snap(w,current.layout,e.getKey(),false); return true;
            }
        }
        int edge=dp(prefs.mouseProfile()?12:24), corner=dp(96);
        boolean left=x<=work.left+edge,right=x>=work.right-edge,top=y<=work.top+edge,bottom=y>=work.bottom-edge;
        if(left&&y<=work.top+corner){snap(w,Layout.FOUR_GRID,0,true);return true;}
        if(right&&y<=work.top+corner){snap(w,Layout.FOUR_GRID,1,true);return true;}
        if(left&&y>=work.bottom-corner){snap(w,Layout.FOUR_GRID,2,true);return true;}
        if(right&&y>=work.bottom-corner){snap(w,Layout.FOUR_GRID,3,true);return true;}
        if(left){snap(w,Layout.TWO_HALF,0,true);return true;}
        if(right){snap(w,Layout.TWO_HALF,1,true);return true;}
        if(top&&x>work.left+corner&&x<work.right-corner)return false;
        return false;
    }
    /** Snap commits on release; layout indicators are drawn with opaque, solid-color surfaces. */
    public void updatePreview(Win w,float x,float y) { }
    public void hidePreview() { }
    public void detach(Win w) {
        if(w.snapGroupId==null)return;
        LauncherPrefs prefs=new LauncherPrefs(context);
        Group g=groups.get(w.snapGroupId);
        if(g!=null){
            // H-02: a deferred detach (WC2-11) may run after another window already took
            // over this cell; removing by cell would evict the new owner.
            Integer currentOwner=g.tasks.get(w.snapCell);
            if(currentOwner!=null&&currentOwner==w.taskId)g.tasks.remove(w.snapCell);
            if(g.tasks.size()<2){
                for(Integer id:new ArrayList<>(g.tasks.values())){
                    Win survivor=controller.get(id);if(survivor!=null)detachFromGroup(survivor,prefs,true);
                }
                groups.remove(g.id);showDividers(g);
            }else showDividers(g);
        }
        // A minimized former member must not retain a SNAPPED restore-state after its cell is gone.
        detachFromGroup(w,prefs,false);
    }
    private void detachFromGroup(Win member,LauncherPrefs prefs,boolean normalizeVisibleSnap){
        member.snapGroupId=null;member.snapCell=-1;
        if(member.state==Win.State.SNAPPED&&normalizeVisibleSnap){
            member.state=Win.State.NORMAL;
            if(member.hasUsableBounds())member.normalBounds.set(member.bounds);
        }else if(member.state==Win.State.MINIMIZED&&member.stateBeforeMinimize==Win.State.SNAPPED){
            Rect saved=member.minimizeRestoreBounds.width()>0&&member.minimizeRestoreBounds.height()>0
                    ?member.minimizeRestoreBounds:member.bounds;
            if(saved.width()>0&&saved.height()>0)member.normalBounds.set(saved);
            member.stateBeforeMinimize=Win.State.NORMAL;
        }
        prefs.clearSavedSnapGroup(member.taskId);
    }
    public void snap(Win w, Layout layout, int cell, boolean assistAfter) {
        List<RectF> template=template(layout);
        if (cell<0||cell>=template.size()) return;
        Group previous = w.snapGroupId==null ? null : groups.get(w.snapGroupId);
        Group group = previous;
        if(previous==null||previous.layout!=layout){
            group=null;
            for(Group candidate:groups.values())if(candidate.layout==layout&&sameDesktop(candidate,w.desktopId)&&(candidate.tasks.containsKey(cell)||findFreeCell(candidate)>=0)){group=candidate;break;}
        }
        if(group!=null&&previous!=null&&group!=previous)detach(w);
        if (group==null || group.layout!=layout) {
            if (previous!=null) detach(w);
            w.snapGroupId=null; w.snapCell=-1;
            group=new Group(UUID.randomUUID().toString(),layout); groups.put(group.id,group);
            for (int i=0;i<template.size();i++) group.cells.put(i,new RectF(template.get(i)));
        }
        Integer occupant=group.tasks.get(cell);
        int oldCell=w.snapGroupId!=null && w.snapGroupId.equals(group.id) ? w.snapCell : -1;
        if (occupant!=null && occupant!=w.taskId) {
            Win other=controller.get(occupant);
            if (other!=null&&other.state==Win.State.MINIMIZED) {
                detach(other);
                if(groups.get(group.id)!=group){group=new Group(UUID.randomUUID().toString(),layout);groups.put(group.id,group);for(int i=0;i<template.size();i++)group.cells.put(i,new RectF(template.get(i)));}
            }else if(other!=null){
                int swap=oldCell>=0?oldCell:findFreeCell(group);
                if(swap>=0){
                    group.tasks.put(swap,other.taskId);other.snapGroupId=group.id;other.snapCell=swap;
                    group.tasks.put(cell,w.taskId);w.snapGroupId=group.id;w.snapCell=cell;
                    applyCell(other,group,swap);applyCell(w,group,cell);showDividers(group);showGroupHint(group);return;
                }
                Rect restore=other.hasUsableBounds()?new Rect(other.bounds):(other.normalBounds.width()>0?new Rect(other.normalBounds):new Rect(controller.workArea()));
                // H-02: clear the membership before the restore, so a deferred detach
                // cannot remove the new owner's cell afterwards.
                other.snapGroupId=null;other.snapCell=-1;
                controller.restoreToNormal(other,restore);
                if(groups.get(group.id)!=group){group=new Group(UUID.randomUUID().toString(),layout);groups.put(group.id,group);for(int i=0;i<template.size();i++)group.cells.put(i,new RectF(template.get(i)));}
            }
        }
        if (oldCell>=0 && oldCell!=cell) group.tasks.remove(oldCell);
        group.tasks.put(cell,w.taskId); w.snapGroupId=group.id; w.snapCell=cell;
        applyCell(w,group,cell);
        showDividers(group);
        showGroupHint(group);
        if (assistAfter && findFreeCell(group)>=0) showAssist(group);
    }

    public void swapIntoCell(Win w, String groupId, int cell) {
        Group g=groups.get(groupId); if (g!=null) snap(w,g.layout,cell,true);
    }

    // BUG-10: a snap group must never mix windows from different virtual desktops.
    private boolean sameDesktop(Group g, int desktopId) {
        for (Integer id : g.tasks.values()) {
            Win m = controller.get(id);
            if (m != null && m.desktopId != desktopId) return false;
        }
        return true;
    }

    private int findFreeCell(Group g) {
        for (int i=0;i<g.cells.size();i++) if (!g.tasks.containsKey(i)) return i;
        return -1;
    }

    private void applyCell(Win w, Group g, int cell) {
        Rect px=cellRect(g.cells.get(cell),controller.workArea());
        controller.applySnapBounds(w,px,g.id,cell);
    }

    public Rect cellRect(Layout layout,int cell) { return cellRect(template(layout).get(cell),controller.workArea()); }
    private Rect cellRect(RectF c, Rect work) {
        final int marginPx=4;
        final int gap=prefs.snapGapPx();
        int aw=Math.max(1,work.width()-2*marginPx), ah=Math.max(1,work.height()-2*marginPx);
        int l=work.left+marginPx+Math.round(c.left*aw), t=work.top+marginPx+Math.round(c.top*ah);
        int r=work.left+marginPx+Math.round(c.right*aw), b=work.top+marginPx+Math.round(c.bottom*ah);
        int before=(gap+1)/2, after=gap/2;
        if(c.left>0)l+=before; if(c.top>0)t+=before;
        if(c.right<1)r-=after; if(c.bottom<1)b-=after;
        return new Rect(l,t,r,b);
    }

    private List<RectF> template(Layout layout) {
        ArrayList<RectF> a=new ArrayList<>();
        switch(layout) {
            case TWO_HALF: a.add(new RectF(0,0,.5f,1)); a.add(new RectF(.5f,0,1,1)); break;
            case LEFT_66: a.add(new RectF(0,0,.66f,1)); a.add(new RectF(.66f,0,1,1)); break;
            case RIGHT_66: a.add(new RectF(0,0,.34f,1)); a.add(new RectF(.34f,0,1,1)); break;
            case THREE_MAIN: a.add(new RectF(0,0,.5f,1)); a.add(new RectF(.5f,0,1,.5f)); a.add(new RectF(.5f,.5f,1,1)); break;
            case THREE_COLUMNS: a.add(new RectF(0,0,.25f,1)); a.add(new RectF(.25f,0,.75f,1)); a.add(new RectF(.75f,0,1,1)); break;
            case FOUR_GRID: a.add(new RectF(0,0,.5f,.5f)); a.add(new RectF(.5f,0,1,.5f)); a.add(new RectF(0,.5f,.5f,1)); a.add(new RectF(.5f,.5f,1,1)); break;
        }
        android.util.DisplayMetrics displayMetrics=new android.util.DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(displayMetrics);
        if(displayMetrics.heightPixels>displayMetrics.widthPixels){
            ArrayList<RectF> transposed=new ArrayList<>();
            for(RectF r:a)transposed.add(new RectF(r.top,r.left,r.bottom,r.right));
            return transposed;
        }
        return a;
    }

    public void showLayouts(Win w, View anchor) {
        final PopupWindow popup=new PopupWindow(context);
        boolean dark=new LauncherPrefs(context).darkTheme();
        LinearLayout panel=new LinearLayout(context); panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(8),dp(8),dp(8),dp(8));
        GradientDrawable bg=new GradientDrawable(); bg.setColor(UiTokens.surface(dark)); bg.setCornerRadius(UiTokens.RADIUS_MEDIUM); bg.setStroke(dp(1),UiTokens.outline(dark));
        panel.setBackground(bg); 
        Layout[] ls=Layout.values();
        for(int row=0;row<2;row++) {
            LinearLayout line=new LinearLayout(context); line.setOrientation(LinearLayout.HORIZONTAL);
            for(int col=0;col<3;col++) {
                Layout l=ls[row*3+col];
                LayoutPreview preview=new LayoutPreview(context,l,dark);
                LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(74),dp(52)); p.setMargins(dp(3),dp(3),dp(3),dp(3));
                preview.setOnHoverListener((v,e)->{preview.updateHover(e);return true;});
                preview.setOnTouchListener((v,e)->{
                    if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){preview.setPressedCell(preview.hitCell(e.getX(),e.getY()));return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_CANCEL){preview.setPressedCell(-1);return true;}
                    if(e.getActionMasked()==MotionEvent.ACTION_UP){
                        // M-05: a release outside the preview must not snap to a guessed cell.
                        boolean inside=e.getX()>=0&&e.getX()<=preview.getWidth()&&e.getY()>=0&&e.getY()<=preview.getHeight();
                        int picked=preview.hitCell(e.getX(),e.getY());preview.setPressedCell(-1);
                        popup.dismiss();if(inside)snap(w,l,picked,true);return true;
                    }
                    return true;
                });
                line.addView(preview,p);
            }
            panel.addView(line);
        }
        popup.setContentView(panel); popup.setWidth(dp(260)); popup.setHeight(dp(142)); popup.setOutsideTouchable(true); popup.setFocusable(false); popup.setBackgroundDrawable(bg);
        // S13: a second Show Layouts must not stack popups; the previous one is replaced.
        if(layoutsPopup!=null)try{layoutsPopup.dismiss();}catch(RuntimeException ignored){}layoutsPopup=popup;
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT); // M-02
        try { popup.showAsDropDown(anchor, -dp(108), 0); } catch(RuntimeException ignored) { }
    }

    public void showAssist(Group group) {
        dismissAssist();
        List<Integer> free=new ArrayList<>();
        for(int i=0;i<group.cells.size();i++) if(!group.tasks.containsKey(i)) free.add(i);
        if(free.isEmpty()) return;
        List<Win> candidates=new ArrayList<>();
        for(Win w:controller.snapshot()) if(!group.tasks.containsValue(w.taskId)&&w.desktopId==controller.currentDesktopId()&&w.stackId==controller.freeformStackId()&&w.state!=Win.State.MINIMIZED&&w.state!=Win.State.FULLSCREEN) candidates.add(w);
        if(candidates.isEmpty()) return;
        assist=new Assist(group,free,candidates); assist.show();
    }

    // M-05: an Assist whose addView failed has no dim; Esc must not be swallowed forever.
    public boolean hasAssist() { return assist != null && assist.dim != null; }
    // BUG-20: the layouts popup is not focusable, so KeyService must close it on Escape.
    public boolean hasLayoutsPopup() { return layoutsPopup != null && layoutsPopup.isShowing(); }
    public boolean dismissLayoutsForEscape() {
        if (!hasLayoutsPopup()) return false;
        try { layoutsPopup.dismiss(); } catch (RuntimeException ignored) { }
        layoutsPopup = null;
        return true;
    }
    public void cancelAssist() { dismissAssist(); }
    private void dismissAssist() { if(assist!=null){assist.dismiss();assist=null;} }

    public void resizeSnapped(Win w,int direction,int dx,int dy,Rect initial) {
        Group g=w.snapGroupId==null?null:groups.get(w.snapGroupId);
        if(g==null){ controller.requestMove(w,initial); return; }
        RectF cell=g.cells.get(w.snapCell); if(cell==null)return;
        Rect work=controller.workArea();
        RectF original=new RectF(initial.left,initial.top,initial.right,initial.bottom);
        float baseL=(float)(original.left-work.left)/Math.max(1,work.width());
        float baseR=(float)(original.right-work.left)/Math.max(1,work.width());
        float baseT=(float)(original.top-work.top)/Math.max(1,work.height());
        float baseB=(float)(original.bottom-work.top)/Math.max(1,work.height());
        if(direction==1||direction==5||direction==7) {
            float current=cell.left, target=baseL+(float)dx/Math.max(1,work.width()); moveDivider(g,true,current,target);
        } else if(direction==2||direction==6||direction==8) {
            float current=cell.right, target=baseR+(float)dx/Math.max(1,work.width()); moveDivider(g,true,current,target);
        }
        if(direction==3||direction==5||direction==6) {
            float current=cell.top, target=baseT+(float)dy/Math.max(1,work.height()); moveDivider(g,false,current,target);
        } else if(direction==4||direction==7||direction==8) {
            float current=cell.bottom, target=baseB+(float)dy/Math.max(1,work.height()); moveDivider(g,false,current,target);
        }
    }

    public void finishDividerDrag() {
        if(activeDividerGroup==null)return;
        Group finished=activeDividerGroup;
        for(Integer id:finished.tasks.values()) { Win w=controller.get(id); if(w!=null&&w.visible&&w.state!=Win.State.MINIMIZED) controller.commitMove(w); }
        activeDividerGroup=null;activeDividerView=null;activeDividerParams=null;
        showDividers(finished);
    }

    private float moveDivider(Group g,boolean vertical,float old,float next) {
        // BUG-20: dragging an outer edge (divider already at the screen boundary) must not
        // shift the whole group away from the screen edge.
        if(old<=0.01f||old>=0.99f)return old;
        Rect work=controller.workArea();float min=vertical?(float)dp(240)/Math.max(1,work.width()):(float)dp(160)/Math.max(1,work.height());
        float lower=0f,upper=1f;
        for(RectF c:g.cells.values()){
            if(vertical){if(Math.abs(c.right-old)<.01f)lower=Math.max(lower,c.left+min);if(Math.abs(c.left-old)<.01f)upper=Math.min(upper,c.right-min);}
            else{if(Math.abs(c.bottom-old)<.01f)lower=Math.max(lower,c.top+min);if(Math.abs(c.top-old)<.01f)upper=Math.min(upper,c.bottom-min);}
        }
        if(lower>upper)return old;next=Math.max(lower,Math.min(upper,next));
        boolean found=false;
        for(RectF c:g.cells.values()) {
            if(vertical) {
                if(Math.abs(c.right-old)<.01f){c.right=next;found=true;}
                if(Math.abs(c.left-old)<.01f){c.left=next;found=true;}
            } else {
                if(Math.abs(c.bottom-old)<.01f){c.bottom=next;found=true;}
                if(Math.abs(c.top-old)<.01f){c.top=next;found=true;}
            }
        }
        if(!found)return old;
        activeDividerGroup=g;dividerOrientation=vertical?1:2;dividerOldFraction=old;
        for(Map.Entry<Integer,Integer> e:g.tasks.entrySet()){Win win=controller.get(e.getValue());if(win!=null&&win.visible&&win.state!=Win.State.MINIMIZED)controller.requestMove(win,cellRect(g.cells.get(e.getKey()),work));}
        if(activeDividerView!=null&&activeDividerParams!=null){
            if(vertical)activeDividerParams.x=work.left+Math.round(next*work.width())-dp(4);
            else activeDividerParams.y=work.top+Math.round(next*work.height())-dp(4);
            try{wm.updateViewLayout(activeDividerView,activeDividerParams);}catch(RuntimeException ignored){}
        }
        showDividers(g);
        return next;
    }

    /** H-05 / I-10: hide snap dividers above any fullscreen window OR when no snap group
     *  currently has two visible non-minimized members (e.g. while Home is on top). */
    public void syncVisibility(List<Win> windows) {
        boolean fs=false;
        for(Win w:windows)if(w.visible&&w.state==Win.State.FULLSCREEN){fs=true;break;}
        if(fs){fullscreenSuppressed=true;removeDividers();return;}
        boolean needDividers=false;
        for(Group g:groups.values()){
            int vis=0;
            for(Integer id:g.tasks.values()){Win m=controller.get(id);if(m!=null&&m.visible&&m.state!=Win.State.MINIMIZED)vis++;}
            if(vis>=2){needDividers=true;break;}
        }
        if(fullscreenSuppressed){fullscreenSuppressed=false;if(needDividers)showDividers(null);return;}
        if(!needDividers&&!dividerViews.isEmpty()){removeDividers();}
        else if(needDividers&&dividerViews.isEmpty()&&activeDividerGroup==null){showDividers(null);}
    }

    private void showDividers(Group changed) {
        if(activeDividerGroup!=null)return; // preserve the active touch target until ACTION_UP/CANCEL
        removeDividers();
        if(fullscreenSuppressed)return; // H-05
        for(Group group:new ArrayList<>(groups.values())){
            List<Integer> occupied=new ArrayList<>();
            for(Map.Entry<Integer,Integer> entry:group.tasks.entrySet()){Win member=controller.get(entry.getValue());if(member!=null&&member.visible&&member.state!=Win.State.MINIMIZED)occupied.add(entry.getKey());}
            for(int i=0;i<occupied.size();i++)for(int j=i+1;j<occupied.size();j++){ 
                int a=occupied.get(i),b=occupied.get(j);RectF x=group.cells.get(a),y=group.cells.get(b);
                if(Math.abs(x.right-y.left)<.01f||Math.abs(y.right-x.left)<.01f){
                    float from=Math.max(x.top,y.top),to=Math.min(x.bottom,y.bottom);
                    if(to-from>.01f)addDivider(group,true,Math.abs(x.right-y.left)<.01f?x.right:y.right,from,to);
                }
                if(Math.abs(x.bottom-y.top)<.01f||Math.abs(y.bottom-x.top)<.01f){
                    float from=Math.max(x.left,y.left),to=Math.min(x.right,y.right);
                    if(to-from>.01f)addDivider(group,false,Math.abs(x.bottom-y.top)<.01f?x.bottom:y.bottom,from,to);
                }
            }
        }
    }

    private void addDivider(Group g,boolean vertical,float position,float from,float to) {
        int thick=dp(8); Rect work=controller.workArea();
        DividerView v=new DividerView(context,new LauncherPrefs(context).darkTheme()); // U-08
        final float start=position;final WindowManager.LayoutParams[] holder=new WindowManager.LayoutParams[1];
        v.setOnHoverListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE){v.active=true;v.invalidate();}else if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){v.active=false;v.invalidate();}return false;});
        v.setOnTouchListener(new View.OnTouchListener(){float down; float last;
            public boolean onTouch(View view,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){v.active=true;v.invalidate();down=vertical?e.getRawX():e.getRawY();last=start;activeDividerGroup=g;activeDividerView=v;activeDividerParams=holder[0];activeDividerVertical=vertical;activeDividerFrom=from;activeDividerTo=to;return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_MOVE){float now=vertical?e.getRawX():e.getRawY();float delta=(now-down)/(vertical?Math.max(1,work.width()):Math.max(1,work.height()));float next=start+delta;last=moveDivider(g,vertical,last,next);return true;}
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){finishDividerDrag();return true;} return true;
            }});
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(vertical?thick:Math.max(1,Math.round((to-from)*work.width())),vertical?Math.max(1,Math.round((to-from)*work.height())):thick,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
        holder[0]=p;
        if(vertical){p.x=work.left+Math.round(position*work.width())-thick/2;p.y=work.top+Math.round(from*work.height());}
        else{p.x=work.left+Math.round(from*work.width());p.y=work.top+Math.round(position*work.height())-thick/2;}
        p.gravity=Gravity.TOP|Gravity.LEFT;
        try{wm.addView(v,p);dividerViews.add(v);}catch(RuntimeException ignored){}
    }
    private void removeDividers(){for(View v:new ArrayList<>(dividerViews))try{wm.removeView(v);}catch(RuntimeException ignored){}dividerViews.clear();}
    /** Dividers are rebuilt from group membership, so the last group leaving must drop them too. */
    public void clearDividersIfEmpty(){if(groups.isEmpty())removeDividers();}

    private int dp(float value){return(int)(value*density+.5f);}

    private final class Assist {
        final Group group; final List<Integer> freeCells; final List<Win> candidates;
        View dim; WindowManager.LayoutParams lp;
        Assist(Group g,List<Integer> cells,List<Win> wins){group=g;freeCells=cells;candidates=wins;}
        void show(){
            boolean dark=new LauncherPrefs(context).darkTheme();
            LinearLayout root=new LinearLayout(context);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setBackgroundColor(UiTokens.pageSoft(dark));
            TextView heading=new TextView(context);
            heading.setText("Snap Group · "+group.tasks.size()+" окно(а) · выберите следующее окно");
            // BUG-02 (regression of D-09): this comment used to sit mid-line and commented out
            // setTextSize, setPadding and addView, so the heading never appeared at all.
            // D-09: was Color.WHITE on a near-white background in the light theme.
            heading.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);
            heading.setTextSize(18);
            heading.setPadding(dp(12),dp(10),dp(12),dp(10));
            root.addView(heading);
            LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.HORIZONTAL);card.setPadding(dp(8),dp(8),dp(8),dp(8));
            GradientDrawable bg=new GradientDrawable();bg.setColor(UiTokens.surfaceAssist(dark));bg.setCornerRadius(UiTokens.RADIUS_MEDIUM);card.setBackground(bg);
            // BUG-20: with 6+ candidate windows the card row overflowed the screen; scroll it.
            android.widget.HorizontalScrollView cardScroll=new android.widget.HorizontalScrollView(context);
            cardScroll.setHorizontalScrollBarEnabled(false);
            cardScroll.addView(card);
            for(Win w:candidates){
                LinearLayout item=new LinearLayout(context);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);item.setPadding(dp(8),dp(6),dp(8),dp(6));
                GradientDrawable itemNormal=new GradientDrawable();itemNormal.setColor(UiTokens.surfaceAssist(dark));itemNormal.setCornerRadius(UiTokens.RADIUS_MEDIUM);
                GradientDrawable itemHover=new GradientDrawable();itemHover.setColor(UiTokens.surfaceHoverAccent(dark));itemHover.setCornerRadius(UiTokens.RADIUS_MEDIUM);
                GradientDrawable itemPressed=new GradientDrawable();itemPressed.setColor(UiTokens.surfacePressedAccent(dark));itemPressed.setCornerRadius(UiTokens.RADIUS_MEDIUM);item.setBackground(itemNormal);
                item.setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER)v.setBackground(itemHover);else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)v.setBackground(itemNormal);return false;});
                item.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.setBackground(itemPressed);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?itemHover:itemNormal);return false;});
                ImageView preview=new ImageView(context);preview.setScaleType(ImageView.ScaleType.CENTER_INSIDE);preview.setBackgroundColor(UiTokens.surfaceWell(dark));
                try{preview.setImageDrawable(context.getPackageManager().getApplicationIcon(w.packageName));}catch(Exception ignored){}
                item.addView(preview,new LinearLayout.LayoutParams(dp(72),dp(72)));
                TextView choose=new TextView(context);choose.setText(w.title==null?"Окно":w.title);choose.setGravity(Gravity.CENTER);choose.setTextColor(UiTokens.textPrimary(dark));item.addView(choose);
                item.setOnClickListener(v->{
                    // M-05: the group may have been dissolved while the Assist was open.
                    if(groups.get(group.id)!=group){dismissAssist();return;}
                    if(controller.get(w.taskId)!=w||w.state==Win.State.MINIMIZED||w.state==Win.State.FULLSCREEN){dismissAssist();return;}
                    int cell=findFreeCell(group);if(cell<0){dismissAssist();return;}
                    detach(w);group.tasks.put(cell,w.taskId);w.snapGroupId=group.id;w.snapCell=cell;applyCell(w,group,cell);dismissAssist();showDividers(group);showGroupHint(group);if(findFreeCell(group)>=0)showAssist(group);
                });
                card.addView(item);
            }
            Button cancel=new Button(context);cancel.setText("Закрыть");cancel.setAllCaps(false);cancel.setTextColor(dark?UiTokens.textOnAccent():UiTokens.textPrimary(dark));
            GradientDrawable cancelBg=new GradientDrawable();cancelBg.setColor(UiTokens.surfaceMuted(dark));cancelBg.setCornerRadius(UiTokens.RADIUS_MEDIUM);
            GradientDrawable cancelHover=new GradientDrawable();cancelHover.setColor(UiTokens.surfaceHoverMuted(dark));cancelHover.setCornerRadius(UiTokens.RADIUS_MEDIUM);
            GradientDrawable cancelPressed=new GradientDrawable();cancelPressed.setColor(UiTokens.surfacePressedMuted(dark));cancelPressed.setCornerRadius(UiTokens.RADIUS_MEDIUM);cancel.setBackground(cancelBg);
            cancel.setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)v.setBackground(cancelHover);else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)v.setBackground(cancelBg);return false;});
            cancel.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.setBackground(cancelPressed);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?cancelHover:cancelBg);return false;});
            cancel.setOnClickListener(v->dismissAssist());root.addView(cardScroll);root.addView(cancel);
            dim=root;root.setFocusableInTouchMode(true);root.setClickable(true);
            root.setOnKeyListener((v,keyCode,event)->{if(keyCode==android.view.KeyEvent.KEYCODE_ESCAPE&&event.getAction()==android.view.KeyEvent.ACTION_UP){dismissAssist();return true;}return false;});
            lp=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE);lp.gravity=Gravity.TOP|Gravity.LEFT;
            root.setOnTouchListener((v,e)->{
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN)return true;
                if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){dismissAssist();return true;}
                return true;
            });
            try{wm.addView(dim,lp);View attached=dim;attached.post(attached::requestFocus);}catch(RuntimeException ignored){dim=null;SnapController.this.assist=null;}
        }
        void dismiss(){if(dim!=null)try{wm.removeView(dim);}catch(RuntimeException ignored){}dim=null;}
    }

    private final class LayoutPreview extends View {
        final Layout layout; final Paint paint=new Paint(3); final boolean dark;
        int hoverCell=-1,pressedCell=-1;
        LayoutPreview(Context c,Layout l,boolean dark){super(c);layout=l;this.dark=dark;setBackgroundColor(UiTokens.surfacePreview(dark));setContentDescription("Макет Snap: "+l.name());}
        int hitCell(float x,float y){
            float pad=dp(4),width=Math.max(1,getWidth()-2*pad),height=Math.max(1,getHeight()-2*pad);
            float nx=Math.max(0,Math.min(.9999f,(x-pad)/width)),ny=Math.max(0,Math.min(.9999f,(y-pad)/height));
            List<RectF> cells=template(layout);for(int i=0;i<cells.size();i++)if(cells.get(i).contains(nx,ny))return i;
            return 0;
        }
        void setPressedCell(int cell){if(pressedCell!=cell){pressedCell=cell;invalidate();}}
        void updateHover(MotionEvent event){
            int action=event.getActionMasked();int cell=(action==MotionEvent.ACTION_HOVER_EXIT)?-1:hitCell(event.getX(),event.getY());
            if(hoverCell!=cell){hoverCell=cell;invalidate();}
        }
        @Override protected void onDraw(Canvas canvas){
            super.onDraw(canvas);float pad=dp(4),width=Math.max(1,getWidth()-2*pad),height=Math.max(1,getHeight()-2*pad),gap=dp(1);
            List<RectF> cells=template(layout);
            for(int i=0;i<cells.size();i++){
                RectF cell=cells.get(i);float left=pad+cell.left*width+gap,top=pad+cell.top*height+gap;
                float right=pad+cell.right*width-gap,bottom=pad+cell.bottom*height-gap;
                boolean highlighted=i==(pressedCell>=0?pressedCell:hoverCell);
                paint.setStyle(Paint.Style.FILL);paint.setColor(highlighted?(pressedCell>=0?UiTokens.accentPressed(false):UiTokens.accentLight()):UiTokens.surfaceCellIdle(dark));
                canvas.drawRect(left,top,right,bottom,paint);
                paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(UiTokens.outlineMuted(dark));
                canvas.drawRect(left,top,right,bottom,paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }
    private final class DividerView extends View {
        final Paint p=new Paint(3); boolean active; final boolean dark;
        DividerView(Context c,boolean d){super(c);dark=d;setBackgroundColor(UiTokens.surfaceCard(d));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);if(!active)return;p.setColor(UiTokens.accent(dark));if(getWidth()>getHeight())c.drawRect(0,getHeight()/2f-dp(1),getWidth(),getHeight()/2f+dp(1),p);else c.drawRect(getWidth()/2f-dp(1),0,getWidth()/2f+dp(1),getHeight(),p);}
    }
}
