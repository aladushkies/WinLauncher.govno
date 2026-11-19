package ru.winlauncher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/** Opaque Action Center surface with optional Android notifications and root-backed radio controls. */
public final class ActionCenterOverlay {
    private static ActionCenterOverlay active;
    private static ActionCenterOverlay singleton;
    public static boolean isShowing(){return active!=null&&active.root!=null;}
    /** S39: Win+A used to allocate a new overlay per keypress; reuse one instance per context. */
    public static void showSingleton(Context context){
        if(singleton==null||singleton.context!=context.getApplicationContext())singleton=new ActionCenterOverlay(context);
        singleton.show();
    }
    /** S12: a controller teardown must not leave an orphan panel behind. */
    public static void dismissActive(){if(active!=null)active.dismiss();}
    public static boolean dismissActiveForEscape(){if(!isShowing())return false;active.dismiss();return true;}

    private final Context context;
    private final WindowManager wm;
    private final float density;
    private View root;
    private TextView state;

    public ActionCenterOverlay(Context c){
        context=c.getApplicationContext();
        wm=(WindowManager)context.getSystemService(Context.WINDOW_SERVICE);
        density=context.getResources().getDisplayMetrics().density;
    }

    /** K-03: the panel starts below the status bar (or below a display cutout). */
    private int topInset(){WindowController controller=WindowController.active();return controller==null?0:controller.topInset();}

