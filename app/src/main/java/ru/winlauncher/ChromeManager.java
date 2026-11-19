package ru.winlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Opaque, segmented title overlays and input handling for visible freeform tasks. */
public final class ChromeManager implements WindowController.Listener {
private static final int TITLE_DRAG_SLOP_DP=8,FULLSCREEN_EDGE_DWELL_DP=8,SHAKE_STEP_DP=40,SHAKE_REVERSALS_REQUIRED=3;
private static final long TITLE_DOUBLE_TAP_MS=350L,TITLE_LONG_PRESS_MS=500L,SNAP_HOVER_DELAY_MS=500L,FULLSCREEN_DWELL_MS=700L,SHAKE_WINDOW_MS=800L;
private static volatile ChromeManager active;
public static boolean isChromeEnabled(){ChromeManager m=active;return m!=null&&m.enabled;}
public static void showSystemMenuForFocused(){ChromeManager m=active;if(m!=null)m.showMenuForFocused();}
public static void showLayoutsForFocused(){ChromeManager m=active;if(m==null)return;Win w=m.controller.focusedWindow();if(w==null)return;Chrome c=m.chromes.get(w.taskId);View anchor=c==null?null:c.root;if(anchor!=null)m.controller.snapController().showLayouts(w,anchor);else m.showMenuForFocused();}
public static boolean hasEscapeOverlay(){ChromeManager m=active;return m!=null&&(m.activePopup!=null&&m.activePopup.isShowing()||m.floatingSystemMenu!=null);}
public static boolean dismissOverlayForEscape(){ChromeManager m=active;if(m==null)return false;if(m.activePopup!=null&&m.activePopup.isShowing()){m.activePopup.dismiss();return true;}if(m.floatingSystemMenu!=null){try{m.windowManager.removeView(m.floatingSystemMenu);}catch(RuntimeException ignored){}m.floatingSystemMenu=null;return true;}return false;}
public interface StatusListener { void onStatus(String message); }

private final Context context;
private final WindowController controller;
private final WindowManager windowManager;
private final ResizeGripManager grips;
private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
private final Map<Integer,Chrome> chromes=new HashMap<>();
private final List<Integer> zOrder=new ArrayList<>();
private int titleHeight,buttonWidth;
private final float density;
private final LauncherPrefs prefs;
private int captionLeftInset,captionRightInset;
private boolean enabled;
private int draggingTaskId=-1; // F-11: replaces the BUG-16 boolean drag flag; per-task id lets removeChrome reset a dead drag
private boolean zOrderDirty; // H-04: a z-order rebuild was skipped because a drag was in progress
private boolean rendering; // I-03: prevents re-entrant render() from removeChrome()
private DragTouch activeDragTouch;
private View shakePreview,floatingSystemMenu;
private PopupWindow activePopup;
private StatusListener statusListener;

public ChromeManager(Context context,WindowController controller){
    this.context=context.getApplicationContext();this.controller=controller;
    windowManager=(WindowManager)this.context.getSystemService(Context.WINDOW_SERVICE);
    grips=new ResizeGripManager(this.context,controller);density=this.context.getResources().getDisplayMetrics().density;
    prefs=new LauncherPrefs(this.context);titleHeight=Math.max(dp(prefs.mouseProfile()?32:40),prefs.captionHeight(dp(prefs.mouseProfile()?32:40)));
    captionLeftInset=prefs.captionLeftInset();captionRightInset=prefs.captionRightInset();buttonWidth=dp(prefs.mouseProfile()?46:52);
    active=this;controller.addListener(this);
}
public void setStatusListener(StatusListener listener){statusListener=listener;}
public boolean hasOverlayPermission(){return android.os.Build.VERSION.SDK_INT<23||Settings.canDrawOverlays(context);}
public void enable(){active=this;enabled=true;if(!hasOverlayPermission()){status("Нет разрешения SYSTEM_ALERT_WINDOW.");return;}grips.setEnabled(true);render(controller.snapshot());}
public void disable(){enabled=false;if(active==this)active=null;grips.setEnabled(false);removeAll();dismissShakePreview();
    // S27: a system menu opened from the keyboard outlives its chrome; close it with the manager.
    if(activePopup!=null){try{activePopup.dismiss();}catch(RuntimeException ignored){}activePopup=null;}
    if(floatingSystemMenu!=null){try{windowManager.removeView(floatingSystemMenu);}catch(RuntimeException ignored){}floatingSystemMenu=null;}}
public boolean isEnabled(){return enabled;}
@Override public void onWindowsChanged(List<Win>windows){if(enabled&&hasOverlayPermission())render(windows);}

private void render(List<Win>windows){
    if(rendering)return;
    rendering=true;
    try{
        syncCaptionCalibration();
        // H-05: no title bars above a fullscreen window.
        for(Win w:windows)if(w.visible&&w.state==Win.State.FULLSCREEN){removeAll();grips.update(windows,-1);return;}
        ArrayList<Win> eligible=new ArrayList<>();
        for(Win w:windows)if(w.stackId==controller.freeformStackId()&&w.visible&&w.state!=Win.State.FULLSCREEN&&w.state!=Win.State.MINIMIZED&&w.hasUsableBounds())eligible.add(w);
        Collections.sort(eligible,Comparator.comparingInt(w->w.z));
        ArrayList<Integer> desired=new ArrayList<>();int activeId=eligible.isEmpty()?-1:eligible.get(eligible.size()-1).taskId;
        for(Win w:eligible)desired.add(w.taskId);
        for(Integer id:new ArrayList<>(chromes.keySet()))if(!desired.contains(id))removeChrome(id);
        for(int index=0;index<eligible.size();index++){
            Win w=eligible.get(index);Chrome c=chromes.get(w.taskId);
            if(c==null){c=new Chrome(w);chromes.put(w.taskId,c);try{windowManager.addView(c.root,c.params);}catch(RuntimeException e){chromes.remove(w.taskId);status("Не удалось добавить заголовок: "+e.getMessage());return;}}
            c.bind(w);c.setActive(w.taskId==activeId);updatePosition(c,w.bounds,eligible,index);
        }
        // BUG-16: removeView/addView on every chrome interrupts an in-progress title drag
        // (ACTION_CANCEL) and detaches open popups; defer the rebuild until the drag ends.
        if(!zOrder.equals(desired)&&draggingTaskId==-1){
            for(Integer id:desired){Chrome c=chromes.get(id);if(c==null)continue;try{windowManager.removeView(c.root);}catch(RuntimeException ignored){}try{windowManager.addView(c.root,c.params);}catch(RuntimeException e){status("Не удалось обновить порядок заголовков: "+e.getMessage());}}
            zOrder.clear();zOrder.addAll(desired);
            zOrderDirty=false;
        }else if(!zOrder.equals(desired))zOrderDirty=true; // H-04: rebuild deferred until the drag ends
        grips.update(windows,activeId);
    }finally{
        rendering=false;
    }
}

private void syncCaptionCalibration(){
    boolean mouse=prefs.mouseProfile();int h=Math.max(dp(mouse?32:40),prefs.captionHeight(dp(mouse?32:40)));
    int bw=dp(mouse?46:52),left=prefs.captionLeftInset(),right=prefs.captionRightInset();
    boolean changed=h!=titleHeight||bw!=buttonWidth;titleHeight=h;buttonWidth=bw;captionLeftInset=left;captionRightInset=right;
    if(changed)for(Chrome c:chromes.values()){
        for(int i=0;i<c.root.getChildCount();i++){View child=c.root.getChildAt(i);android.view.ViewGroup.LayoutParams lp=child.getLayoutParams();lp.height=titleHeight;if(child==c.minimize||child==c.maximize||child==c.close)lp.width=buttonWidth;child.setLayoutParams(lp);}
        c.params.height=titleHeight;try{windowManager.updateViewLayout(c.root,c.params);}catch(RuntimeException ignored){}
    }
}

/** Select one unoccluded contiguous caption segment, preferring the segment containing all controls. */
private void updatePosition(Chrome c,Rect b,List<Win> zOrdered,int index){
    int left=b.left-captionLeftInset,right=b.right+captionRightInset,top=b.top,bottom=top+titleHeight;
    ArrayList<int[]> segments=new ArrayList<>();segments.add(new int[]{left,right});
    for(int i=index+1;i<zOrdered.size();i++){
        Rect cover=zOrdered.get(i).bounds;if(cover.bottom<=top||cover.top>=bottom)continue;
        int cutL=Math.max(left,cover.left-captionLeftInset),cutR=Math.min(right,cover.right+captionRightInset);if(cutR<=cutL)continue;
        ArrayList<int[]> next=new ArrayList<>();for(int[] s:segments){
            if(cutR<=s[0]||cutL>=s[1])next.add(s);else{if(cutL>s[0])next.add(new int[]{s[0],cutL});if(cutR<s[1])next.add(new int[]{cutR,s[1]});}}
        segments=next;if(segments.isEmpty())break;
    }
    int[] chosen=null;int controls=buttonWidth*3+dp(10); // three controls plus the chrome's left inset
    for(int[] s:segments)if(s[1]-s[0]>=controls&&(chosen==null||s[1]-s[0]>chosen[1]-chosen[0]))chosen=s;
    if(chosen==null)for(int[] s:segments)if(chosen==null||s[1]-s[0]>chosen[1]-chosen[0])chosen=s;
    if(chosen==null||chosen[1]-chosen[0]<dp(24)){c.root.setVisibility(View.INVISIBLE);return;}
    boolean buttons=chosen[1]-chosen[0]>=controls;
    c.root.setVisibility(View.VISIBLE);c.setVisibleSegment(buttons,chosen[1]-chosen[0]);
    int x=chosen[0],width=chosen[1]-chosen[0];if(c.lastLeft==x&&c.lastTop==top&&c.lastWidth==width)return;
    c.params.x=x;c.params.y=top;c.params.width=width;c.params.height=titleHeight;c.lastLeft=x;c.lastTop=top;c.lastWidth=width;
    try{if(c.root.getParent()!=null)windowManager.updateViewLayout(c.root,c.params);}catch(RuntimeException e){status("Не удалось обновить заголовок: "+e.getMessage());}
}
private void finishDragZOrder(){
    if(zOrderDirty&&draggingTaskId==-1&&!rendering){zOrderDirty=false;render(controller.snapshot());}
}
private void removeChrome(int id){
    Chrome c=chromes.remove(id);
    if(c!=null){
        handler.removeCallbacks(c.showLayoutFlyout);
        try{
            if(c.root.getParent()!=null)windowManager.removeView(c.root);
        }catch(RuntimeException ignored){
        }
    }
    zOrder.remove((Integer)id);
    // F-11 / I-03: a window closed mid-drag must clear drag state and shake preview
    // before any deferred z-order rebuild, and must not re-enter render() synchronously.
    if(draggingTaskId==id){
        draggingTaskId=-1;
        dismissShakePreview();
        if(activeDragTouch!=null){
            handler.removeCallbacks(activeDragTouch.longPress);
            handler.removeCallbacks(activeDragTouch.fullscreenDwell);
            activeDragTouch.moving=false;
            activeDragTouch.focusOnly=false;
            activeDragTouch=null;
        }
        finishDragZOrder();
    }
}
private void removeAll(){for(Integer id:new ArrayList<>(chromes.keySet()))removeChrome(id);zOrder.clear();}

private void showMenuForFocused(){Win w=controller.focusedWindow();if(w==null)return;Chrome c=chromes.get(w.taskId);if(c!=null)showSystemMenu(c);else showFullscreenSystemMenu(w);}
private boolean focusOnlyIfNeeded(Win w){Win focused=controller.focusedWindow();if(focused!=w){controller.focus(w);return true;}return false;}

private void showFullscreenSystemMenu(Win w){
    if(floatingSystemMenu!=null)try{windowManager.removeView(floatingSystemMenu);}catch(RuntimeException ignored){}
    LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(6),dp(6),dp(6),dp(6));
    GradientDrawable bg=new GradientDrawable();bg.setColor(UiTokens.surface(prefs.darkTheme()));bg.setCornerRadius(0);panel.setBackground(bg);
    ArrayList<String> labels=new ArrayList<>();if(controller.isDockedPairMember(w))labels.add("Выйти из split");labels.add("Окно поверх рабочего стола");labels.add("Свернуть");labels.add("Закрыть");
    for(String label:labels){TextView row=menuRow(label);row.setOnClickListener(v->{try{windowManager.removeView(panel);}catch(RuntimeException ignored){}floatingSystemMenu=null;
        if(label.equals("Выйти из split"))controller.exitDockedPair(w);else if(label.equals("Окно поверх рабочего стола"))controller.maximize(w);else if(label.equals("Свернуть"))controller.minimize(w);else controller.close(w);
    });panel.addView(row,new LinearLayout.LayoutParams(-1,dp(36)));}
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(240),-2,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.OPAQUE);
    p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=controller.topInset()+dp(48);p.setTitle("WinLauncher fullscreen system menu");
    panel.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){try{windowManager.removeView(panel);}catch(RuntimeException ignored){}floatingSystemMenu=null;return true;}return false;});
    try{windowManager.addView(panel,p);floatingSystemMenu=panel;}catch(RuntimeException ignored){floatingSystemMenu=null;}
}

