package ru.winlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Minimal Win11-like taskbar: task switch/minimize plus auto-hide in fullscreen. */
public final class TaskbarOverlay implements WindowController.Listener {
    public interface Actions { void openTaskView(); void openStart(); }
    private final Context context;
    private final WindowController controller;
    private final WindowManager wm;
    private final LauncherPrefs prefs;
    private final float density;
    private final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());
    private List<Win> lastWindows;
    private final Runnable hideRunnable=()->{if(isJumpMenuShowing()){renewTemporary();return;}temporary=false;render(lastWindows);}; // M-06
    // D-10: render() now runs only on real model changes (S4), so the clock needs its own
    // minute-aligned tick or it freezes while the device is idle.
    private static TaskbarOverlay activeInstance;
    private PopupWindow jumpMenu;

    public static boolean isJumpMenuShowing(){
        TaskbarOverlay bar=activeInstance;
        return bar!=null&&bar.jumpMenu!=null&&bar.jumpMenu.isShowing();
    }

    /** F-13: KeyService Escape can close the jump menu without knowing the instance. */
    public static boolean dismissJumpMenuForEscape(){
        TaskbarOverlay bar=activeInstance;
        if(bar==null||bar.jumpMenu==null)return false;
        bar.dismissJumpMenu();
        return true;
    }

    private void dismissJumpMenu(){
        PopupWindow popup=jumpMenu;
        jumpMenu=null;
        if(popup!=null){
            try{
                popup.dismiss();
            }catch(RuntimeException ignored){
            }
        }
    }
    private final Runnable clockTick=new Runnable(){@Override public void run(){
        if(!enabled)return;
        if(clock!=null)clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));
        long now=System.currentTimeMillis();handler.postDelayed(this,60000-(now%60000)+50);
    }};
    private LinearLayout bar;
    private TextView clock; // S28/S30: reused, so the clock neither re-inflates text nor freezes
    private boolean clockDark; // BUG-17: the cached clock is rebuilt when the theme flips
    private WindowManager.LayoutParams params;
    private boolean enabled;
    private boolean temporary;
    private Actions actions;

    public TaskbarOverlay(Context context,WindowController controller){
        this.context=context.getApplicationContext();this.controller=controller;this.wm=(WindowManager)this.context.getSystemService(Context.WINDOW_SERVICE);
        this.prefs=new LauncherPrefs(this.context);this.density=this.context.getResources().getDisplayMetrics().density;
        controller.setTaskbarHeight(dp(prefs.mouseProfile()?48:56));controller.addListener(this);
    }
    public void setActions(Actions a){actions=a;}
    public void enable(){enabled=true;handler.removeCallbacks(clockTick);handler.post(clockTick);render(controller.snapshot());}
    public void disable(){dismissJumpMenu();enabled=false;handler.removeCallbacks(clockTick);remove();}
    public void showTemporary(){temporary=true;render(lastWindows);renewTemporary();}
    private void renewTemporary(){handler.removeCallbacks(hideRunnable);handler.postDelayed(hideRunnable,2500);}
    @Override public void onWindowsChanged(List<Win> windows){lastWindows=windows;if(enabled)render(windows);}

    private void render(List<Win> windows){
        if(!enabled)return;
        controller.setTaskbarHeight(dp(prefs.mouseProfile()?48:56));
        if(windows==null)windows=controller.snapshot();
        ArrayList<Win> desktopWindows=new ArrayList<>();for(Win w:windows)if(w.desktopId==controller.currentDesktopId())desktopWindows.add(w);windows=desktopWindows;
        // D-10: buttons follow the stable taskbarOrder() (by taskId), not the z-order snapshot;
        // windows outside that set (temporary fullscreen) keep their relative order at the end.
        ArrayList<Win> ordered=new ArrayList<>(controller.taskbarOrder());for(Win w:windows)if(!ordered.contains(w))ordered.add(w);windows=ordered;
        boolean fullscreen=false;for(Win w:windows)if(w.state==Win.State.FULLSCREEN&&w.visible){fullscreen=true;break;}
        if(fullscreen&&!temporary){remove();return;}
        if(!fullscreen)temporary=false;
        if(bar==null)create();if(bar==null)return;
        // BUG-17: the bar background was only set in create(), so a theme switch left the
        // bar (and the cached clock) in the old theme until the bar was recreated.
        bar.setBackground(round(prefs.darkTheme()?0xff1c1c1c:0xfff3f3f3,0));
        bar.removeAllViews();
        LinearLayout.LayoutParams startLp=new LinearLayout.LayoutParams(dp(44),-1);startLp.setMarginEnd(dp(4));startLp.rightMargin=dp(4);
        TextView start=button("⊞",dp(44));start.setOnClickListener(v->{if(actions!=null)actions.openStart();});bar.addView(start,startLp);
        LinearLayout.LayoutParams tvLp=new LinearLayout.LayoutParams(dp(44),-1);tvLp.setMarginEnd(dp(4));tvLp.rightMargin=dp(4);
        TextView taskView=button("▧",dp(44));taskView.setOnClickListener(v->{if(actions!=null)actions.openTaskView();});bar.addView(taskView,tvLp);
        int activeId=-1;for(Win w:windows)if(w.visible&&w.state!=Win.State.MINIMIZED&&(activeId<0||w.z>=zOf(windows,activeId)))activeId=w.taskId;
        final int currentActiveId=activeId;
        int taskBtnW=dp(prefs.mouseProfile()?44:52);
        HorizontalScrollView scroller=new HorizontalScrollView(context);
        scroller.setHorizontalScrollBarEnabled(false);scroller.setFillViewport(true);
        LinearLayout tasks=new LinearLayout(context);tasks.setOrientation(LinearLayout.HORIZONTAL);
        for(Win w:windows){
            if(w.state==Win.State.FULLSCREEN&&!temporary)continue;
            if(w.stackId!=controller.freeformStackId()&&w.state!=Win.State.MINIMIZED&&!(temporary&&w.state==Win.State.FULLSCREEN))continue;
            String taskLabel=abbrev(w.title);
            if(w.state==Win.State.MINIMIZED)taskLabel="↗"+taskLabel;
            TextView item=button(taskLabel,taskBtnW);if(w.state==Win.State.MINIMIZED)item.setTextColor(prefs.darkTheme()?0xffb0b0b0:0xff555555);
            String taskTitle=w.title==null?"окно":w.title;
            item.setContentDescription(w.state==Win.State.MINIMIZED?"Восстановить окно: "+taskTitle:"Переключиться на окно: "+taskTitle);
            item.setOnClickListener(v->{if(w.state==Win.State.MINIMIZED)controller.restore(w);else if(w.state==Win.State.FULLSCREEN)controller.focus(w);else if(w.taskId==currentActiveId)controller.minimize(w);else controller.focus(w);});
            item.setOnLongClickListener(v->{showJumpMenu(item,w);return true;});
            attachTaskButtonBehavior(item,w,w.taskId==currentActiveId);
            // U-04 / I-04: keep the full 44/52dp hit width instead of collapsing to WRAP_CONTENT.
            LinearLayout.LayoutParams itemLp=new LinearLayout.LayoutParams(taskBtnW,-1);itemLp.setMarginEnd(dp(4));itemLp.rightMargin=dp(4);tasks.addView(item,itemLp);
        }
        scroller.addView(tasks,new android.widget.FrameLayout.LayoutParams(-2,-1));bar.addView(scroller,new LinearLayout.LayoutParams(0,-1,1f));
        if(clock!=null&&clockDark!=prefs.darkTheme())clock=null;
        if(clock==null){clock=button("",dp(64));clockDark=prefs.darkTheme();}
        LinearLayout.LayoutParams clockLp=new LinearLayout.LayoutParams(dp(64),-1);clockLp.setMarginStart(dp(4));clockLp.leftMargin=dp(4);
        clock.setText(new SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()));bar.addView(clock,clockLp);
        updatePosition(fullscreen);
    }

    private int zOf(List<Win> wins,int id){for(Win w:wins)if(w.taskId==id)return w.z;return -1;}

    // BUG-17: button() installs hover/touch listeners that know nothing about the active
    // window, and render() then overwrote the touch listener with the right-click handler —
    // losing the press highlight and letting hover-exit erase the active window's blue
    // background. One combined behavior now owns all three concerns.
        private void attachTaskButtonBehavior(TextView item,Win w,boolean isActive){
        boolean dark=prefs.darkTheme();
        GradientDrawable normal=round(dark?0xff2b2b2b:0xfff9f9f9,dp(4));
        // G-02: an active button carries white text, so its hover/pressed states stay blue.
        GradientDrawable hover=round(isActive?0xff005a9e:(dark?0xff454545:0xffe5e5e5),dp(4));
        GradientDrawable pressed=round(isActive?0xff004b85:(dark?0xff555555:0xffe0e0e0),dp(4));
        GradientDrawable activeBg=round(0xff0067c0,dp(4));
        item.setBackground(isActive?activeBg:normal);
        // F-13(a): the active item sits on solid blue, so its label is always white.
        if(isActive)item.setTextColor(Color.WHITE);
        item.setOnHoverListener((view,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)view.setBackground(hover);
            else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)view.setBackground(isActive?activeBg:normal);
            return false;
        });
        item.setOnTouchListener((view,e)->{
            if((e.getActionMasked()==MotionEvent.ACTION_BUTTON_PRESS||e.getActionMasked()==MotionEvent.ACTION_DOWN)&&(e.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){
                showJumpMenu(item,w);
                return true;
            }
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN)view.setBackground(pressed);
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)view.setBackground(view.isHovered()?hover:(isActive?activeBg:normal));
            return false;
        });
    }
        private void showJumpMenu(View anchor,Win w){
        dismissJumpMenu();
        activeInstance=this;
        ArrayList<String> labels=new ArrayList<>();
        labels.add("Восстановить");
        labels.add("Свернуть");
        labels.add("Развернуть");
        labels.add("На весь экран");
        labels.add("Привязать влево");
        labels.add("Привязать вправо");
        labels.add("Snap Layouts");
        if(w.snapGroupId!=null)labels.add("Свернуть группу");
        labels.add("Закрыть окно");
        LinearLayout panel=new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(6),dp(6),dp(6),dp(6));
        panel.setBackground(round(prefs.darkTheme()?0xff2b2b2b:0xfff9f9f9,8));
        PopupWindow popup=new PopupWindow(panel,dp(220),-2,true);
        popup.setOutsideTouchable(true);
        popup.setBackgroundDrawable(panel.getBackground());
        jumpMenu=popup;
        popup.setOnDismissListener(()->{
            if(jumpMenu==popup)jumpMenu=null;
        });
        for(String label:labels){
            TextView row=new TextView(context);
            row.setText(label);
            row.setTextSize(14);
            row.setTextColor(prefs.darkTheme()?Color.WHITE:0xff1a1a1a);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(10),0,dp(10),0);
            row.setMinHeight(dp(32));
            GradientDrawable normal=round(prefs.darkTheme()?0xff2b2b2b:0xfff9f9f9,8),hover=round(prefs.darkTheme()?0xff454545:0xffe5e5e5,8),pressed=round(prefs.darkTheme()?0xff555555:0xffe0e0e0,8);
            row.setBackground(normal);
            row.setOnHoverListener((v,e)->{
                if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)v.setBackground(hover);
                else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)v.setBackground(normal);
                return false;
            });
            row.setOnTouchListener((v,e)->{
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.setBackground(pressed);
                else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?hover:normal);
                return false;
            });
            row.setOnClickListener(v->{
                popup.dismiss();
                switch(label){
                    case "Восстановить":if(w.state==Win.State.MINIMIZED)controller.restore(w);else controller.restoreNormal(w);break; // H-06
                    case "Свернуть":controller.minimize(w);break;
                    case "Развернуть":controller.maximize(w);break;
                    case "На весь экран":controller.fullscreen(w);break;
                    case "Привязать влево":controller.snapHalf(w,true);break;
                    case "Привязать вправо":controller.snapHalf(w,false);break;
                    case "Snap Layouts":controller.snapController().showLayouts(w,anchor);break;
                    case "Свернуть группу":controller.snapController().minimizeGroup(w);break;
                    case "Закрыть окно":controller.close(w);break;
                }
            });
            panel.addView(row,new LinearLayout.LayoutParams(-1,dp(32)));
        }
        popup.setWindowLayoutType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT); // M-02
        try{
            popup.showAsDropDown(anchor,0,-(labels.size()*dp(32)+dp(20)));
        }catch(RuntimeException e){
            jumpMenu=null;
        }
    }
    private void create(){
        bar=new LinearLayout(context);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(8),dp(2),dp(8),dp(2));bar.setBackground(round(prefs.darkTheme()?0xff1c1c1c:0xfff3f3f3,0));
        bar.setOnHoverListener((v,e)->{if(temporary&&(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE))renewTemporary();return false;});
        bar.setOnTouchListener((v,e)->{if(temporary&&(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE))renewTemporary();return false;});
        params=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,dp(prefs.mouseProfile()?48:56),WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,PixelFormat.OPAQUE);
        params.gravity=Gravity.BOTTOM|Gravity.LEFT;params.setTitle("WinLauncher taskbar");
        try{wm.addView(bar,params);}catch(RuntimeException e){bar=null;params=null;}
    }
    private void updatePosition(boolean fullscreen){if(bar==null)return;params.height=dp(prefs.mouseProfile()?48:56);try{if(bar.getParent()!=null)wm.updateViewLayout(bar,params);}catch(RuntimeException ignored){}}
    // M-06: the menu must not outlive its anchor bar.
    private void remove(){dismissJumpMenu();if(bar!=null)try{if(bar.getParent()!=null)wm.removeView(bar);}catch(RuntimeException ignored){}bar=null;params=null;clock=null;}
    private TextView button(String text,int width){TextView v=new TextView(context);v.setText(text);v.setTextSize(13);v.setTextColor(prefs.darkTheme()?Color.WHITE:0xff1a1a1a);v.setGravity(Gravity.CENTER);GradientDrawable normal=round(prefs.darkTheme()?0xff2b2b2b:0xfff9f9f9,dp(4)),hover=round(prefs.darkTheme()?0xff454545:0xffe5e5e5,dp(4)),pressed=round(prefs.darkTheme()?0xff555555:0xffe0e0e0,dp(4));v.setBackground(normal);v.setOnHoverListener((view,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)view.setBackground(hover);else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)view.setBackground(normal);return false;});v.setOnTouchListener((view,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)view.setBackground(pressed);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)view.setBackground(view.isHovered()?hover:normal);return false;});v.setLayoutParams(new LinearLayout.LayoutParams(width,-1));return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private String abbrev(String title){if(title==null||title.length()==0)return "□";return title.length()>2?title.substring(0,2):title;}
    private int dp(float x){return(int)(x*density+.5f);}
}
