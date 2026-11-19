package ru.winlauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import java.util.List;

/** Owns a two-task docked-stack pair (Android stack 3) and its live resize divider. */
public final class DockedPairManager {
    private final Context context;
    private final WindowController controller;
    private final WindowManager wm;
    private final float density;
    private Win first;
    private Win second;
    private float ratio=.5f;
    private boolean verticalSplit;
    private View divider;
    private WindowManager.LayoutParams dividerParams;
    private boolean busy;
    private Runnable pendingEnter;
    private float pointerStart;
    private float ratioStart;

    DockedPairManager(Context c,WindowController ctl){
        context=c.getApplicationContext();controller=ctl;
        wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        density=context.getResources().getDisplayMetrics().density;
    }

    public boolean isActive(){return first!=null&&second!=null;}
    public boolean contains(Win w){return isActive()&&(first==w||second==w);}
    public Win first(){return first;}
    public Win second(){return second;}
    public float ratio(){return ratio;}

    public void adoptExisting(Win a,Win b){
        if(isActive()||a==null||b==null||a==b||a.stackId!=WindowController.DOCKED_STACK_ID||b.stackId!=WindowController.DOCKED_STACK_ID)return;
        first=a;second=b;Rect screen=controller.displayBounds();verticalSplit=screen.height()>screen.width();
        ratio=verticalSplit?(float)a.bounds.height()/Math.max(1,screen.height()):(float)a.bounds.width()/Math.max(1,screen.width());
        ratio=clampRatio(ratio,screen);createDivider();
    }

    public void enter(Win a,Win b){
        if(a==null||b==null||a==b)return;
        // S41: exit()/begin() are asynchronous; a second Enter while a pair is being torn down
        // used to run two moveToDockedStack chains and leave the divider pointing at no pair.
        if(busy){pendingEnter=()->enter(a,b);return;}
        if(isActive())exit(null,()->enter(a,b));
        else begin(a,b);
    }
    private void abortPending(Win... rollback){
        first=null;second=null;removeDivider();busy=false;
        // M-07(2): a queued enter() must not replay after the pair it waited for was aborted.
        pendingEnter=null;
        for(Win w:rollback)if(w!=null){w.stackId=WindowController.DOCKED_STACK_ID;controller.moveFromDockedStack(w,null);}
    }
    /** M-07(2): when the FIRST transfer of a pair fails, the window was never moved into
     *  the docked stack; restore its original stack instead of forcing DOCKED_STACK_ID. */
    private void abortFirstTransfer(Win w,int originalStackId){
        first=null;second=null;removeDivider();busy=false;pendingEnter=null;
        // I-02: roll back to the real original stack (e.g. freeform stack 2) instead of
        // calling moveFromDockedStack() which always forces fullscreenStackId.
        if(w!=null)controller.rollbackDockedTransfer(w,originalStackId);
    }
    private void begin(Win a,Win b){
        if(a.snapGroupId!=null)controller.snapController().detach(a);
        if(b.snapGroupId!=null)controller.snapController().detach(b);
        first=a;second=b;ratio=.5f;busy=true;
        // M-03: bump both operations so async results from before the pair entry are ignored.
        controller.nextOperation(a);
        controller.nextOperation(b);
        // M-07(2): remember where the first window lived before the move.
        final int firstOriginalStackId=a.stackId;
        Rect[] bounds=pairBounds();
        controller.moveToDockedStack(first,bounds[0],(ok,message)->{
            if(!ok){abortFirstTransfer(first,firstOriginalStackId);return;}
            controller.moveToDockedStack(second,bounds[1],(secondOk,secondMessage)->{
                if(!secondOk){abortPending(first,second);return;}
                createDivider();
                // BUG-05: busy stayed true after a successful begin(), so every later
                // enter() was silently queued into pendingEnter and never ran.
                busy=false;Runnable pending=pendingEnter;pendingEnter=null;if(pending!=null)pending.run();
            });
        });
    }

    public void exit(Win preferred){exit(preferred,null);}
    public void exit(Win preferred,Runnable after){
        if(!isActive()){if(after!=null)after.run();return;}
        Win a=first,b=second;first=null;second=null;removeDivider();busy=true;
        Runnable finish=()->{busy=false;if(preferred!=null)controller.focus(preferred);if(after!=null)after.run();
            Runnable pending=pendingEnter;pendingEnter=null;if(pending!=null)pending.run();};
        controller.moveFromDockedStack(a,()->controller.moveFromDockedStack(b,finish));
    }

    public void resizeTo(float value){
        if(!isActive())return;
        Rect display=controller.displayBounds();
        ratio=clampRatio(value,display);
        reflow();
    }

    public void commit(){
        if(!isActive())return;
        // D-02: commitMove() applies the freeform clamp(); docked bounds must be committed as-is.
        controller.commitDockedBounds(first);controller.commitDockedBounds(second);
    }

    public void release(){first=null;second=null;removeDivider();busy=false;pendingEnter=null;}
    public void onTaskRemoved(Win removed,boolean keepOther){
        if(!contains(removed))return;Win other=first==removed?second:first;first=null;second=null;removeDivider();busy=false;pendingEnter=null;
        if(keepOther&&other!=null)controller.moveFromDockedStack(other,null);
    }
    public void onDisplayChanged(){if(isActive()){verticalSplit=controller.displayBounds().height()>controller.displayBounds().width();reflow();}}