private void showSystemMenu(Chrome chrome){
    ArrayList<String> labels=new ArrayList<>();Collections.addAll(labels,"Восстановить","Переместить","Изменить размер","Свернуть","Развернуть","На весь экран","Привязать влево","Привязать вправо","Snap Layouts");
    if(chrome.model.snapGroupId!=null)labels.add("Свернуть группу");labels.add("Закрыть");
    LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(6),dp(6),dp(6),dp(6));
    GradientDrawable bg=new GradientDrawable();bg.setColor(UiTokens.surface(prefs.darkTheme()));bg.setCornerRadius(UiTokens.RADIUS_MEDIUM);bg.setStroke(dp(1),UiTokens.outlineSoft(prefs.darkTheme()));panel.setBackground(bg);
    // M-02: only one system menu at a time.
    if(activePopup!=null){try{activePopup.dismiss();}catch(RuntimeException ignored){}activePopup=null;}
    PopupWindow popup=new PopupWindow(panel,dp(220),-2,true);popup.setBackgroundDrawable(bg);popup.setOutsideTouchable(true);activePopup=popup;
    popup.setOnDismissListener(()->{if(activePopup==popup)activePopup=null;});
    for(String label:labels){TextView row=menuRow(label);row.setOnClickListener(v->{popup.dismiss();Win w=chrome.model;switch(label){
        case "Восстановить":controller.restoreNormal(w);break;case "Переместить":controller.beginKeyboardMove(w);instruction("Переместить: стрелки · Enter — готово · Esc — отмена");break;case "Изменить размер":controller.beginKeyboardResize(w);instruction("Размер: стрелки · Shift ускоряет · Enter — готово · Esc — отмена");break;
        case "Свернуть":controller.minimize(w);break;case "Развернуть":controller.maximize(w);break;case "На весь экран":controller.fullscreen(w);break;
        case "Привязать влево":controller.snapHalf(w,true);break;case "Привязать вправо":controller.snapHalf(w,false);break;
        case "Snap Layouts":controller.snapController().showLayouts(w,chrome.dragArea);break;case "Свернуть группу":controller.snapController().minimizeGroup(w);break;case "Закрыть":controller.close(w);break;
    }});panel.addView(row,new LinearLayout.LayoutParams(-1,dp(32)));}
    popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT); // M-02
    try{popup.showAsDropDown(chrome.dragArea,0,0);}catch(RuntimeException ignored){}
}
private TextView menuRow(String label){
    TextView row=new TextView(context);row.setText(label);row.setTextSize(UiTokens.TEXT_LABEL);row.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));row.setTextColor(UiTokens.textPrimary(prefs.darkTheme()));row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(dp(10),0,dp(10),0);row.setMinHeight(dp(UiTokens.MENU_ROW_HEIGHT));row.setBackgroundColor(UiTokens.surface(prefs.darkTheme()));
    GradientDrawable normal=rounded(UiTokens.surface(prefs.darkTheme())),hover=rounded(UiTokens.surfaceHover(prefs.darkTheme())),pressed=rounded(UiTokens.surfacePressed(prefs.darkTheme()));
    row.setBackground(normal);
    row.setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)v.setBackground(hover);else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)v.setBackground(normal);return false;});
    row.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.setBackground(pressed);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?hover:normal);return false;});return row;
}
private GradientDrawable rounded(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(UiTokens.RADIUS_MEDIUM);return d;
}
private void showShakePreview(){
    dismissShakePreview();boolean dark=prefs.darkTheme();TextView chip=new TextView(context);chip.setText("Отпустите, чтобы свернуть остальные окна");chip.setTextSize(UiTokens.TEXT_LABEL);chip.setTextColor(UiTokens.textOnSurface(dark));chip.setPadding(dp(16),dp(10),dp(16),dp(10));
    GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff2b2b2b:0xfff9f9f9);bg.setCornerRadius(0);bg.setStroke(dp(1),0xff888888);chip.setBackground(bg);
    WindowManager.LayoutParams p=new WindowManager.LayoutParams(-2,-2,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
    p.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;p.y=controller.topInset()+dp(48);p.setTitle("WinLauncher shake preview");try{windowManager.addView(chip,p);shakePreview=chip;}catch(RuntimeException ignored){shakePreview=null;}
}
private void dismissShakePreview(){if(shakePreview!=null)try{if(shakePreview.getParent()!=null)windowManager.removeView(shakePreview);}catch(RuntimeException ignored){}shakePreview=null;}
private void status(String message){if(statusListener!=null)statusListener.onStatus(message);}
private void instruction(String message){status(message);Toast.makeText(context,message,Toast.LENGTH_LONG).show();}
private int dp(float value){return(int)(value*density+.5f);}

