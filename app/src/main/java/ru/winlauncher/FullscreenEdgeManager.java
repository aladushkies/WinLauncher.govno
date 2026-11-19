package ru.winlauncher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Four small fullscreen edge targets. Per-app exclusions and optional two-swipe gesture are honored. */
public final class FullscreenEdgeManager implements WindowController.Listener {
    private static FullscreenEdgeManager activeManager;
    public static boolean isMiniShowing(){return activeManager!=null&&activeManager.mini!=null;}
    public static boolean dismissMiniForEscape(){if(!isMiniShowing())return false;activeManager.hideMini();return true;}
    private enum Side { TOP,BOTTOM,LEFT,RIGHT }
    private final Context context; private final WindowController controller; private final TaskbarOverlay taskbar;
    private final WindowManager wm; private final LauncherPrefs prefs; private final float density;
    private TaskViewOverlay fallbackTaskView; // F-02: shared overlays come from DockService / the singleton
    private final Map<Side,Edge> edges=new EnumMap<>(Side.class);
    private Win active; private View mini; private WindowManager.LayoutParams miniParams; private boolean enabled,listening;
    private int lastScreenW,lastScreenH; // BUG-09: edge geometry is rebuilt when the display size changes

        private static final long MINI_AUTO_HIDE_MS=3500L;
    private final android.os.Handler miniHandler=new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable miniAutoHide=new Runnable(){
        @Override public void run(){
            hideMini();
        }
    };
    public FullscreenEdgeManager(Context c,WindowController ctl,TaskbarOverlay bar){
        context=c.getApplicationContext();
        controller=ctl;
        taskbar=bar;
        wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        prefs=new LauncherPrefs(context);
        density=context.getResources().getDisplayMetrics().density;
        activeManager=this;
    }
    public void enable(){activeManager=this;enabled=true;if(!listening){listening=true;controller.addListener(this);}update(controller.snapshot());}
        public void disable(){
        enabled=false;
        if(listening){
            controller.removeListener(this);
            listening=false;
        }
        removeEdges();
        hideMini();
        // F-02: tear down the shared singletons, never private copies.
        TaskViewOverlay.dismissActive();
        ActionCenterOverlay.dismissActive();
        if(activeManager==this)activeManager=null;
    }
    @Override public void onWindowsChanged(List<Win> windows){if(enabled)update(windows);}

    private void update(List<Win> windows){
        Win candidate=null;for(Win w:windows)if(w.state==Win.State.FULLSCREEN&&w.visible&&(candidate==null||w.z>candidate.z))candidate=w;
        if(candidate==null||prefs.areEdgesDisabled(candidate.packageName)){active=null;removeEdges();hideMini();return;}
        DisplayMetrics dm=new DisplayMetrics();wm.getDefaultDisplay().getRealMetrics(dm);
        // BUG-09: the old early return also skipped rebuilding after a rotation, leaving the
        // four edge strips sized for the previous orientation.
        boolean sameWindow=active!=null&&active.taskId==candidate.taskId;
        boolean sameSize=dm.widthPixels==lastScreenW&&dm.heightPixels==lastScreenH;
        if(sameWindow&&edges.size()==4&&sameSize)return;
        // M-07(1): the mini bar belongs to the previous window / display size.
        if(!sameWindow||!sameSize)hideMini();
        active=candidate;removeEdges();lastScreenW=dm.widthPixels;lastScreenH=dm.heightPixels;
        for(Side side:Side.values()){Edge edge=new Edge(side,active);WindowManager.LayoutParams p=paramsFor(side,dm);try{wm.addView(edge.view,p);edge.params=p;edges.put(side,edge);}catch(RuntimeException ignored){}}
    }

    private WindowManager.LayoutParams paramsFor(Side side,DisplayMetrics dm){
        int thickness=dp(2);int width=(side==Side.LEFT||side==Side.RIGHT)?thickness:dm.widthPixels;int height=(side==Side.TOP||side==Side.BOTTOM)?thickness:dm.heightPixels;
        WindowManager.LayoutParams p=new WindowManager.LayoutParams(width,height,WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.OPAQUE);
        p.gravity=side==Side.TOP?Gravity.TOP|Gravity.LEFT:side==Side.BOTTOM?Gravity.BOTTOM|Gravity.LEFT:side==Side.LEFT?Gravity.TOP|Gravity.LEFT:Gravity.TOP|Gravity.RIGHT;
        p.setTitle("WinLauncher fullscreen edge "+side);return p;
    }