    private float clampRatio(float value,Rect display){
        int axis=verticalSplit?display.height():display.width();
        int min=verticalSplit?dp(160):dp(240);
        float lo=Math.min(.5f,Math.max(.05f,(float)min/Math.max(1,axis)));
        return Math.max(lo,Math.min(1f-lo,value));
    }

    private Rect[] pairBounds(){
        Rect screen=controller.displayBounds();verticalSplit=screen.height()>screen.width();
        ratio=clampRatio(ratio,screen);
        if(verticalSplit){
            int split=screen.top+Math.round(screen.height()*ratio);
            return new Rect[]{new Rect(screen.left,screen.top,screen.right,split),new Rect(screen.left,split,screen.right,screen.bottom)};
        }
        int split=screen.left+Math.round(screen.width()*ratio);
        return new Rect[]{new Rect(screen.left,screen.top,split,screen.bottom),new Rect(split,screen.top,screen.right,screen.bottom)};
    }

    /** H-05 / I-01: hide the pair divider when a foreign fullscreen window covers the pair
     *  or when neither pair member is visible in DOCKED_STACK_ID (the pair members themselves
     *  have state == FULLSCREEN, so the old loop always destroyed its own divider). */
    public void syncVisibility(List<Win> windows){
        if(!isActive()){if(divider!=null)removeDivider();return;}
        boolean foreignFs=false,pairVisible=false;
        for(Win w:windows){
            if(contains(w)){if(w.visible&&w.stackId==WindowController.DOCKED_STACK_ID)pairVisible=true;}
            else if(w.visible&&w.state==Win.State.FULLSCREEN){foreignFs=true;}
        }
        if(foreignFs||!pairVisible){if(divider!=null)removeDivider();}
        else if(divider==null)createDivider();
    }

    private void reflow(){
        if(!isActive())return;
        Rect[] r=pairBounds();controller.requestDockedBounds(first,r[0]);controller.requestDockedBounds(second,r[1]);updateDivider(r[0],r[1]);
    }

    private void createDivider(){
        if(!isActive())return;
        Divider hit=new Divider(context);hit.setBackgroundColor(new LauncherPrefs(context).darkTheme()?0xff444444:0xffb7b7b7); // U-08
        int thickness=dp(12);Rect display=controller.displayBounds();
        dividerParams=new WindowManager.LayoutParams(verticalSplit?display.width():thickness,
                verticalSplit?thickness:display.height(),WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE);
        dividerParams.gravity=Gravity.TOP|Gravity.LEFT;dividerParams.setTitle("WinLauncher docked pair divider");
        divider=hit;updateDivider(pairBounds()[0],pairBounds()[1]);
        try{wm.addView(divider,dividerParams);}catch(RuntimeException e){divider=null;dividerParams=null;}
    }

    private void updateDivider(Rect a,Rect b){
        if(dividerParams==null)return;
        int thickness=dp(12);Rect display=controller.displayBounds();
        dividerParams.width=verticalSplit?display.width():thickness;dividerParams.height=verticalSplit?thickness:display.height();
        if(verticalSplit){dividerParams.x=a.left;dividerParams.y=a.bottom-thickness/2;}
        else{dividerParams.x=a.right-thickness/2;dividerParams.y=a.top;}
        try{if(divider!=null&&divider.getParent()!=null)wm.updateViewLayout(divider,dividerParams);}catch(RuntimeException ignored){}
    }

    private void removeDivider(){
        if(divider!=null)try{if(divider.getParent()!=null)wm.removeView(divider);}catch(RuntimeException ignored){}
        divider=null;dividerParams=null;
    }

    private int dp(float value){return(int)(value*density+.5f);}

    private final class Divider extends View implements View.OnTouchListener {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private boolean active;private final boolean dark;
        Divider(Context c){super(c);dark=new LauncherPrefs(c).darkTheme();setOnTouchListener(this);setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)active=true;else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)active=false;invalidate();return false;});setBackgroundColor(dark?0xff444444:0xffb7b7b7);}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setColor(active?(dark?0xff0097ff:0xff0067c0):(dark?0xff888888:0xff666666));float half=active?dp(1):1;if(verticalSplit)canvas.drawRect(0,getHeight()/2f-half,getWidth(),getHeight()/2f+half,paint);else canvas.drawRect(getWidth()/2f-half,0,getWidth()/2f+half,getHeight(),paint);}
        @Override public boolean onTouch(View v,MotionEvent event){
            if(event.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||event.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE){active=true;invalidate();}
            else if(event.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT){active=false;invalidate();}
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){active=true;invalidate();pointerStart=verticalSplit?event.getRawY():event.getRawX();ratioStart=ratio;return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                float now=verticalSplit?event.getRawY():event.getRawX();Rect screen=controller.displayBounds();
                float delta=(now-pointerStart)/(verticalSplit?Math.max(1,screen.height()):Math.max(1,screen.width()));
                float next=ratioStart+delta;
                if(next<.04f||next>.96f){exit(next<.04f?second:first);return true;}
                resizeTo(next);return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP){active=false;invalidate();commit();return true;}
            if(event.getActionMasked()==MotionEvent.ACTION_CANCEL){active=false;invalidate();commit();return true;}
            return true;
        }
    }
}
