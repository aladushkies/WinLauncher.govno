package ru.winlauncher;

import android.content.Context;

import android.content.Intent;

import android.graphics.Rect;

import android.graphics.RectF;

import android.os.Handler;

import android.os.Looper;

import android.view.KeyEvent;

import org.json.JSONArray;

import org.json.JSONObject;

import java.util.ArrayList;

import java.util.Collections;

import java.util.Comparator;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;

import java.util.Set;

import java.util.LinkedHashSet;

import ru.winlauncher.bridge.RootBridge;

/**
 * Small first-pass window state machine. All model mutations and listener callbacks are on the
 * main thread; Binder work is serialized by RootBridge. Freeform/fullscreen IDs are injected;
 * the Samsung docked split uses the current stack-3 profile and requires device validation.
 */

public final class WindowController {
    public static final int DOCKED_STACK_ID=3;
    private static volatile WindowController activeController;
    public static WindowController active() { return activeController; }

    public interface Listener { void onWindowsChanged(List<Win> windows); }
    public interface Result { void onResult(boolean ok, String message); }
    public interface ErrorListener { void onError(String message); }

    private final Context context;
    private final RootBridge root;
    private final LauncherPrefs prefs;
    private final WindowAnimator animator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Win> windows = new LinkedHashMap<>();
    private final Set<Integer> showDesktopRestoreIds = new LinkedHashSet<>();
    private final Set<Integer> pendingMinimizeIds = new LinkedHashSet<>();
    private final Set<Integer> pendingDesktopRestoreIds = new LinkedHashSet<>();
    private final Set<Integer> pendingDesktopPairExitIds = new LinkedHashSet<>();
    private boolean restoreShowDesktopWhenMinimized;
    private final Set<Integer> showDesktopRestoringIds = new LinkedHashSet<>();
    private boolean showDesktopToggleQueued;
    private long showDesktopRestoreStartedAt;
    private final Map<String, String> titleCache = new LinkedHashMap<>();
    private Win keyboardModeTarget;
    private boolean keyboardResizeMode;
    private final Rect keyboardModeStart=new Rect();
    private final List<Listener> listeners = new ArrayList<>();
    private ErrorListener errorListener;
    private SnapController snapController;
    private DockedPairManager dockedPair;
    private final int freeformStackId;
    private final int fullscreenStackId;
    private int screenWidth;
    private int screenHeight;
    private int statusInsetPx = 24;
    private int taskbarHeight = 48;
    private boolean hideStatusBar = true;
    private boolean statusConfigured;
    private boolean monitoring;
    private boolean initialized;
    private boolean restoringSnapGroup;
    private long generation;
    private long nextStatusAttempt;
    private String lastSignature;

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!monitoring) return;
            refreshTasks(null);
            // S1: after a failure back off to one attempt every 8 s instead of hammering the
            // status-bar service on every tick.
            if (!statusConfigured && android.os.SystemClock.uptimeMillis() >= nextStatusAttempt) configureStatusBar();
            main.postDelayed(this, 750L);
        }
    };

    public WindowController(Context context, RootBridge root) {
        this(context, root, 2, 1);
    }

    public WindowController(Context context, RootBridge root, int freeformStackId, int fullscreenStackId) {
        this.context = context.getApplicationContext();
        this.root = root;
        this.prefs = new LauncherPrefs(this.context);
        this.animator = new WindowAnimator(this.context, root);
        this.hideStatusBar = prefs.hideStatusBar();
        this.freeformStackId = freeformStackId;
        this.fullscreenStackId = fullscreenStackId;
        activeController = this;
        updateDisplayGeometry();
        snapController = new SnapController(this.context, this);
        dockedPair = new DockedPairManager(this.context,this);
    }

    public void addListener(Listener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        listener.onWindowsChanged(snapshot());
    }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public void setErrorListener(ErrorListener listener) { errorListener = listener; }

    public void setTaskbarHeight(int px) {
        int value=Math.max(0,px);if(taskbarHeight==value)return;taskbarHeight=value;
        Rect work=workArea();for(Win w:windows.values())if(w.state==Win.State.MAXIMIZED){requestBounds(w,work,0);}
        notifyListeners();
    }
    public boolean hideStatusBar() { return hideStatusBar; }
    // D-03: no cached copy — the cached field was only refreshed from MainActivity, so the
    // setting changed in LauncherSettingsActivity silently did not apply. Read prefs directly,
    // the same way willHandleEscapeFullscreen() reads escapeExitsFullscreen.
    public boolean windowsButtonBehavior() { return prefs.windowsButtonBehavior(); }
    public void setWindowsButtonBehavior(boolean enabled) {
        prefs.setWindowsButtonBehavior(enabled);notifyListeners();
    }
    public void setHideStatusBar(boolean hide) {
        hideStatusBar = hide;
        prefs.setHideStatusBar(hide);
        statusConfigured = false;
        if (monitoring) configureStatusBar();
        Rect work = workArea();
        for (Win w : windows.values()) if (w.state == Win.State.MAXIMIZED) {
            w.bounds.set(work);
            root.resize(w.taskId, work.left, work.top, work.right, work.bottom, 0, null);
        }
        notifyListeners();
    }

    private void configureStatusBar() {
        root.setStatusBarHidden(hideStatusBar, result -> {
            statusConfigured = result.optBoolean("ok", false);
            nextStatusAttempt = statusConfigured ? 0L
                    : android.os.SystemClock.uptimeMillis() + 8000L;
            if (!statusConfigured && errorListener != null)
                errorListener.onError(result.optString("error", "status-bar setup failed"));
        });
    }

    public void captureScreenshot(RootBridge.Callback callback){root.captureScreenshot(callback);}

    public void startMonitoring() {
        if (monitoring) return;
        monitoring = true;
        refreshTasks(null);
        configureStatusBar();
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
        int oldWidth=screenWidth,oldHeight=screenHeight;boolean sizeChanged=oldWidth!=dm.widthPixels||oldHeight!=dm.heightPixels;
        boolean orientationChanged=oldWidth>0&&((oldHeight>oldWidth)!=(dm.heightPixels>dm.widthPixels));
        screenWidth = dm.widthPixels;
        screenHeight = dm.heightPixels;
        statusInsetPx = statusBarHeight(); // D-06: real device inset, not a hardcoded 24 px
        generation++;
        if(snapController!=null&&sizeChanged)snapController.displayChanged(orientationChanged);
        if(dockedPair!=null)dockedPair.onDisplayChanged();
        // H-01: a rotation (or an input-device change) while MainActivity is in the
        // background reaches us only through DockService; refit maximized windows here.
        if(sizeChanged){
            Rect area=workArea();
            for(Win w:snapshot())if(w.state==Win.State.MAXIMIZED)requestBounds(w,area,0);
            notifyListeners();
        }
    }

    public Rect workArea() {
        int top = hideStatusBar ? 0 : statusInsetPx;
        return new Rect(0, top, screenWidth, Math.max(top, screenHeight - taskbarHeight));
    }

    private int statusBarHeight() {
        int id = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? context.getResources().getDimensionPixelSize(id) : 24;
    }
    private int dp(float value) { return (int)(value*context.getResources().getDisplayMetrics().density+0.5f); }

    public List<Win> snapshot() {
        ArrayList<Win> out = new ArrayList<>(windows.values());
        Collections.sort(out, Comparator.comparingInt(w -> w.z));
        return out;
    }

    public Win get(int taskId) { return windows.get(taskId); }
    // WC2-06: per-window operation generation. Async root continuations capture the current
    // value and refuse to mutate the model when a newer operation superseded them or when
    // the Win was removed (a recycled taskId gets a fresh Win, so the identity check fails
    // for stale continuations too).
    // M-03: package-visible so DockedPairManager can bump both pair members on entry.
    long nextOperation(Win w) { return ++w.operationSequence; }
    private boolean operationCurrent(Win w, long operation) {
        return windows.get(w.taskId) == w && w.operationSequence == operation;
    }
    public long geometryGeneration() { return generation; }
    /** J-04: the freeform stack id is injectable, so the overlays must ask for it instead of
     *  hardcoding stack 2. With a different freeform stack the chrome, the taskbar buttons,
     *  Snap Assist and Task View all filtered on the wrong stack and showed nothing. */
    public int freeformStackId() { return freeformStackId; }
    public SnapController snapController() { return snapController; }
    public Rect displayBounds(){return new Rect(0,0,screenWidth,screenHeight);}
    public boolean hasDockedPair(){return dockedPair!=null&&dockedPair.isActive();}
    public boolean isDockedPairMember(Win w){return dockedPair!=null&&dockedPair.contains(w);}
    public void enterDockedPair(Win first,Win second){if(dockedPair!=null)dockedPair.enter(first,second);}
    public void exitDockedPair(){if(dockedPair!=null)dockedPair.exit(focusedWindow());}
    public void exitDockedPair(Win preferred){if(dockedPair!=null)dockedPair.exit(preferred);}
    public void moveDockedDivider(float ratio){if(dockedPair!=null)dockedPair.resizeTo(ratio);}
    public void releaseDockedPair(){if(dockedPair!=null)dockedPair.release();}
    public int currentDesktopId(){return prefs.currentVirtualDesktop();}
    /** D-10: stable taskbar order by taskId. The z-order snapshot re-sorts on every focus
     *  change, which made taskbar buttons jump and Win+1…9 bindings shift under the user. */
    public List<Win> taskbarOrder() {
        ArrayList<Win> out = new ArrayList<>();
        for (Win w : windows.values()) {
            if (w.desktopId == currentDesktopId()
                    && (w.stackId == freeformStackId || w.state == Win.State.MINIMIZED)) {
                out.add(w);
            }
        }
        Collections.sort(out, Comparator.comparingInt(w -> w.taskId));
        return out;
    }
    public void focusTaskbarIndex(int index){
        List<Win> candidates=taskbarOrder();
        if(index<0||index>=candidates.size())return;Win target=candidates.get(index);
        if(target.state==Win.State.MINIMIZED)restore(target);else focus(target);
    }
    public void cycleTaskbarWindow(){
        List<Win> candidates=taskbarOrder();
        if(candidates.isEmpty())return;Win focused=focusedWindow();int i=candidates.indexOf(focused);Win next=candidates.get((i+1+candidates.size())%candidates.size());
        if(next.state==Win.State.MINIMIZED)restore(next);else focus(next);
    }
    public int virtualDesktopCount(){return prefs.virtualDesktopCount();}
    public int createVirtualDesktop(){
        int count=prefs.virtualDesktopCount();if(count>=4)return prefs.currentVirtualDesktop();
        prefs.setVirtualDesktopCount(count+1);switchVirtualDesktop(count);return count;
    }
    public void switchVirtualDesktop(int target){
        int count=prefs.virtualDesktopCount();target=Math.max(0,Math.min(count-1,target));int current=prefs.currentVirtualDesktop();if(target==current)return;
        java.util.Set<String> hidden=prefs.virtualDesktopHiddenTasks();java.util.Set<String> processedGroups=new java.util.LinkedHashSet<>();
        boolean leaveDockedPair=false;
        for(Win w:snapshot())if(w.desktopId==current&&w.state!=Win.State.MINIMIZED){
            if(w.snapGroupId!=null&&!processedGroups.add(w.snapGroupId))continue;
            if(dockedPair!=null&&dockedPair.contains(w)){
                Win first=dockedPair.first(),second=dockedPair.second();
                if(first!=null)hidden.add(String.valueOf(first.taskId));if(second!=null)hidden.add(String.valueOf(second.taskId));
                leaveDockedPair=true;continue;
            }
            for(Win member:snapController.groupMembers(w))if(member.state!=Win.State.MINIMIZED)hidden.add(String.valueOf(member.taskId));
            minimize(w);
        }
        prefs.setVirtualDesktopHiddenTasks(hidden);prefs.setCurrentVirtualDesktop(target);
        if(leaveDockedPair)scheduleDockedPairHideForInactiveDesktop();
        for(String id:new java.util.ArrayList<>(hidden)){
            Win w;try{w=windows.get(Integer.parseInt(id));}catch(NumberFormatException e){hidden.remove(id);continue;}
            if(w==null){hidden.remove(id);continue;}
            if(w.desktopId==target&&w.state==Win.State.MINIMIZED)restoreVirtualDesktopMember(w,hidden);
        }
        prefs.setVirtualDesktopHiddenTasks(hidden);notifyListeners();
    }
    private void restoreVirtualDesktopMember(Win w,java.util.Set<String> hidden){
        List<Win> members=snapController.groupMembers(w);
        boolean newlyPending=false;
        for(Win member:members){if(pendingDesktopRestoreIds.add(member.taskId))newlyPending=true;hidden.remove(String.valueOf(member.taskId));}
        if(!newlyPending)return;
        java.util.ArrayList<Integer> ids=new java.util.ArrayList<>();for(Win member:members)ids.add(member.taskId);
        main.postDelayed(()->pendingDesktopRestoreIds.removeAll(ids),2500L);
        restore(w);
    }
    private void reconcileVirtualDesktopVisibility(){
        int current=prefs.currentVirtualDesktop();java.util.Set<String> hidden=prefs.virtualDesktopHiddenTasks();
        java.util.Set<String> processed=new java.util.LinkedHashSet<>();boolean leaveDockedPair=false;
        for(Win w:snapshot()){
            String key=String.valueOf(w.taskId);
            if(w.state!=Win.State.MINIMIZED)pendingDesktopRestoreIds.remove(w.taskId);
            if(pendingDesktopPairExitIds.contains(w.taskId))continue;
            if(w.desktopId!=current&&w.state!=Win.State.MINIMIZED){
                if(dockedPair!=null&&dockedPair.contains(w)){
                    Win first=dockedPair.first(),second=dockedPair.second();
                    if(first!=null)hidden.add(String.valueOf(first.taskId));if(second!=null)hidden.add(String.valueOf(second.taskId));
                    leaveDockedPair=true;continue;
                }
                if(w.snapGroupId!=null&&!processed.add(w.snapGroupId))continue;
                for(Win member:snapController.groupMembers(w))if(member.state!=Win.State.MINIMIZED)hidden.add(String.valueOf(member.taskId));
                minimize(w);
            }else if(w.desktopId==current&&hidden.contains(key)&&w.state==Win.State.MINIMIZED){
                restoreVirtualDesktopMember(w,hidden);
            }
        }
        prefs.setVirtualDesktopHiddenTasks(hidden);
        if(leaveDockedPair)scheduleDockedPairHideForInactiveDesktop();
    }
    private void scheduleDockedPairHideForInactiveDesktop(){
        if(dockedPair==null||!dockedPair.isActive())return;
        Win first=dockedPair.first(),second=dockedPair.second();java.util.ArrayList<Win> members=new java.util.ArrayList<>();
        if(first!=null){members.add(first);pendingDesktopPairExitIds.add(first.taskId);}
        if(second!=null){members.add(second);pendingDesktopPairExitIds.add(second.taskId);}
        dockedPair.exit(null,()->{
            int current=prefs.currentVirtualDesktop();java.util.Set<String> hidden=prefs.virtualDesktopHiddenTasks();
            for(Win member:members){pendingDesktopPairExitIds.remove(member.taskId);if(member.desktopId!=current){hidden.add(String.valueOf(member.taskId));if(member.state!=Win.State.MINIMIZED)minimize(member);}else hidden.remove(String.valueOf(member.taskId));}
            prefs.setVirtualDesktopHiddenTasks(hidden);notifyListeners();
        });
    }
        public void moveWindowToDesktop(Win w,int desktop){
        if(w==null)return;
        desktop=Math.max(0,Math.min(prefs.virtualDesktopCount()-1,desktop));
        int old=w.desktopId;
        if(old==desktop)return;
        int current=prefs.currentVirtualDesktop();
        // F-03: a SnapGroup travels as one unit; moving a single member desynchronised the group
        // and minimize() then hid members the caller did not expect.
        List<Win> members=snapController.groupMembers(w);
        if(members==null||members.isEmpty())members=java.util.Collections.singletonList(w);
        boolean leavesCurrent=false;
        for(Win member:members){
            if(member.desktopId==current)leavesCurrent=true;
        }
        for(Win member:members){
            member.desktopId=desktop;
            prefs.setTaskDesktop(member.taskId,desktop);
        }
        java.util.Set<String> hidden=prefs.virtualDesktopHiddenTasks();
        boolean hiddenChanged=false;
        if(leavesCurrent){
            // F-03: without these ids switchVirtualDesktop never restores the window on return (V-03).
            for(Win member:members){
                if(member.state!=Win.State.MINIMIZED&&hidden.add(String.valueOf(member.taskId)))hiddenChanged=true;
            }
        }
        if(desktop==current){
            // G-03: a window that arrives on the current desktop must leave the hidden set,
            // otherwise reconcile keeps treating it as parked on another desktop.
            for(Win member:members){
                if(hidden.remove(String.valueOf(member.taskId)))hiddenChanged=true;
            }
        }
        if(hiddenChanged)prefs.setVirtualDesktopHiddenTasks(hidden);
        if(leavesCurrent&&w.state!=Win.State.MINIMIZED)minimize(w);
        if(desktop==current&&w.state==Win.State.MINIMIZED)restore(w);
        notifyListeners();
    }
    /** M-03: single activation path for list surfaces. switchVirtualDesktop already
     *  queues a restore for hidden minimized members (pendingDesktopRestoreIds), so a
     *  second restore() from the caller would double-restore the same window. */
    public void activateWindow(Win w) {
        if (w == null) return;
        if (w.desktopId != currentDesktopId()) switchVirtualDesktop(w.desktopId);
        if (pendingDesktopRestoreIds.contains(w.taskId)) return;
        if (w.state == Win.State.MINIMIZED) restore(w);
        else focus(w);
    }

    public void moveToDockedStack(Win w,Rect bounds,Result completion){
        Runnable resize=()->root.resize(w.taskId,bounds.left,bounds.top,bounds.right,bounds.bottom,0,result->{
            if(!result.optBoolean("ok",false)){report(result,null);if(completion!=null)completion.onResult(false,result.optString("error","resize failed"));return;}
            Rect actual=readConfirmedBounds(result);w.bounds.set(actual==null?bounds:actual);w.requestedBounds.set(w.bounds);w.stackId=DOCKED_STACK_ID;w.state=Win.State.FULLSCREEN;w.visible=true;notifyListeners();if(completion!=null)completion.onResult(true,"");
        });
        if(w.stackId!=DOCKED_STACK_ID)root.moveToStack(w.taskId,DOCKED_STACK_ID,true,result->{if(!result.optBoolean("ok",false)){report(result,null);if(completion!=null)completion.onResult(false,result.optString("error","stack move failed"));return;}resize.run();});else resize.run();
    }
    public void requestDockedBounds(Win w,Rect bounds){w.stackId=DOCKED_STACK_ID;w.state=Win.State.FULLSCREEN;w.visible=true;requestBounds(w,bounds,1);}
    /** I-02: rollback a failed first transfer into the docked stack back to its original stack. */
    public void rollbackDockedTransfer(Win w, int originalStackId) {
        if (w == null) return;
        root.moveToStack(w.taskId, originalStackId, true, r -> refreshTasks(null));
    }
    public void moveFromDockedStack(Win w,Runnable after){
        Rect bounds=displayBounds();
        Runnable resize=()->root.resize(w.taskId,bounds.left,bounds.top,bounds.right,bounds.bottom,0,result->{
            if(!result.optBoolean("ok",false)){report(result,null);if(after!=null)after.run();return;}
            Rect actual=readConfirmedBounds(result);w.bounds.set(actual==null?bounds:actual);w.requestedBounds.set(w.bounds);w.stackId=fullscreenStackId;w.state=Win.State.FULLSCREEN;w.visible=true;notifyListeners();if(after!=null)after.run();
        });
        if(w.stackId!=fullscreenStackId)root.moveToStack(w.taskId,fullscreenStackId,true,result->{if(!result.optBoolean("ok",false)){report(result,null);if(after!=null)after.run();return;}resize.run();});else resize.run();
    }
    public void snapHalf(Win w, boolean left) { snapController.snapHalf(w, left); }
    public void applySnapBounds(Win w, Rect bounds, String groupId, int cell) {
        if (w.state == Win.State.NORMAL && w.hasUsableBounds()) w.normalBounds.set(w.bounds);
        w.snapGroupId=groupId; w.snapCell=cell;
        moveToStackThenResize(w, freeformStackId, true, clamp(bounds), Win.State.SNAPPED, null);
    }
    public void releaseActive() { if(activeController==this)activeController=null;if(snapController!=null)snapController.releaseActive();if(dockedPair!=null)dockedPair.release(); }
    public Win focusedWindow() {
        Win full=null,desktop=null;
        for (Win w : windows.values()) if (w.visible && w.state!=Win.State.MINIMIZED) {
            if((w.stackId==fullscreenStackId||w.stackId==DOCKED_STACK_ID)&&(full==null||w.z>full.z))full=w;
            else if(w.stackId==freeformStackId&&(desktop==null||w.z>desktop.z))desktop=w;
        }
        return full!=null?full:desktop;
    }
    public void toggleFocusedFullscreen() {
        Win top = focusedWindow();
        if (top != null) toggleFullscreen(top);
    }
    public boolean willHandleEscapeFullscreen(){Win w=focusedWindow();return prefs.escapeExitsFullscreen()&&w!=null&&w.state==Win.State.FULLSCREEN;}
    public boolean handleEscapeFullscreen(){
        if(!willHandleEscapeFullscreen())return false;
        Win w=focusedWindow();if(dockedPair!=null&&dockedPair.contains(w))dockedPair.exit(w,()->maximize(w));else maximize(w);
        return true;
    }
    public void keyboardSnap(boolean left) {
        Win w=focusedWindow(); if(w==null||w.stackId!=freeformStackId)return;
        if(w.state==Win.State.SNAPPED && w.snapGroupId!=null && w.snapCell>=0) {
            SnapController.Group g=snapController.group(w.snapGroupId);
            if(g!=null && g.layout==SnapController.Layout.TWO_HALF && w.snapCell==(left?0:1)) {
                snapController.snap(w,SnapController.Layout.FOUR_GRID,left?0:1,true); return;
            }
            if(g!=null && g.layout==SnapController.Layout.FOUR_GRID && w.snapCell==(left?0:1)) {
                snapController.snap(w,SnapController.Layout.FOUR_GRID,left?2:3,true); return;
            }
        }
        snapController.snapHalf(w,left);
    }
    public void keyboardMaximizeUp() {
        Win w=focusedWindow();if(w==null)return;
        if(w.state==Win.State.NORMAL||w.state==Win.State.SNAPPED)maximize(w);
        else if(w.state==Win.State.MAXIMIZED&&!windowsButtonBehavior())fullscreen(w);
    }
    public void keyboardRestoreDown() {
        Win w=focusedWindow();if(w==null)return;
        if(w.state==Win.State.FULLSCREEN)maximize(w);
        else if(w.state==Win.State.MAXIMIZED||w.state==Win.State.SNAPPED)restoreNormal(w);
        else if(w.state==Win.State.NORMAL)minimize(w);
    }

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
        List<Win> newlyObserved=new ArrayList<>();
        String ownPackage = context.getPackageName();
        int activeFullscreenId=-1,activeFullscreenZ=Integer.MIN_VALUE;
        for(int i=0;i<tasks.length();i++){JSONObject row=tasks.getJSONObject(i);String pkg=componentPackage(row.optString("component",""));
            if(row.optInt("stack",-1)==fullscreenStackId&&row.optBoolean("visible",false)&&!pkg.equals(ownPackage)&&row.optInt("z",i)>activeFullscreenZ){activeFullscreenId=row.optInt("id",-1);activeFullscreenZ=row.optInt("z",i);}}
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject row = tasks.getJSONObject(i);
            int id = row.getInt("id");
            int stack = row.optInt("stack", -1);
            String component = row.optString("component", "");
            String pkg = componentPackage(component);
            if (pkg.equals(ownPackage)) continue;
            // WC2-02: the supported-stack filter used to apply only to new windows, so a
            // tracked task that moved to an unsupported stack stayed managed forever.
            // Filtering here leaves it out of seen, and the removal pass cleans it up.
            if (stack != freeformStackId && stack != fullscreenStackId && stack != DOCKED_STACK_ID) continue;
            Win w = windows.get(id);
            if (w == null) {
                // Keep fullscreen-stack history as selectable candidates for docked-stack pairs;
                // only the top stack-1 task is visible until a pair is explicitly formed.
                if(stack!=freeformStackId&&stack!=fullscreenStackId&&stack!=DOCKED_STACK_ID)continue;
                w = new Win(id);
                w.packageName = pkg;
                w.component = component;
                w.title = resolveTitle(pkg, component);
                w.stackId = stack;
                w.desktopId=prefs.taskDesktop(id,prefs.currentVirtualDesktop());
                w.visible = row.optBoolean("visible", false)&&(stack==freeformStackId||stack==DOCKED_STACK_ID||id==activeFullscreenId);
                w.z = row.optInt("z", i);
                readBounds(w.bounds, row.optJSONArray("bounds"));
                if(stack==fullscreenStackId||stack==DOCKED_STACK_ID)w.state=Win.State.FULLSCREEN;
                else if (sameRect(w.bounds, workArea())) w.state = Win.State.MAXIMIZED;
                else w.state = Win.State.NORMAL;
                if (w.state == Win.State.NORMAL) w.normalBounds.set(w.bounds);
                restorePersistedMinimizedState(w);
                windows.put(id, w);
                if(initialized && w.hasUsableBounds()&&w.state!=Win.State.MINIMIZED)newlyObserved.add(w);
            } else {
                // WC2-01: Android recycles taskIds. If the package/component behind this id
                // changed, the row now describes a different task: retire everything the old
                // task left behind instead of letting the new one inherit its state, geometry,
                // snap group, desktop and persisted minimize record. (A stable task-instance
                // key is not available: RootServer reports only the numeric taskId, so an
                // identity change is the strongest reuse signal the bridge can offer.)
                boolean identityChanged = !pkg.equals(w.packageName) || !component.equals(w.component);
                Win.State stateBeforePoll = w.state;
                if (identityChanged) {
                    prefs.clearSavedWindow(id);
                    prefs.clearTaskDesktop(id);
                    pendingMinimizeIds.remove(id);
                    showDesktopRestoreIds.remove(id);
                    showDesktopRestoringIds.remove(id);
                    pendingDesktopRestoreIds.remove(id);
                    if (keyboardModeTarget == w) keyboardModeTarget = null;
                    if (w.snapGroupId != null) snapController.detach(w);
                    w.state = Win.State.NORMAL;
                    w.stateBeforeMinimize = Win.State.NORMAL;
                    w.minimizeRestoreBounds.setEmpty();
                    w.normalBounds.setEmpty();
                    w.desktopId = prefs.taskDesktop(id, prefs.currentVirtualDesktop());
                    // R-03: invalidate callbacks still in flight from the previous task that
                    // owned this id, so they cannot pass the currency checks and mutate the
                    // recycled Win on behalf of a task that no longer exists.
                    nextOperation(w);
                    ++w.resizeSequence;
                }
                w.packageName = pkg;
                w.component = component;
                // WC2-19: re-resolving the label costs a PackageManager call per task per
                // poll; only do it when the identity actually changed (labels are cached
                // per package inside resolveTitle).
                if (identityChanged) w.title = resolveTitle(pkg, component);
                w.stackId = stack;
                w.visible = row.optBoolean("visible", false) && (stack!=fullscreenStackId || id==activeFullscreenId) && w.state!=Win.State.MINIMIZED;
                w.z = row.optInt("z", i);
                Rect actual = new Rect();
                readBounds(actual, row.optJSONArray("bounds"));
                if (actual.width() > 0 && actual.height() > 0) w.bounds.set(actual);
                if(stack==freeformStackId&&w.state!=Win.State.MINIMIZED){
                    if(sameRect(w.bounds,workArea())){
                        // Reconcile native/Samsung maximize actions as well as launcher actions.
                        // A snapped member maximized outside our own chrome must leave its group.
                        if(w.state==Win.State.SNAPPED&&w.snapGroupId!=null)snapController.detach(w);
                        w.state=Win.State.MAXIMIZED;
                    }else if(w.state==Win.State.FULLSCREEN||w.state==Win.State.MAXIMIZED){
                        w.state=Win.State.NORMAL;
                        w.normalBounds.set(w.bounds);
                    }
                }
                if ((stack == fullscreenStackId||stack==DOCKED_STACK_ID) && w.state != Win.State.MINIMIZED) w.state = Win.State.FULLSCREEN;
                // WC2-03: an already-NORMAL window resized outside the launcher (native
                // chrome, Samsung edge actions) updated bounds but not normalBounds, so a
                // later maximize/restore returned it to a stale rectangle.
                if (w.state == Win.State.NORMAL && !sameRect(w.bounds, workArea())
                        && (identityChanged || stateBeforePoll == Win.State.NORMAL)) {
                    w.normalBounds.set(w.bounds);
                }
            }
            seen.put(id, true);
        }
        ArrayList<Integer> remove = new ArrayList<>();
        for (Integer id : windows.keySet()) if (!seen.containsKey(id)) remove.add(id);
        for (Integer id : remove) {
            // D-12: also drop the desktop-restore queue and the persisted virtual-desktop
            // hidden set, or a recycled taskId could inherit another window's desktop.
            pendingMinimizeIds.remove(id);showDesktopRestoreIds.remove(id);pendingDesktopRestoreIds.remove(id);
            showDesktopRestoringIds.remove(id);pendingDesktopPairExitIds.remove(id);
            java.util.Set<String> hiddenTasks=prefs.virtualDesktopHiddenTasks();
            if(hiddenTasks.remove(String.valueOf(id)))prefs.setVirtualDesktopHiddenTasks(hiddenTasks);
            Win removedWindow=windows.get(id);
            // WC2-05: the removal pass cleared the pending sets but not the keyboard
            // move/resize target, so DPAD/Enter/Escape could keep acting on a removed
            // (or recycled) task after its window disappeared.
            if(removedWindow!=null&&keyboardModeTarget==removedWindow)keyboardModeTarget=null;
            if(removedWindow!=null){
                snapController.detach(removedWindow);
                if(dockedPair!=null&&dockedPair.contains(removedWindow)){Win other=dockedPair.first()==removedWindow?dockedPair.second():dockedPair.first();dockedPair.onTaskRemoved(removedWindow,other!=null&&!remove.contains(other.taskId));}
            }
            windows.remove(id);prefs.clearSavedWindow(id);prefs.clearTaskDesktop(id);
        }
        restoreShowDesktopWhenReady();
        checkShowDesktopRestoreComplete();
        if(dockedPair!=null&&!dockedPair.isActive()){
            ArrayList<Win> docked=new ArrayList<>();for(Win w:windows.values())if(w.stackId==DOCKED_STACK_ID)docked.add(w);
            if(docked.size()>=2){Collections.sort(docked,Comparator.comparingInt(w->w.z));dockedPair.adoptExisting(docked.get(0),docked.get(1));}
        }
        reconcileVirtualDesktopVisibility();
        // S4 (poll half: S9): one notify per real model change. The 750 ms poll used to rebuild
        // every chrome, grip, taskbar button, divider and Task View row even when nothing moved.
        String signature=stateSignature();
        boolean changed=!initialized||!signature.equals(lastSignature);
        lastSignature=signature;
        if(changed)notifyListeners();
        if(initialized)for(Win w:newlyObserved)animator.transition(w,new Rect(w.bounds),WindowAnimator.Kind.OPEN,()->{});
        initialized=true;
    }

    /** S4 helper: compact signature of everything the overlays render. */
    private String stateSignature(){
        StringBuilder b=new StringBuilder(windows.size()*48);
        for(Win w:snapshot())b.append(w.taskId).append(':').append(w.stackId).append(':').append(w.state.ordinal())
                .append(':').append(w.visible?'1':'0').append(':').append(w.z).append(':').append(w.desktopId)
                .append(':').append(w.snapCell).append(':').append(w.bounds.toShortString())
                // WC2-04: labels, icons and snap-group UI render from these fields; leaving
                // them out of the signature let adoptTasks change them without notifying.
                .append(':').append(w.packageName).append(':').append(w.component)
                .append(':').append(w.title).append(':').append(w.snapGroupId).append(';');
        return b.toString();
    }

    private String resolveTitle(String pkg, String component) {
        // WC2-19: application labels are stable per package; cache them instead of paying
        // a PackageManager Binder call for every task on every 750 ms poll.
        String cached = pkg == null ? null : titleCache.get(pkg);
        if (cached != null) return cached;
        try {
            CharSequence label = context.getPackageManager().getApplicationLabel(
                    context.getPackageManager().getApplicationInfo(pkg, 0));
            if (label != null) {
                if (pkg != null) titleCache.put(pkg, label.toString());
                return label.toString();
            }
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
        // WC2-18: parse strictly and keep the previous rectangle unless the new one is a
        // valid positive-size area; optInt() used to turn malformed entries into zeroes.
        try {
            int left = a.getInt(0), top = a.getInt(1), right = a.getInt(2), bottom = a.getInt(3);
            if (right > left && bottom > top) out.set(left, top, right, bottom);
        } catch (Exception ignored) { }
    }

    private void restorePersistedMinimizedState(Win w){
        String saved=prefs.savedWindow(w.taskId);if(saved==null)return;
        String[] parts=saved.split("\\|",-1);if(parts.length<3||!w.packageName.equals(parts[0]))return;
        try{
            Win.State before=Win.State.valueOf(parts[1]);String[] xy=parts[2].split(",");if(xy.length!=4)return;
            Rect restoreBounds=new Rect(Integer.parseInt(xy[0]),Integer.parseInt(xy[1]),Integer.parseInt(xy[2]),Integer.parseInt(xy[3]));
            if(restoreBounds.width()>0&&restoreBounds.height()>0){w.normalBounds.set(restoreBounds);w.minimizeRestoreBounds.set(restoreBounds);}
            w.stateBeforeMinimize=before;w.state=Win.State.MINIMIZED;w.visible=false;
            if(parts.length>=6&&parts[3].length()>0&&parts[4].length()>0)try{
                SnapController.Layout layout=SnapController.Layout.valueOf(parts[4]);int cell=Integer.parseInt(parts[5]);RectF cellBounds=null;
                if(parts.length>=7&&parts[6].length()>0){String[] f=parts[6].split(",");if(f.length==4)cellBounds=new RectF(Float.parseFloat(f[0]),Float.parseFloat(f[1]),Float.parseFloat(f[2]),Float.parseFloat(f[3]));}
                snapController.restorePersistedMember(w,parts[3],layout,cell,cellBounds);
            }catch(RuntimeException ignored){}
            ensureMinimizedParked(w);
        }catch(RuntimeException ignored){}
    }
    private void ensureMinimizedParked(Win w){
        int original=w.stackId;
        final long operation=nextOperation(w);
        root.moveToStack(w.taskId,fullscreenStackId,false,result->{
            if(!operationCurrent(w,operation))return;
            if(result.optBoolean("ok",false)){w.stackId=fullscreenStackId;notifyListeners();return;}
            root.moveToStack(w.taskId,original,false,fallback->{
                if(!operationCurrent(w,operation))return;
                if(fallback.optBoolean("ok",false)){w.stackId=original;notifyListeners();return;}
                try{Intent home=new Intent(Intent.ACTION_MAIN);home.addCategory(Intent.CATEGORY_HOME);home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(home);}
                catch(RuntimeException ignored){}
            });
        });
    }

    private static boolean sameRect(Rect a, Rect b) {
        return Math.abs(a.left - b.left) <= 2 && Math.abs(a.top - b.top) <= 2
                && Math.abs(a.right - b.right) <= 2 && Math.abs(a.bottom - b.bottom) <= 2;
    }

    public void focus(Win w) {
        // WC2-08: focus() used to front a MINIMIZED task directly, marking the parked task
        // visible without un-parking it. Route minimized windows through restore() instead.
        if (w == null) return;
        if (w.state == Win.State.MINIMIZED) { restore(w); return; }
        List<Win> members;
        if(dockedPair!=null&&dockedPair.contains(w)){
            members=new ArrayList<>();
            if(dockedPair.first()!=null)members.add(dockedPair.first());
            if(dockedPair.second()!=null)members.add(dockedPair.second());
        }else members=snapController.groupMembers(w);
        List<Win> raise=new ArrayList<>();
        for(Win member:members)if(member.visible&&member.state!=Win.State.MINIMIZED)raise.add(member);
        if(!raise.contains(w))raise.add(w);
        if(raise.size()>1){
            Collections.sort(raise,Comparator.comparingInt(member->member.z));
            raise.remove(w);raise.add(w);
        }
        int z=0;for(Win member:raise){member.z=Integer.MAX_VALUE-raise.size()+z++;member.visible=true;}
        for(Win member:raise)root.front(member.taskId,result->{if(!result.optBoolean("ok",false))report(result,null);});
        notifyListeners();if(w.snapGroupId!=null)snapController.refreshGroup(w.snapGroupId);
    }

    public void aeroShake(Win source){
        Set<String> retained=new LinkedHashSet<>();
        if(source.snapGroupId!=null)retained.add(source.snapGroupId);
        Set<String> visited=new LinkedHashSet<>();
        for(Win candidate:snapshot()){
            if(!candidate.visible||candidate.state==Win.State.MINIMIZED||candidate.state==Win.State.FULLSCREEN)continue;
            if(candidate==source||candidate.snapGroupId!=null&&retained.contains(candidate.snapGroupId))continue;
            if(candidate.snapGroupId!=null&&!visited.add(candidate.snapGroupId))continue;
            minimize(candidate);
        }
    }

    public void toggleShowDesktop(){
        // WC2-09: while a Show Desktop restore is still in flight the restore set is
        // already cleared, so a second Win+D used to be silently lost. Queue it and replay
        // it when the restore completes (see checkShowDesktopRestoreComplete()).
        if(!showDesktopRestoringIds.isEmpty()){showDesktopToggleQueued=true;return;}
        if(!showDesktopRestoreIds.isEmpty()){
            for(Integer id:showDesktopRestoreIds)if(pendingMinimizeIds.contains(id)){
                // A second Win+D during the minimize transition queues restoration instead of
                // clearing the restore set before the asynchronous stack operation finishes.
                restoreShowDesktopWhenMinimized=true;return;
            }
            // M-07(3): if every window in the restore set was already restored by hand,
            // the set is stale: clear it and fall through to a fresh Show Desktop.
            if (hasMinimizedShowDesktopWindow()) { restoreShowDesktopNow(); return; }
            showDesktopRestoreIds.clear();
        }
        restoreShowDesktopWhenMinimized=false;
        Set<String> groups=new LinkedHashSet<>();
        for(Win w:snapshot())if(w.stackId==freeformStackId&&w.visible&&w.state!=Win.State.MINIMIZED){
            if(w.snapGroupId!=null&&!groups.add(w.snapGroupId))continue;
            showDesktopRestoreIds.add(w.taskId);
            if(w.snapGroupId!=null){for(Win member:snapController.groupMembers(w))showDesktopRestoreIds.add(member.taskId);}
            minimize(w);
        }
    }

    private boolean hasMinimizedShowDesktopWindow() {
        for (Win w : snapshot()) if (showDesktopRestoreIds.contains(w.taskId) && w.state == Win.State.MINIMIZED) return true;
        return false;
    }

    private void restoreShowDesktopNow(){
        Set<Integer> restoreIds=new LinkedHashSet<>(showDesktopRestoreIds);showDesktopRestoreIds.clear();
        restoreShowDesktopWhenMinimized=false;
        // WC2-09: remember which windows this restore is bringing back; a Win+D pressed
        // before they land is queued in toggleShowDesktop() and replayed on completion.
        if(!restoreIds.isEmpty()){
            showDesktopRestoringIds.addAll(restoreIds);
            showDesktopRestoreStartedAt=android.os.SystemClock.uptimeMillis();
        }
        Set<String> restoredGroups=new LinkedHashSet<>();
        for(Integer id:restoreIds){Win w=windows.get(id);if(w==null||w.state!=Win.State.MINIMIZED)continue;if(w.snapGroupId!=null&&!restoredGroups.add(w.snapGroupId))continue;restore(w);}
    }

    private void restoreShowDesktopWhenReady(){
        if(!restoreShowDesktopWhenMinimized)return;
        for(Integer id:showDesktopRestoreIds)if(pendingMinimizeIds.contains(id))return;
        restoreShowDesktopNow();
    }

    // WC2-09: called from adoptTasks() on every poll. A restore counts as complete when
    // none of its windows is MINIMIZED any more (or vanished); a stuck restore times out
    // after 3 s so a queued Win+D is never held hostage by a failed root operation.
    private void checkShowDesktopRestoreComplete(){
        if(showDesktopRestoringIds.isEmpty())return;
        boolean timedOut=android.os.SystemClock.uptimeMillis()-showDesktopRestoreStartedAt>3000L;
        showDesktopRestoringIds.removeIf(id->{
            Win w=windows.get(id);
            return w==null||w.state!=Win.State.MINIMIZED;
        });
        if(!showDesktopRestoringIds.isEmpty()&&!timedOut)return;
        showDesktopRestoringIds.clear();
        if(showDesktopToggleQueued){
            showDesktopToggleQueued=false;
            toggleShowDesktop();
        }
    }

    public void beginKeyboardMove(Win w){prepareKeyboardWindow(w);keyboardModeTarget=w;keyboardResizeMode=false;keyboardModeStart.set(w.bounds);focus(w);}
    public void beginKeyboardResize(Win w){prepareKeyboardWindow(w);keyboardModeTarget=w;keyboardResizeMode=true;keyboardModeStart.set(w.bounds);focus(w);}
    private void prepareKeyboardWindow(Win w){if(w.state==Win.State.SNAPPED){snapController.detach(w);w.state=Win.State.NORMAL;w.normalBounds.set(w.bounds);notifyListeners();}}
    public boolean handleSystemMenuKey(KeyEvent event){
        if(keyboardModeTarget==null)return false;
        int key=event.getKeyCode();
        if(event.getAction()!=KeyEvent.ACTION_UP)return key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key>=KeyEvent.KEYCODE_DPAD_UP&&key<=KeyEvent.KEYCODE_DPAD_RIGHT;
        Win w=keyboardModeTarget;
        if(key==KeyEvent.KEYCODE_ESCAPE){requestBounds(w,keyboardModeStart,0);keyboardModeTarget=null;return true;}
        if(key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER){commitMove(w);keyboardModeTarget=null;return true;}
        int step=(event.getMetaState()&KeyEvent.META_SHIFT_ON)!=0?dp(32):dp(8);Rect next=new Rect(w.requestedBounds.width()>0?w.requestedBounds:w.bounds);
        if(keyboardResizeMode){
            if(key==KeyEvent.KEYCODE_DPAD_LEFT)next.right-=step;else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)next.right+=step;
            else if(key==KeyEvent.KEYCODE_DPAD_UP)next.bottom-=step;else if(key==KeyEvent.KEYCODE_DPAD_DOWN)next.bottom+=step;else return false;
            if(next.width()<dp(240))next.right=next.left+dp(240);if(next.height()<dp(160))next.bottom=next.top+dp(160);
        }else{
            if(key==KeyEvent.KEYCODE_DPAD_LEFT)next.offset(-step,0);else if(key==KeyEvent.KEYCODE_DPAD_RIGHT)next.offset(step,0);
            else if(key==KeyEvent.KEYCODE_DPAD_UP)next.offset(0,-step);else if(key==KeyEvent.KEYCODE_DPAD_DOWN)next.offset(0,step);else return false;

        }

        requestBounds(w,clamp(next),1);return true;

    }

    public void maximize(Win w) {

        if(dockedPair!=null&&dockedPair.contains(w)){dockedPair.exit(w,()->maximize(w));return;}

        if (w.state == Win.State.NORMAL && w.hasUsableBounds()) w.normalBounds.set(w.bounds);

        // WC2-11: detach from the snap group only after the maximize is confirmed;
        // detaching up front left the model saying SNAPPED for a window already out of
        // its group whenever the move/resize failed.
        final boolean wasSnapped = w.state == Win.State.SNAPPED;

        moveToStackThenResize(w, freeformStackId, true, workArea(), Win.State.MAXIMIZED,
                () -> { if (wasSnapped) snapController.detach(w); });

    }

    public void fullscreen(Win w) {

        // WC2-10: the one-argument exit() only leaves the pair; without a continuation
        // the requested fullscreen was silently dropped (maximize/minimize pass one).
        if(dockedPair!=null&&dockedPair.contains(w)){dockedPair.exit(w,()->fullscreen(w));return;}

        // S25: F11 from a floating window used to lose the floating rectangle, so MAX afterwards
        // produced a full-screen "restored" window.
        if (w.state == Win.State.NORMAL && w.hasUsableBounds()) w.normalBounds.set(w.bounds);

        // WC2-11: the snap detach is deferred until the fullscreen resize is confirmed.
        final boolean wasSnapped = w.state == Win.State.SNAPPED;
        final int originalStack = w.stackId;
        final long operation = nextOperation(w);

        Rect target=new Rect(0,0,screenWidth,screenHeight);

        animator.transition(w,target,WindowAnimator.Kind.RESTORE,()->root.moveToStack(w.taskId,fullscreenStackId,true,moved->{

            if(!operationCurrent(w,operation))return;

            if(!moved.optBoolean("ok",false)){report(moved,null);return;}

            root.resize(w.taskId,target.left,target.top,target.right,target.bottom,0,resized->{

                if(!operationCurrent(w,operation))return;

                if(!resized.optBoolean("ok",false)){
                    report(resized,null);
                    // WC2-12: the model used to commit stackId/state/visible FULLSCREEN
                    // before this resize ran, so a failed resize left a fullscreen model
                    // over stale geometry. Nothing is committed yet now; roll the native
                    // task back to its original stack and reconcile from the root state.
                    if(originalStack!=fullscreenStackId)root.moveToStack(w.taskId,originalStack,true,rolledBack->refreshTasks(null));
                    else refreshTasks(null);
                    return;
                }

                w.stackId=fullscreenStackId;w.state=Win.State.FULLSCREEN;w.visible=true;

                Rect actual=readConfirmedBounds(resized);w.bounds.set(actual==null?target:actual);w.requestedBounds.set(w.bounds);

                if(wasSnapped)snapController.detach(w);

                notifyListeners();

            });

        }));

    }

    public void minimize(Win w) {

        if(dockedPair!=null&&dockedPair.contains(w)){dockedPair.exit(w,()->minimize(w));return;}

        List<Win> group=snapController.groupMembers(w);

        if(group.size()>1){for(Win member:new ArrayList<>(group))minimizeSingle(member);}

        else minimizeSingle(w);

    }

    /** F-14: R-02 — a stale operation must not leave a saved minimized state behind a live window. */
    private void clearStaleMinimizeState(Win w){
        if(windows.get(w.taskId)==w&&w.state!=Win.State.MINIMIZED)prefs.clearSavedWindow(w.taskId);
    }

    private void minimizeSingle(Win w){

        if(w.state==Win.State.MINIMIZED||!pendingMinimizeIds.add(w.taskId))return;

        if(w.state==Win.State.NORMAL&&w.hasUsableBounds())w.normalBounds.set(w.bounds);

        final int originalStack=w.stackId;w.stateBeforeMinimize=w.state;

        Rect saved=w.stateBeforeMinimize==Win.State.SNAPPED&&w.hasUsableBounds()?new Rect(w.bounds):

                (w.normalBounds.width()>0&&w.normalBounds.height()>0?new Rect(w.normalBounds):new Rect(w.bounds));

        w.minimizeRestoreBounds.set(saved);

        SnapController.Group savedGroup=w.snapGroupId==null?null:snapController.group(w.snapGroupId);

        String layout=savedGroup==null?null:savedGroup.layout.name(),cellBounds=null;

        if(savedGroup!=null){RectF cell=savedGroup.cells.get(w.snapCell);if(cell!=null)cellBounds=cell.left+","+cell.top+","+cell.right+","+cell.bottom;}

        prefs.saveMinimizedState(w.taskId,w.packageName,saved.left,saved.top,saved.right,saved.bottom,w.stateBeforeMinimize.name(),w.snapGroupId,layout,w.snapCell,cellBounds);

        Rect target=new Rect(0,Math.max(0,screenHeight-taskbarHeight),dp(48),screenHeight);

        final long operation=nextOperation(w);

        animator.transition(w,target,WindowAnimator.Kind.MINIMIZE,()->root.moveToStack(w.taskId,fullscreenStackId,false,result->{

            if (!operationCurrent(w, operation)) { clearStaleMinimizeState(w); pendingMinimizeIds.remove(w.taskId); restoreShowDesktopWhenReady(); return; }

            if(result.optBoolean("ok",false)){markMinimized(w,fullscreenStackId);return;}

            // Fallback 1: park behind peers in its current stack. Fallback 2: surface Home,

            // leaving the task restorable from the taskbar even on Samsung builds that reject stack 1.

            root.moveToStack(w.taskId,originalStack,false,fallback->{

                if (!operationCurrent(w, operation)) { clearStaleMinimizeState(w); pendingMinimizeIds.remove(w.taskId); restoreShowDesktopWhenReady(); return; }

                if(fallback.optBoolean("ok",false)){markMinimized(w,originalStack);return;}

                try{

                    Intent home=new Intent(Intent.ACTION_MAIN);home.addCategory(Intent.CATEGORY_HOME);home.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    context.startActivity(home);markMinimized(w,originalStack);

                }catch(RuntimeException e){prefs.clearSavedWindow(w.taskId);pendingMinimizeIds.remove(w.taskId);report(fallback,null);restoreShowDesktopWhenReady();}

            });

        }));

    }

    private void markMinimized(Win w,int parkedStack){

        pendingMinimizeIds.remove(w.taskId);

        w.state=Win.State.MINIMIZED;w.stackId=parkedStack;w.visible=false;if(w.snapGroupId!=null)snapController.refreshGroup(w.snapGroupId);notifyListeners();

        restoreShowDesktopWhenReady();

    }

    public void restore(Win w) {

        // S7: also pull in minimized siblings when the member itself is no longer MINIMIZED,
        // otherwise a SnapGroup could stay half-restored and half-parked behind the taskbar.
        if(!restoringSnapGroup){
            List<Win> group=snapController.groupMembers(w);
            boolean anyMinimized=false;
            for(Win member:group)if(member.state==Win.State.MINIMIZED)anyMinimized=true;
            if(group.size()>1&&anyMinimized){
                restoringSnapGroup=true;
                try{for(Win member:group)if(member.state==Win.State.MINIMIZED)restore(member);}
                finally{restoringSnapGroup=false;}
                // WC2-14: the group branch has handled the whole group. Falling through
                // used to issue a second restore for w itself whenever w was already
                // restored while its siblings were still minimized.
                return;
            }
        }

        if (w.stateBeforeMinimize == Win.State.FULLSCREEN) {

            Rect target=new Rect(0,0,screenWidth,screenHeight);

            moveToStackThenResize(w,fullscreenStackId,true,target,Win.State.FULLSCREEN,()->{prefs.clearSavedWindow(w.taskId);w.minimizeRestoreBounds.setEmpty();focus(w);});

            return;

        }

        Rect target;Win.State restoreState=w.stateBeforeMinimize;

        if(restoreState==Win.State.MAXIMIZED)target=workArea();

        else if(restoreState==Win.State.SNAPPED){

            Rect snap=w.minimizeRestoreBounds.width()>0&&w.minimizeRestoreBounds.height()>0?new Rect(w.minimizeRestoreBounds):

                    (w.snapGroupId==null?null:snapController.boundsFor(w.snapGroupId,w.snapCell));

            target=snap!=null?snap:(w.normalBounds.width()>0?new Rect(w.normalBounds):fallbackNormalBounds());

        }else target=w.normalBounds.width()>0&&w.normalBounds.height()>0?new Rect(w.normalBounds):fallbackNormalBounds();

        final Win.State finalRestoreState=restoreState;

        target=clamp(target);

        moveToStackThenResize(w, freeformStackId, true, target, finalRestoreState, () -> {prefs.clearSavedWindow(w.taskId);w.minimizeRestoreBounds.setEmpty();focus(w);});

    }

    /** MAX follows the selected profile: Windows mode restores NORMAL; otherwise it cycles MAX/FULLSCREEN. */

    public void toggleMaximize(Win w) {

        if(w==null)return;

        switch (w.state) {

            case NORMAL:

            case SNAPPED: maximize(w); break;

            case MAXIMIZED: if(windowsButtonBehavior())restoreNormal(w);else fullscreen(w); break;

            case FULLSCREEN: maximize(w); break;

            case MINIMIZED: restore(w); break;

        }

    }

    public void toggleFullscreen(Win w) {

        if (w.state == Win.State.FULLSCREEN) maximize(w);

        else fullscreen(w);

    }

    public void toggleNormalMax(Win w) {

        if(w.state==Win.State.MAXIMIZED)restoreNormal(w);

        else if(w.state==Win.State.FULLSCREEN)maximize(w);

        else if(w.state==Win.State.MINIMIZED)restore(w);

        else maximize(w);

    }

    public void restoreNormal(Win w) {

        if(dockedPair!=null&&dockedPair.contains(w)){dockedPair.exit(w,()->restoreNormal(w));return;}

        // WC2-11: detach only after the restore is confirmed (same pattern as maximize()).
        final boolean wasSnapped = w.state == Win.State.SNAPPED;

        Rect target = w.normalBounds.width() > 0 && w.normalBounds.height() > 0

                ? new Rect(w.normalBounds) : fallbackNormalBounds();

        moveToStackThenResize(w, freeformStackId, true, clamp(target), Win.State.NORMAL,
                () -> { if (wasSnapped) snapController.detach(w); focus(w); });

    }

    /** Returns a maximized/snapped task to its saved window rectangle under the pointer. */

    public void restoreToNormal(Win w, Rect bounds) {

        // WC2-11: detach only after the restore is confirmed (same pattern as maximize()).
        final boolean wasSnapped = w.state == Win.State.SNAPPED;

        Rect target = clamp(bounds);

        // S6: the model must not claim NORMAL/freeform until moveToStack actually succeeded.
        // The old code mutated state and stackId up front; when the task was FULLSCREEN (stack 1)
        // with no stack-2 resize available, the window was reported as restored while still parked.
        w.requestedBounds.set(target);

        // The restored rectangle is remembered here; focus is left to the caller (a drag, a menu
        // action, or the snap cell-eviction path, which must not steal focus from the dragged window).
        moveToStackThenResize(w, freeformStackId, true, target, Win.State.NORMAL,
                () -> { if (wasSnapped) snapController.detach(w); w.normalBounds.set(target); });

    }

    public void requestMove(Win w, Rect bounds) {

        requestBounds(w, clamp(bounds), 1);

    }

    private void requestBounds(Win w, Rect target, int mode) {

        w.requestedBounds.set(target);

        final long seq = ++w.resizeSequence;

        root.resize(w.taskId, target.left, target.top, target.right, target.bottom, mode, result -> {

            if (windows.get(w.taskId) != w || seq != w.resizeSequence) return;

            if (!result.optBoolean("ok", false)) {

                // WC2-15: this request is still the current one (sequence check above), so
                // its target was rejected; do not leave the rejected rectangle queued for
                // commitMove() or the keyboard handler to resend later.
                w.requestedBounds.set(w.bounds);

                if (!result.optString("error", "").contains("superseded")) report(result, null);

                return;

            }

            Rect actual = readConfirmedBounds(result);

            if (actual != null) {

                w.bounds.set(actual);

                w.requestedBounds.set(actual);

                if (w.state == Win.State.NORMAL) w.normalBounds.set(actual);

                notifyListeners();

            } else refreshTasks(null);

        });

    }

    public void commitMove(Win w) {

        Rect target = w.requestedBounds.width() > 0 && w.requestedBounds.height() > 0

                ? clamp(w.requestedBounds) : clamp(w.bounds);

        requestBounds(w, target, 0);

    }

    /** D-02: docked-pair bounds already span the full display and are validated by
     *  DockedPairManager; the freeform clamp() would shrink them by the taskbar height and
     *  the status-bar inset every time the divider is released. */
    public void commitDockedBounds(Win w) {

        Rect target = w.requestedBounds.width() > 0 && w.requestedBounds.height() > 0

                ? new Rect(w.requestedBounds) : new Rect(w.bounds);

        requestBounds(w, target, 0);

    }

    private Rect readConfirmedBounds(JSONObject response) {

        JSONArray a = response.optJSONArray("bounds");

        if (a == null || a.length() != 4) return null;

        // WC2-18: validate strictly; a malformed or partial root response must not
        // replace a valid rectangle with a zero-size or inverted one.
        try {

            int left = a.getInt(0), top = a.getInt(1), right = a.getInt(2), bottom = a.getInt(3);

            if (right <= left || bottom <= top) return null;

            return new Rect(left, top, right, bottom);

        } catch (Exception e) { return null; }

    }

    public void close(Win w) {

        if(dockedPair!=null&&dockedPair.contains(w)){dockedPair.exit(w,()->close(w));return;}

        // WC2-11: no upfront snap detach. If closeTask fails the window must stay in its
        // group; on success the adoptTasks() removal pass detaches it centrally.
        final long operation = nextOperation(w);

        Rect b=w.hasUsableBounds()?new Rect(w.bounds):workArea();

        Rect target=new Rect(b);

        animator.transition(w,target,WindowAnimator.Kind.CLOSE,()->root.closeTask(w.taskId, result -> {

            if (!operationCurrent(w, operation)) return;

            if (!result.optBoolean("ok", false)) { report(result, null); return; }

            // D-12: do not remove the window from the model here. The next adoptTasks() pass
            // sees the task disappear and centrally cleans showDesktopRestoreIds,
            // pendingMinimizeIds, pendingDesktopRestoreIds, virtual_hidden_tasks and prefs;
            // removing it here first made adoptTasks skip all of that cleanup.
            refreshTasks(null);

        }));

    }

    private void moveToStackThenResize(Win w, int stack, boolean top, Rect target, Win.State targetState, Runnable after) {

        final int oldStack = w.stackId;
        final long operation = nextOperation(w);
        // R-04: a minimized window is parked in its stack with toTop=false; rolling a failed
        // operation back must not raise it to the front as a side effect.
        final boolean rollbackToTop = w.state != Win.State.MINIMIZED;

        Runnable change=()->{

            Runnable resize = () -> root.resize(w.taskId, target.left, target.top, target.right, target.bottom, 0, result -> {

                if (!operationCurrent(w, operation)) return;

                if (!result.optBoolean("ok", false)) {
                    // H-03: a resize superseded by a newer one (RootBridge coalesce) is not
                    // a failure: the newer request owns the window, so no rollback/report.
                    if ("superseded by newer resize".equals(result.optString("error"))) return;
                    report(result, null);
                    // WC2-13: the native task was already moved to the target stack; without
                    // a rollback it stays there while the model keeps the old stack/state.
                    if (oldStack != stack) root.moveToStack(w.taskId, oldStack, rollbackToTop, rolledBack -> refreshTasks(null));
                    else refreshTasks(null);
                    return;
                }

                Rect actual = readConfirmedBounds(result);

                w.bounds.set(actual != null ? actual : target);

                w.requestedBounds.set(w.bounds);

                w.stackId = stack;

                w.state = targetState;

                w.visible = true;

                if(targetState==Win.State.NORMAL)w.normalBounds.set(w.bounds);

                // R-05: run the continuation first (e.g. the deferred snap detach from
                // WC2-11), so listeners are never notified with the pre-detach snap state.
                if (after != null) after.run();

                notifyListeners();

            });

            if (w.stackId != stack) {

                root.moveToStack(w.taskId, stack, top, result -> {

                    if (!operationCurrent(w, operation)) return;

                    if (!result.optBoolean("ok", false)) { report(result, null); return; }

                    resize.run();

                });

            } else resize.run();

        };

        animator.transition(w,target,targetState==Win.State.SNAPPED?WindowAnimator.Kind.SNAP:WindowAnimator.Kind.RESTORE,change);

    }

    private Rect fallbackNormalBounds() {

        Rect work = workArea();

        // WC2-16: these limits are dp like the rest of the geometry; as raw pixels the
        // fallback window came out smaller than intended on higher-density panels.
        int width = Math.min(dp(640), Math.max(dp(240), work.width() * 2 / 3));

        int height = Math.min(dp(480), Math.max(dp(160), work.height() * 2 / 3));

        // WC2-17: centre inside the work area; its origin is not always (0,0).
        int left = work.left + Math.max(0, (work.width() - width) / 2);

        int top = work.top + Math.max(0, (work.height() - height) / 3);

        return new Rect(left, top, Math.min(work.right, left + width), Math.min(work.bottom, top + height));

    }

    private Rect clamp(Rect r) {

        Rect work = workArea();

        // S43: the old constants were raw pixels; on a 1.5x panel they shrank the tap-through
        // margin to ~32 px and the minimum window to 160x107 dp.
        int minW = Math.min(dp(240), work.width());

        int minH = Math.min(dp(160), work.height());

        int edge = dp(48);
        int title = dp(32);
        int width = Math.max(minW, Math.min(r.width(), work.width()));

        int height = Math.max(minH, Math.min(r.height(), work.height()));

        int left = Math.max(work.left - width + edge, Math.min(r.left, work.right - edge));

        int top = Math.max(work.top, Math.min(r.top, work.bottom - title));

        left = Math.min(left, work.right - edge);

        top = Math.min(top, work.bottom - minH);

        return new Rect(left, top, left + width, top + height);

    }

    /** S44: lets the settings screen push a theme/profile change without faking a task update. */
    public void refreshUi(){notifyListeners();}

    private void report(JSONObject result, Result callback) {

        String error = result.optString("error", "root operation failed");

        if (callback != null) callback.onResult(false, error);

        else if (errorListener != null) errorListener.onError(error);

    }

    private void notifyListeners() {

        List<Win> copy = snapshot();

        // H-05: snap and pair dividers must not float above a fullscreen window.
        if (snapController != null) snapController.syncVisibility(copy);

        if (dockedPair != null) dockedPair.syncVisibility(copy);

        for (Listener listener : new ArrayList<>(listeners)) listener.onWindowsChanged(copy);

    }

}
