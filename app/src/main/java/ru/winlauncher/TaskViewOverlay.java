package ru.winlauncher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** Window picker and entry/exit surface for fullscreen docked-stack pairs. */
public final class TaskViewOverlay {
    private static TaskViewOverlay active;
    private final Context context;
    private final WindowController controller;
    private final WindowManager wm;
    private final float density;
    private View root;
    private WindowManager.LayoutParams params;

    public TaskViewOverlay(Context c,WindowController ctl){
        context=c.getApplicationContext();controller=ctl;
        wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        density=context.getResources().getDisplayMetrics().density;
    }
    public static void dismissActive(){if(active!=null)active.dismiss();}
    public static boolean isShowing(){return active!=null&&active.root!=null;}
    public static boolean dismissForEscape(){if(!isShowing())return false;active.dismiss();return true;}

    public void show(){
        // F-02: dismiss the previously shown overlay through the static owner first,
        // otherwise overwriting active=this orphans that window permanently.
        dismissActive();
        active=this;
        boolean dark=new LauncherPrefs(context).darkTheme();
        LinearLayout shell=new LinearLayout(context);shell.setGravity(Gravity.CENTER);shell.setBackgroundColor(dark?0xff202020:0xfff4f4f4);shell.setClickable(true);
        LinearLayout card=new LinearLayout(context);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(16),dp(12),dp(16),dp(12));card.setClickable(true); // U-03: taps must not fall through to the shell
        GradientDrawable bg=new GradientDrawable();bg.setColor(dark?0xff292929:0xfff4f4f4);bg.setCornerRadius(8);bg.setStroke(dp(1),dark?0xff777777:0xff888888);card.setBackground(bg);
        TextView heading=new TextView(context);heading.setText("Task View · окна и split-screen");heading.setTextSize(20);heading.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);heading.setPadding(dp(4),dp(4),dp(4),dp(10));card.addView(heading);
        LinearLayout desktops=new LinearLayout(context);desktops.setOrientation(LinearLayout.HORIZONTAL);
        TextView desktopLabel=new TextView(context);desktopLabel.setText("Рабочий стол "+(controller.currentDesktopId()+1)+" из "+controller.virtualDesktopCount());desktopLabel.setTextColor(dark?0xfff5f5f5:0xff202020);desktopLabel.setGravity(Gravity.CENTER_VERTICAL);
        desktops.addView(desktopLabel,new LinearLayout.LayoutParams(0,dp(42),1));
        Button previousDesktop=new Button(context);previousDesktop.setText("‹");styleButton(previousDesktop,dark);previousDesktop.setEnabled(controller.currentDesktopId()>0);previousDesktop.setOnClickListener(v->{controller.switchVirtualDesktop(controller.currentDesktopId()-1);dismiss();});desktops.addView(previousDesktop,new LinearLayout.LayoutParams(dp(44),dp(42)));
        Button nextDesktop=new Button(context);nextDesktop.setText("›");styleButton(nextDesktop,dark);nextDesktop.setEnabled(controller.currentDesktopId()<controller.virtualDesktopCount()-1);nextDesktop.setOnClickListener(v->{controller.switchVirtualDesktop(controller.currentDesktopId()+1);dismiss();});desktops.addView(nextDesktop,new LinearLayout.LayoutParams(dp(44),dp(42)));
        Button addDesktop=new Button(context);addDesktop.setText("+");styleButton(addDesktop,dark);addDesktop.setEnabled(controller.virtualDesktopCount()<4);addDesktop.setOnClickListener(v->{controller.createVirtualDesktop();dismiss();});desktops.addView(addDesktop,new LinearLayout.LayoutParams(dp(44),dp(42)));
        card.addView(desktops);

        List<Win> windows=controller.snapshot();
        ArrayList<Win> selected=new ArrayList<>();
        LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroller=new ScrollView(context);scroller.addView(list);android.util.DisplayMetrics scrollMetrics=new android.util.DisplayMetrics();wm.getDefaultDisplay().getMetrics(scrollMetrics);card.addView(scroller,new LinearLayout.LayoutParams(-1,Math.min(dp(300),scrollMetrics.heightPixels/2))); // U-03
        Button enterPair=new Button(context);enterPair.setText("Открыть выбранные два окна в split 50:50");styleButton(enterPair,dark);enterPair.setEnabled(false); // U-03
        enterPair.setOnClickListener(v->{
            if(selected.size()==2){
                ensureDesktop(selected.get(0));
                // G-01: the pair must live on one desktop, otherwise the next reconcile
                // tick dismantles it when the second window reports a foreign desktopId.
                if(selected.get(1).desktopId!=selected.get(0).desktopId)controller.moveWindowToDesktop(selected.get(1),selected.get(0).desktopId);
                controller.enterDockedPair(selected.get(0),selected.get(1));
                dismiss();
            }
        });
        for(Win w:windows){
            if(w.state==Win.State.FULLSCREEN){
                // BUG-18: one CheckBox used to do two jobs — clicking it both focused the
                // window (without dismissing Task View) and marked it for split-screen, so a
                // plain Alt+Tab switch to a fullscreen window was impossible. The row is now
                // a focus button plus a separate pair-selection checkbox.
                LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
                Button focusButton=new Button(context);
                focusButton.setText((w.title==null?"Окно":w.title)+(w.stackId==WindowController.DOCKED_STACK_ID?" · docked":" · fullscreen")+" · стол "+(w.desktopId+1));
                styleButton(focusButton,dark);
                focusButton.setOnClickListener(v->{
                    ensureDesktop(w);
                    controller.focus(w);
                    dismiss();
                });
                focusButton.setOnLongClickListener(v->{chooseDesktop(w);return true;});
                row.addView(focusButton,new LinearLayout.LayoutParams(0,dp(44),1));
                CheckBox check=new CheckBox(context);
                check.setContentDescription("Выбрать для split-screen: "+(w.title==null?"Окно":w.title));
                check.setButtonTintList(ColorStateList.valueOf(0xff0067c0));
                check.setChecked(false);
                check.setOnCheckedChangeListener((button,checked)->{
                    if(checked){if(selected.size()>=2){button.setChecked(false);return;}selected.add(w);}
                    else selected.remove(w);
                    enterPair.setEnabled(selected.size()==2); // U-03
                });
                row.addView(check,new LinearLayout.LayoutParams(dp(48),dp(44)));
                LinearLayout.LayoutParams rowLp=new LinearLayout.LayoutParams(-1,dp(44));rowLp.bottomMargin=dp(6);list.addView(row,rowLp); // U-03
            }else if(w.state==Win.State.MINIMIZED||w.stackId==controller.freeformStackId()){
                Button item=new Button(context);item.setText((w.title==null?"Окно":w.title)+"  ·  "+w.state+" · стол "+(w.desktopId+1));styleButton(item,dark);
                item.setOnClickListener(v->{
                    // M-03: activateWindow switches desktop and restores exactly once.
                    controller.activateWindow(w);
                    dismiss();
                });
                item.setOnLongClickListener(v->{chooseDesktop(w);return true;});
                LinearLayout.LayoutParams itemLp=new LinearLayout.LayoutParams(-1,dp(44));itemLp.bottomMargin=dp(6);list.addView(item,itemLp); // U-03
            }
        }
        LinearLayout.LayoutParams enterLp=new LinearLayout.LayoutParams(-1,dp(44));enterLp.topMargin=dp(8);card.addView(enterPair,enterLp); // U-03
        if(controller.hasDockedPair()){
            Button exitPair=new Button(context);exitPair.setText("Выйти из docked split");styleButton(exitPair,dark);exitPair.setOnClickListener(v->{controller.exitDockedPair();dismiss();});LinearLayout.LayoutParams exitLp=new LinearLayout.LayoutParams(-1,dp(44));exitLp.topMargin=dp(8);card.addView(exitPair,exitLp);
        }
        Button close=new Button(context);close.setText("Закрыть Task View");styleButton(close,dark);close.setOnClickListener(v->dismiss());LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(-1,dp(44));closeLp.topMargin=dp(8);card.addView(close,closeLp);
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();wm.getDefaultDisplay().getMetrics(metrics);
        int cardWidth=Math.max(dp(240),Math.min(dp(680),metrics.widthPixels-dp(32)));
        shell.addView(card,new LinearLayout.LayoutParams(cardWidth,-2)); // F-15: shell is a LinearLayout
        shell.setFocusableInTouchMode(true);
        shell.setOnKeyListener((v,keyCode,event)->{if(keyCode==KeyEvent.KEYCODE_ESCAPE&&event.getAction()==KeyEvent.ACTION_UP){dismiss();return true;}return false;});
        shell.setOnTouchListener((v,e)->{if(e.getAction()==android.view.MotionEvent.ACTION_UP){dismiss();return true;}return true;});
        params=new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT,WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.OPAQUE);
        params.gravity=Gravity.TOP|Gravity.LEFT;params.setTitle("WinLauncher Task View");root=shell;
        try{wm.addView(root,params);View attached=root;attached.post(attached::requestFocus);}catch(RuntimeException ignored){root=null;}
    }

    /** F-04: focusing a window that lives on another virtual desktop must switch there first. */
    private void ensureDesktop(Win w){
        if(w.desktopId!=controller.currentDesktopId())controller.switchVirtualDesktop(w.desktopId);
    }

    private void chooseDesktop(Win window){
        int count=controller.virtualDesktopCount();if(count<2)return;
        controller.moveWindowToDesktop(window,(window.desktopId+1)%count);
        android.widget.Toast.makeText(context,"Окно перемещено на рабочий стол "+(window.desktopId+1),android.widget.Toast.LENGTH_SHORT).show();
        // BUG-18: the open list kept showing the window under its old desktop.
        show();
    }
    public void dismiss(){if(root!=null)try{wm.removeView(root);}catch(RuntimeException ignored){}root=null;if(active==this)active=null;}
    private void styleButton(Button button,boolean dark){
        button.setAllCaps(false);button.setTextSize(14);button.setTextColor(dark?0xfff5f5f5:0xff202020);
        GradientDrawable normal=round(dark?0xff383838:0xffe8e8e8,8);
        GradientDrawable hover=round(dark?0xff454545:0xffdedede,8);
        GradientDrawable pressed=round(dark?0xff525252:0xffd2d2d2,8);
        button.setBackground(normal);
        button.setOnHoverListener((v,e)->{
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==android.view.MotionEvent.ACTION_HOVER_MOVE)v.setBackground(hover);
            else if(e.getActionMasked()==android.view.MotionEvent.ACTION_HOVER_EXIT)v.setBackground(normal);
            return false;
        });
        button.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==android.view.MotionEvent.ACTION_DOWN)v.setBackground(pressed);
            else if(e.getActionMasked()==android.view.MotionEvent.ACTION_UP||e.getActionMasked()==android.view.MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?hover:normal);
            return false;
        });
    }
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private int dp(float v){return(int)(v*density+.5f);}
}