private final class Chrome {
    final LinearLayout root,dragArea;final TextView title,minimize,maximize,close;final WindowManager.LayoutParams params;
    Win model;int lastLeft=Integer.MIN_VALUE,lastTop=Integer.MIN_VALUE,lastWidth=-1;boolean active,hoverLayoutArmed;Runnable showLayoutFlyout;
    Chrome(Win model){
        this.model=model;root=new LinearLayout(context);root.setOrientation(LinearLayout.HORIZONTAL);root.setGravity(Gravity.CENTER_VERTICAL);root.setPadding(dp(10),0,0,0);root.setBackground(headerBackground(UiTokens.chromeFocused(false)));
        dragArea=new LinearLayout(context);dragArea.setOrientation(LinearLayout.HORIZONTAL);dragArea.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon=new ImageView(context);icon.setImageDrawable(loadIcon(model.packageName));dragArea.addView(icon,new LinearLayout.LayoutParams(dp(16),dp(16)));
        icon.setOnClickListener(v->{if(!focusOnlyIfNeeded(this.model))showSystemMenu(this);});
        title=new TextView(context);title.setTextSize(UiTokens.TEXT_CAPTION);title.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));title.setSingleLine(true);title.setEllipsize(TextUtils.TruncateAt.END);title.setPadding(dp(10),0,dp(4),0);dragArea.addView(title,new LinearLayout.LayoutParams(0,-1,1f));
        root.addView(dragArea,new LinearLayout.LayoutParams(0,titleHeight,1f));
        minimize=makeButton("—");maximize=makeButton("□");close=makeButton("×");
        minimize.setContentDescription("Свернуть окно");close.setContentDescription("Закрыть окно");
        showLayoutFlyout=()->controller.snapController().showLayouts(this.model,maximize);
        root.addView(minimize,new LinearLayout.LayoutParams(buttonWidth,titleHeight));root.addView(maximize,new LinearLayout.LayoutParams(buttonWidth,titleHeight));root.addView(close,new LinearLayout.LayoutParams(buttonWidth,titleHeight));
        minimize.setOnClickListener(v->{if(!focusOnlyIfNeeded(this.model))controller.minimize(this.model);});
        maximize.setOnClickListener(v->{if(!focusOnlyIfNeeded(this.model))controller.toggleMaximize(this.model);});
        close.setOnClickListener(v->{if(!focusOnlyIfNeeded(this.model))controller.close(this.model);});
        maximize.setOnLongClickListener(v->{if(focusOnlyIfNeeded(this.model))return true;controller.snapController().showLayouts(this.model,maximize);return true;});
        dragArea.setOnLongClickListener(v->{if(focusOnlyIfNeeded(this.model))return true;showSystemMenu(this);return true;});
        // M-02: no OnContextClickListener here — DragTouch BUTTON_SECONDARY is the only right-click path.
        dragArea.setOnHoverListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){handler.removeCallbacks(showLayoutFlyout);hoverLayoutArmed=false;}
            else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE){boolean center=e.getX()>=v.getWidth()*.35f&&e.getX()<=v.getWidth()*.75f;if(center&&!hoverLayoutArmed){hoverLayoutArmed=true;handler.postDelayed(showLayoutFlyout,SNAP_HOVER_DELAY_MS);}else if(!center&&hoverLayoutArmed){handler.removeCallbacks(showLayoutFlyout);hoverLayoutArmed=false;}}
            return false;
        });
        dragArea.setOnTouchListener(new DragTouch(this));
        params=new WindowManager.LayoutParams(Math.max(dp(160),model.bounds.width()),titleHeight,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.x=model.bounds.left;params.y=model.bounds.top;params.setTitle("WinLauncher chrome "+model.taskId);
    }
    void bind(Win w){
        // S24: bind() runs on every poll/render; re-resolving the icon each time was pure garbage.
        boolean packageChanged=model==null||!w.packageName.equals(model.packageName);
        model=w;title.setText(w.title==null?"Окно":w.title);
        if(packageChanged)((ImageView)dragArea.getChildAt(0)).setImageDrawable(loadIcon(w.packageName));
        switch(w.state){
            case MAXIMIZED:
                if(controller.windowsButtonBehavior()){maximize.setText("⧉");maximize.setContentDescription("Восстановить обычный размер");}
                else{maximize.setText("⛶");maximize.setContentDescription("Перейти в полноэкранный режим");}
                break;
            case FULLSCREEN:maximize.setText("⧉");maximize.setContentDescription("Вернуть окно к максимальному размеру");break;
            case MINIMIZED:maximize.setText("⧉");maximize.setContentDescription("Восстановить окно");break;
            case NORMAL:
            case SNAPPED:
            default:maximize.setText("□");maximize.setContentDescription("Развернуть окно");break;
        }
    }
    void setVisibleSegment(boolean showButtons,int width){
        int visibility=showButtons?View.VISIBLE:View.GONE;minimize.setVisibility(visibility);maximize.setVisibility(visibility);close.setVisibility(visibility);
        LinearLayout.LayoutParams drag=(LinearLayout.LayoutParams)dragArea.getLayoutParams();if(showButtons){drag.width=0;drag.weight=1f;minimize.getLayoutParams().width=buttonWidth;maximize.getLayoutParams().width=buttonWidth;close.getLayoutParams().width=buttonWidth;}else{drag.width=Math.max(1,width);drag.weight=0f;}dragArea.setLayoutParams(drag);root.setClipChildren(true);
    }
    void setActive(boolean value){active=value;boolean dark=prefs.darkTheme();root.setBackground(headerBackground(dark?(value?UiTokens.chromeFocused(dark):UiTokens.chromeIdle(dark)):(value?UiTokens.chromeFocused(false):UiTokens.chromeIdle(false))));title.setTextColor(dark?(value?UiTokens.textOnAccent():UiTokens.textChromeIdle(dark)):(value?UiTokens.textPrimary(false):UiTokens.textChromeIdle(false)));int color=dark?(value?UiTokens.textPrimary(dark):UiTokens.textDisabled(dark)):(value?UiTokens.textOnSurface(false):UiTokens.textDisabled(false));minimize.setTextColor(color);maximize.setTextColor(color);close.setTextColor(color);
        // BUG-16: the buttons kept the background from construction time, so after a theme
        // switch the (now light) text sat on the old light background in the dark theme.
        int buttonBg=UiTokens.surface(dark);minimize.setBackgroundColor(buttonBg);maximize.setBackgroundColor(buttonBg);close.setBackgroundColor(buttonBg);}
    private GradientDrawable headerBackground(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(0);d.setStroke(dp(1),UiTokens.chromeBorder());return d;}
    private TextView makeButton(String text){
        TextView b=new TextView(context);b.setText(text);b.setTextSize(UiTokens.TEXT_GLYPH);b.setGravity(Gravity.CENTER);b.setTextColor(UiTokens.textPrimary(false));b.setBackgroundColor(UiTokens.surface(prefs.darkTheme()));
        b.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN){v.setBackgroundColor(v==close?UiTokens.dangerPressed():UiTokens.surfaceHover(prefs.darkTheme()));if(v==close)((TextView)v).setTextColor(UiTokens.textOnAccent());}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){v.setBackgroundColor(UiTokens.surface(prefs.darkTheme()));((TextView)v).setTextColor(prefs.darkTheme()?(active?UiTokens.textPrimary(true):UiTokens.textDisabled(true)):(active?UiTokens.textOnSurface(false):UiTokens.textDisabled(false)));if(v==maximize)handler.removeCallbacks(showLayoutFlyout);}return false;});
        b.setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER){v.setBackgroundColor(v==close?UiTokens.danger():UiTokens.surfaceHover(prefs.darkTheme()));if(v==close)((TextView)v).setTextColor(UiTokens.textOnAccent());if(v==maximize)handler.postDelayed(showLayoutFlyout,SNAP_HOVER_DELAY_MS);}else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){v.setBackgroundColor(UiTokens.surface(prefs.darkTheme()));((TextView)v).setTextColor(prefs.darkTheme()?(active?UiTokens.textPrimary(true):UiTokens.textDisabled(true)):(active?UiTokens.textOnSurface(false):UiTokens.textDisabled(false)));if(v==maximize)handler.removeCallbacks(showLayoutFlyout);}return false;});return b;
    }
    private Drawable loadIcon(String packageName){try{return context.getPackageManager().getApplicationIcon(packageName);}catch(Exception e){return context.getApplicationInfo().loadIcon(context.getPackageManager());}}
}