        private void perform(Side side){
        if(active==null)return;
        switch(side){
            case BOTTOM:taskbar.showTemporary();break;
            case TOP:showMini(active);break;
            case LEFT:showTaskView();break;
            case RIGHT:ActionCenterOverlay.showSingleton(context);break;
        }
    }

    private void showTaskView(){
        TaskViewOverlay view=DockService.taskView();
        if(view==null){
            if(fallbackTaskView==null)fallbackTaskView=new TaskViewOverlay(context,controller);
            view=fallbackTaskView;
        }
        view.show();
    }
    private void removeEdges(){for(Edge e:new ArrayList<>(edges.values())){e.cancelHover();try{wm.removeView(e.view);}catch(RuntimeException ignored){}}edges.clear();}
    private void showMini(Win w){
        hideMini();
        boolean dark=prefs.darkTheme();
        LinearLayout row=new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),0,dp(4),0);
        row.setBackground(round(dark?0xff292929:0xfff3f3f3,0));
        TextView title=new TextView(context);
        title.setText(w.title==null?w.packageName:w.title);
        title.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);
        title.setTextSize(12);
        title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER_VERTICAL); // U-08 / I-11
        LinearLayout.LayoutParams titleLp=new LinearLayout.LayoutParams(0,dp(32),1);titleLp.gravity=Gravity.CENTER_VERTICAL;row.addView(title,titleLp);
        TextView min=miniButton("—",dark);
        TextView restore=miniButton("⧉",dark);
        TextView close=miniButton("×",dark);
        min.setContentDescription("Свернуть окно");
        restore.setContentDescription("Вернуть окно к максимальному размеру");
        close.setContentDescription("Закрыть окно");
        row.addView(min,new LinearLayout.LayoutParams(dp(46),dp(32)));
        row.addView(restore,new LinearLayout.LayoutParams(dp(46),dp(32)));
        row.addView(close,new LinearLayout.LayoutParams(dp(46),dp(32)));
        // F-12: every button hides the bar too.
        min.setOnClickListener(v->{
            hideMini();
            controller.minimize(w);
        });
        restore.setOnClickListener(v->{
            hideMini();
            controller.maximize(w);
        });
        close.setOnClickListener(v->{
            hideMini();
            controller.close(w);
        });
        title.setOnTouchListener(new View.OnTouchListener(){
            float y;
            public boolean onTouch(View v,MotionEvent e){
                if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
                    y=e.getRawY();
                    scheduleMiniAutoHide();
                    return true;
                }
                if(e.getActionMasked()==MotionEvent.ACTION_UP){
                    if(e.getRawY()-y>dp(40)){
                        hideMini();
                        controller.maximize(w);
                    }else{
                        scheduleMiniAutoHide();
                    }
                    return true;
                }
                return true;
            }
        });
        // F-12: outside touches and any touch on the bar rearm the auto-hide timer.
        row.setOnTouchListener((v,e)->{
            if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){
                hideMini();
                return false;
            }
            scheduleMiniAutoHide();
            return false;
        });
        row.setOnHoverListener((v,e)->{
            scheduleMiniAutoHide();
            return false;
        });
        DisplayMetrics dm=new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        miniParams=new WindowManager.LayoutParams(
                Math.min(dm.widthPixels,dp(560)),
                dp(32),
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        |WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        |WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.OPAQUE);
        miniParams.gravity=Gravity.TOP|Gravity.CENTER_HORIZONTAL;
        // U-08: do not slide under a visible system status bar.
        if(!prefs.hideStatusBar())miniParams.y=controller.workArea().top;
        miniParams.setTitle("WinLauncher fullscreen mini title");
        mini=row;
        try{
            wm.addView(mini,miniParams);
        }catch(RuntimeException e){
            mini=null;
        }
        scheduleMiniAutoHide();
    }

    private void scheduleMiniAutoHide(){
        miniHandler.removeCallbacks(miniAutoHide);
        if(mini!=null)miniHandler.postDelayed(miniAutoHide,MINI_AUTO_HIDE_MS);
    }

    private void hideMini(){
        miniHandler.removeCallbacks(miniAutoHide);
        if(mini!=null){
            try{
                wm.removeView(mini);
            }catch(RuntimeException ignored){
            }
        }
        mini=null;
        miniParams=null;
    }
    // U-08: hovering or touching a mini button re-arms the auto-hide timer (see the listeners).
    private TextView miniButton(String text,boolean dark){boolean isClose="×".equals(text);TextView v=new TextView(context);v.setText(text);v.setTextSize(15);v.setTextColor(dark?0xfff5f5f5:0xff202020);v.setGravity(Gravity.CENTER);v.setBackground(round(dark?0xff292929:0xfff3f3f3,8));v.setOnHoverListener((x,e)->{scheduleMiniAutoHide();if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE){x.setBackground(round(isClose?0xffc42b1c:(dark?0xff424242:0xffdddddd),8));if(isClose)((TextView)x).setTextColor(Color.WHITE);}else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){x.setBackground(round(dark?0xff292929:0xfff3f3f3,8));((TextView)x).setTextColor(dark?0xfff5f5f5:0xff202020);}return false;});v.setOnTouchListener((x,e)->{scheduleMiniAutoHide();if(e.getActionMasked()==MotionEvent.ACTION_DOWN){x.setBackground(round(isClose?0xffa91f12:(dark?0xff5c5c5c:0xffc9c9c9),8));if(isClose)((TextView)x).setTextColor(Color.WHITE);}else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){x.setBackground(round(x.isHovered()?(isClose?0xffc42b1c:(dark?0xff424242:0xffdddddd)):dark?0xff292929:0xfff3f3f3,8));((TextView)x).setTextColor(isClose&&x.isHovered()?Color.WHITE:(dark?0xfff5f5f5:0xff202020));}return false;});return v;}
    private GradientDrawable round(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(radius);return d;}
    private int dp(float x){return(int)(x*density+.5f);}

    private final class Edge implements View.OnTouchListener,View.OnHoverListener {
        final Side side;final View view;WindowManager.LayoutParams params;final Win owner;float sx,sy;long lastSwipe;final android.os.Handler handler=new android.os.Handler(android.os.Looper.getMainLooper());Runnable hover;
        Edge(Side s,Win w){side=s;owner=w;view=new View(context);view.setBackgroundColor(0xff0067c0);view.setOnTouchListener(this);view.setOnHoverListener(this);}
        public boolean onTouch(View v,MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN){sx=e.getRawX();sy=e.getRawY();return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){float dx=e.getRawX()-sx,dy=e.getRawY()-sy;boolean valid=side==Side.TOP?dy>dp(12):side==Side.BOTTOM?dy<-dp(12):side==Side.LEFT?dx>dp(20):dx<-dp(20);if(valid)trigger();return true;}return true;}
        private void trigger(){if(active==null||!owner.packageName.equals(active.packageName)||prefs.areEdgesDisabled(owner.packageName))return;if(prefs.doubleSwipeEdges()){long now=android.os.SystemClock.uptimeMillis();if(now-lastSwipe>1100){lastSwipe=now;Toast.makeText(context,"Повторите свайп",Toast.LENGTH_SHORT).show();return;}lastSwipe=0;}perform(side);}
        void cancelHover(){if(hover!=null){handler.removeCallbacks(hover);hover=null;}}
        public boolean onHover(View v,MotionEvent e){
            if(side!=Side.TOP&&side!=Side.BOTTOM)return false;
            if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER&&!prefs.doubleSwipeEdges()){
                cancelHover();hover=()->{hover=null;if(!prefs.doubleSwipeEdges())trigger();};handler.postDelayed(hover,300);
            }else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)cancelHover();
            return false;
        }
    }
}