    public void show(){
        // F-02: dismiss the previously shown overlay through the static owner first,
        // otherwise overwriting active=this orphans that window permanently.
        dismissActive();
        active=this;
        boolean dark=new LauncherPrefs(context).darkTheme();
        LinearLayout panel=new LinearLayout(context);panel.setOrientation(LinearLayout.VERTICAL);
        // K-03: the panel is MATCH_PARENT tall, so the status bar is cleared with padding
        // instead of an offset — an offset would push the close button off the screen.
        panel.setPadding(dp(14),dp(12)+topInset(),dp(14),dp(12));
        panel.setBackground(round(dark?0xff292929:0xfff5f5f5,0)); // D-13: the window is PixelFormat.OPAQUE; rounded outer corners would render as black squares
        TextView title=new TextView(context);title.setText("Центр уведомлений");title.setTextSize(UiTokens.TEXT_SUBHEADING);title.setTextColor(UiTokens.textPrimary(dark));panel.addView(title);
        state=new TextView(context);state.setText("Быстрые настройки");state.setTextSize(UiTokens.TEXT_BUTTON);state.setTextColor(UiTokens.textSecondary(dark));state.setPadding(0,dp(8),0,dp(6));panel.addView(state);

        LinearLayout tiles=new LinearLayout(context);tiles.setOrientation(LinearLayout.VERTICAL);
        addRadioRow(tiles,dark,new String[]{"Wi‑Fi","Bluetooth"},new String[]{"wifi","bluetooth"});
        addRadioRow(tiles,dark,new String[]{"Мобильные данные","Авиарежим"},new String[]{"mobile_data","airplane"});
        panel.addView(tiles);
        TextView settings=actionButton("Открыть системные настройки соединений",dark,()->openSettings(Settings.ACTION_WIRELESS_SETTINGS));
        panel.addView(settings,new LinearLayout.LayoutParams(-1,dp(UiTokens.MIN_TOUCH_TARGET)));

        TextView notificationTitle=new TextView(context);notificationTitle.setText("Уведомления Android");notificationTitle.setTextSize(UiTokens.TEXT_ITEM);notificationTitle.setTextColor(UiTokens.textPrimary(dark));notificationTitle.setPadding(0,dp(12),0,dp(5));panel.addView(notificationTitle);
        ScrollView notifications=new ScrollView(context);LinearLayout list=new LinearLayout(context);list.setOrientation(LinearLayout.VERTICAL);notifications.addView(list);
        if(!LauncherNotificationListener.isConnected()){
            TextView enable=actionButton("Разрешить доступ к уведомлениям…",dark,()->openSettings(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            list.addView(enable,new LinearLayout.LayoutParams(-1,dp(UiTokens.MIN_TOUCH_TARGET)));
            TextView unavailable=new TextView(context);unavailable.setText("Android требует отдельного согласия пользователя. Без него содержимое уведомлений недоступно.");unavailable.setTextSize(UiTokens.TEXT_CAPTION);unavailable.setTextColor(dark?UiTokens.textSecondary(dark):UiTokens.textMuted(dark));unavailable.setPadding(dp(6),dp(6),dp(6),dp(6));list.addView(unavailable);
        }else{
            List<LauncherNotificationListener.Entry> entries=LauncherNotificationListener.snapshot();
            if(entries.isEmpty()){TextView empty=new TextView(context);empty.setText("Нет активных уведомлений");empty.setTextColor(dark?UiTokens.textSecondary(dark):UiTokens.textMuted(dark));empty.setPadding(dp(6),dp(8),dp(6),dp(8));list.addView(empty);}
            for(LauncherNotificationListener.Entry entry:entries){
                // S11: the source carried a doubled escape ("\\n"), so the tile showed a literal
                // backslash-n between app, title and text instead of three lines.
                String label=appLabel(entry.packageName)+"\n"+entry.title+(entry.text.length()==0?"":"\n"+entry.text);
                TextView item=actionButton(label,dark,()->openNotification(entry.contentIntent));item.setGravity(Gravity.CENTER_VERTICAL|Gravity.LEFT);item.setPadding(dp(8),dp(6),dp(8),dp(6));item.setMaxLines(3);item.setEllipsize(TextUtils.TruncateAt.END); // U-05
                LinearLayout.LayoutParams itemLp=new LinearLayout.LayoutParams(-1,dp(68));itemLp.bottomMargin=dp(6);list.addView(item,itemLp); // U-05
            }
        }
        panel.addView(notifications,new LinearLayout.LayoutParams(-1,0,1));
        TextView close=actionButton("Закрыть",dark,this::dismiss);LinearLayout.LayoutParams closeLp=new LinearLayout.LayoutParams(-1,dp(UiTokens.MIN_TOUCH_TARGET));closeLp.topMargin=dp(8);panel.addView(close,closeLp);

        WindowManager.LayoutParams p=new WindowManager.LayoutParams(dp(360),WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,PixelFormat.OPAQUE); // U-05
        p.gravity=Gravity.RIGHT|Gravity.TOP;p.setTitle("WinLauncher Action Center");root=panel;
        panel.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_OUTSIDE){dismiss();return true;}return false;}); // U-05
        try{wm.addView(root,p);refreshRadioLabels(dark);}catch(RuntimeException ignored){root=null;}
    }

    private void addRadioRow(LinearLayout parent,boolean dark,String[] labels,String[] actions){
        LinearLayout row=new LinearLayout(context);row.setOrientation(LinearLayout.HORIZONTAL);
        for(int i=0;i<labels.length;i++){
            final String label=labels[i],action=actions[i];final TextView tile=actionButton(label,dark,()->{});tile.setTag(action);
            tile.setOnClickListener(v->toggleRadio(action,label,tile));
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(52),1);lp.setMargins(dp(2),dp(2),dp(2),dp(2));row.addView(tile,lp);
        }
        parent.addView(row);
    }

    private void refreshRadioLabels(boolean dark){
        if(!(root instanceof LinearLayout))return;
        // Radio buttons query actual system settings through the privileged bridge when available.
        LinearLayout panel=(LinearLayout)root;for(int i=0;i<panel.getChildCount();i++){
            View child=panel.getChildAt(i);if(!(child instanceof LinearLayout))continue;LinearLayout rows=(LinearLayout)child;
            for(int r=0;r<rows.getChildCount();r++)if(rows.getChildAt(r) instanceof LinearLayout){LinearLayout row=(LinearLayout)rows.getChildAt(r);for(int c=0;c<row.getChildCount();c++)if(row.getChildAt(c) instanceof TextView){TextView tile=(TextView)row.getChildAt(c);String action=(String)tile.getTag();if(action!=null)queryState(action,tile,tile.getText().toString());}}
        }
    }

    private void toggleRadio(String action,String label,TextView tile){
        ru.winlauncher.bridge.RootBridge bridge=DockService.bridge();
        if(bridge==null){openSettings(Settings.ACTION_WIRELESS_SETTINGS);return;}
        bridge.deviceState(action,result->{
            if(!result.optBoolean("ok",false)){showState("Не удалось прочитать состояние "+label+".");return;}
            if(!result.optBoolean("known",false)){showState("Состояние "+label+" недоступно; используйте системные настройки.");openSettings(Settings.ACTION_WIRELESS_SETTINGS);return;}
            boolean enabled=result.optBoolean("enabled",false);
            final boolean requested=!enabled;
            bridge.deviceAction(action,requested,done->{
                // BUG-11 (incomplete D-11): the server returns the state it actually read
                // back; the tile must show that, not the state we merely requested.
                if(done.optBoolean("ok",false)){
                    boolean known=done.optBoolean("known",true);
                    boolean actual=done.optBoolean("enabled",requested);
                    if(!known){showState("Состояние "+label+" не подтверждено; проверьте системные настройки.");return;}
                    if(tile!=null)tile.setText(label+(actual?" · Вкл.":" · Выкл."));
                    showState(label+": "+(actual?"включено":"выключено"));
                }
                else showState("Команда "+label+" не поддерживается этой прошивкой: "+done.optString("error","ошибка"));
            });
        });
    }
    private void queryState(String action,TextView tile,String label){
        ru.winlauncher.bridge.RootBridge bridge=DockService.bridge();if(bridge==null)return;
        bridge.deviceState(action,result->{if(result.optBoolean("ok",false)&&result.optBoolean("known",false))tile.setText(label+(result.optBoolean("enabled",false)?" · Вкл.":" · Выкл."));});
    }
    private String appLabel(String packageName){try{return String.valueOf(context.getPackageManager().getApplicationLabel(context.getPackageManager().getApplicationInfo(packageName,0)));}catch(Exception ignored){return packageName;}}
    private void openNotification(PendingIntent intent){if(intent==null)return;try{intent.send();dismiss();}catch(PendingIntent.CanceledException ignored){}}
    private TextView actionButton(String label,boolean dark,Runnable action){
        TextView button=new TextView(context);button.setText(label);button.setTextSize(UiTokens.TEXT_BUTTON);button.setGravity(Gravity.CENTER);button.setTextColor(UiTokens.textOnSurface(dark));
        button.setBackground(round(UiTokens.surfaceButton(dark),UiTokens.RADIUS_MEDIUM));GradientDrawable normal=round(UiTokens.surfaceButton(dark),UiTokens.RADIUS_MEDIUM),hover=round(UiTokens.surfaceHoverSoft(dark),UiTokens.RADIUS_MEDIUM),pressed=round(UiTokens.surfacePressedSoft(dark),UiTokens.RADIUS_MEDIUM);
        button.setOnHoverListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_HOVER_ENTER||e.getActionMasked()==MotionEvent.ACTION_HOVER_MOVE)v.setBackground(hover);else if(e.getActionMasked()==MotionEvent.ACTION_HOVER_EXIT)v.setBackground(normal);return false;});
        button.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)v.setBackground(pressed);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)v.setBackground(v.isHovered()?hover:normal);return false;});button.setOnClickListener(v->action.run());return button;
    }
    // U-05: the panel must not stay above Settings.
    private void openSettings(String action){dismiss();try{Intent intent=new Intent(action);intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);context.startActivity(intent);}catch(RuntimeException ignored){Intent fallback=new Intent(Settings.ACTION_SETTINGS);fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);try{context.startActivity(fallback);}catch(RuntimeException ignoredAgain){}}}
    private void showState(String value){if(state!=null)state.setText(value);Toast.makeText(context,value,Toast.LENGTH_SHORT).show();}
    public void dismiss(){if(root!=null)try{wm.removeView(root);}catch(RuntimeException ignored){}root=null;if(active==this)active=null;}
    private GradientDrawable round(int c,int r){GradientDrawable d=new GradientDrawable();d.setColor(c);d.setCornerRadius(r);return d;}
    private int dp(float x){return(int)(x*density+.5f);}
}