private final class DragTouch implements View.OnTouchListener {
    private final Chrome chrome;private float downX,downY;private final Rect startBounds=new Rect();private boolean moving;private Win.State initialState=Win.State.NORMAL;private long lastTap,topDwellStart;
    private boolean fullscreenGesture,menuShown,focusOnly,layoutZone;private float lastShakeX;private long shakeStart;private int shakeReversals,shakeDirection;private boolean shakePreviewShown;
    private final Runnable fullscreenDwell=()->{if(moving&&topDwellStart>0)fullscreenGesture=true;};
    private final Runnable longPress;
    DragTouch(Chrome chrome){this.chrome=chrome;this.longPress=()->{if(!moving&&!focusOnly){menuShown=true;if(layoutZone)controller.snapController().showLayouts(chrome.model,chrome.dragArea);else showSystemMenu(chrome);}};}
    @Override public boolean onTouch(View view,MotionEvent event){
        Win w=chrome.model;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                activeDragTouch=this;
                handler.removeCallbacks(fullscreenDwell);
                downX=event.getRawX();downY=event.getRawY();layoutZone=event.getX()>=view.getWidth()*.35f&&event.getX()<=view.getWidth()*.75f;startBounds.set(w.bounds);initialState=w.state;moving=false;topDwellStart=0;fullscreenGesture=false;menuShown=false;focusOnly=focusOnlyIfNeeded(w);
                lastShakeX=downX;shakeStart=android.os.SystemClock.uptimeMillis();shakeReversals=0;shakeDirection=0;shakePreviewShown=false;
                if(focusOnly)return true;if((event.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){menuShown=true;showSystemMenu(chrome);return true;}handler.postDelayed(longPress,TITLE_LONG_PRESS_MS);return true;
            case MotionEvent.ACTION_MOVE:{
                if(focusOnly)return true;float x=event.getRawX(),y=event.getRawY();
                if(!moving&&Math.hypot(x-downX,y-downY)<dp(TITLE_DRAG_SLOP_DP))return true;
                if(!moving){handler.removeCallbacks(longPress);moving=true;draggingTaskId=w.taskId;lastTap=0;if(w.state==Win.State.MAXIMIZED){Rect restore=restoredRectUnderPointer(w,x,y);controller.restoreToNormal(w,restore);startBounds.set(restore);downX=x;downY=y;return true;}else if(w.state==Win.State.SNAPPED){startBounds.set(w.bounds);}}
                updateFullscreenDwell(y);
                int dx=Math.round(x-downX),dy=Math.round(y-downY);trackShake(w,x);Rect next=new Rect(startBounds);next.offset(dx,dy);controller.requestMove(w,next);controller.snapController().updatePreview(w,x,y);return true;
            }
            case MotionEvent.ACTION_UP:
                if(draggingTaskId==w.taskId)draggingTaskId=-1;
                if(activeDragTouch==this)activeDragTouch=null;
                finishDragZOrder();
                if(focusOnly){handler.removeCallbacks(fullscreenDwell);focusOnly=false;lastTap=android.os.SystemClock.uptimeMillis();return true;}
                if(topDwellStart>0&&android.os.SystemClock.uptimeMillis()-topDwellStart>=FULLSCREEN_DWELL_MS)fullscreenGesture=true;
                handler.removeCallbacks(fullscreenDwell);handler.removeCallbacks(longPress);
                if(menuShown){menuShown=false;return true;}if(shakePreviewShown){dismissShakePreview();controller.snapController().hidePreview();if(moving){if(w.state==Win.State.SNAPPED)controller.requestMove(w,startBounds);else controller.commitMove(w);}controller.aeroShake(w);return true;}
                if(moving){if(fullscreenGesture)controller.fullscreen(w);else if(!controller.snapController().snapAt(w,event.getRawX(),event.getRawY())){if(event.getRawY()<=controller.workArea().top+dp(8))controller.maximize(w);else if(w.state==Win.State.SNAPPED){Rect target=w.requestedBounds.width()>0?new Rect(w.requestedBounds):new Rect(w.bounds);controller.restoreToNormal(w,target);}else controller.commitMove(w);}controller.snapController().hidePreview();}
                else{long now=android.os.SystemClock.uptimeMillis();if(now-lastTap<TITLE_DOUBLE_TAP_MS){controller.toggleNormalMax(w);lastTap=0;}else lastTap=now;}return true;
            case MotionEvent.ACTION_CANCEL:
                if(draggingTaskId==w.taskId)draggingTaskId=-1;
                if(activeDragTouch==this)activeDragTouch=null;
                finishDragZOrder();
                handler.removeCallbacks(longPress);handler.removeCallbacks(fullscreenDwell);dismissShakePreview();
                if(moving&&!focusOnly){
                    if(initialState==Win.State.MAXIMIZED)controller.maximize(w);
                    else{controller.requestMove(w,startBounds);controller.commitMove(w);}
                }
                focusOnly=false;controller.snapController().hidePreview();return true;
            default:return true;
        }
    }
    private void updateFullscreenDwell(float y){
        int edge=controller.workArea().top+dp(FULLSCREEN_EDGE_DWELL_DP);long now=android.os.SystemClock.uptimeMillis();
        if(moving&&y<=edge){
            if(topDwellStart==0){topDwellStart=now;handler.removeCallbacks(fullscreenDwell);handler.postDelayed(fullscreenDwell,FULLSCREEN_DWELL_MS);}
        }else{topDwellStart=0;fullscreenGesture=false;handler.removeCallbacks(fullscreenDwell);}
    }
    private void trackShake(Win w,float x){if(!prefs.shakeToMinimize())return;long now=android.os.SystemClock.uptimeMillis();if(now-shakeStart>SHAKE_WINDOW_MS){shakeStart=now;shakeReversals=0;shakeDirection=0;lastShakeX=x;}float delta=x-lastShakeX;int direction=Math.abs(delta)>=dp(SHAKE_STEP_DP)?(delta>0?1:-1):0;if(direction!=0){if(shakeDirection!=0&&direction!=shakeDirection)shakeReversals++;shakeDirection=direction;lastShakeX=x;if(shakeReversals>=SHAKE_REVERSALS_REQUIRED&&!shakePreviewShown){shakePreviewShown=true;showShakePreview();}}}
    private Rect restoredRectUnderPointer(Win w,float x,float y){Rect r=new Rect(w.normalBounds);if(r.width()<dp(240)||r.height()<dp(160)){android.util.DisplayMetrics dm=new android.util.DisplayMetrics();((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);int width=Math.min(dp(640),dm.widthPixels*2/3),height=Math.min(dp(480),dm.heightPixels*2/3);r.set(0,0,width,height);}float fraction=Math.max(.1f,Math.min(.9f,(x-w.bounds.left)/(float)Math.max(1,w.bounds.width())));int left=Math.round(x-fraction*r.width());int top=Math.max(0,Math.round(y-dp(12)));r.offsetTo(left,top);return r;}
}
}
