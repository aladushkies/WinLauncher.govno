package ru.winlauncher;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetHost;
import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/** Opaque desktop shell with a low-cost geometric wallpaper and Android widgets hosted on the desktop. */
public final class DesktopActivity extends Activity {
    public static final int APP_WIDGET_HOST_ID=0x574c;
    private static final int REQUEST_WIDGET_BIND=7321,REQUEST_WIDGET_CONFIGURE=7322;
    private AlertDialog appsDialog; // U-06
    private LauncherPrefs prefs;
    private BloomView wallpaper;
    private android.app.AlertDialog backgroundMenu; // F-08(b)
    private AppWidgetHost widgetHost;
    private AppWidgetManager widgetManager;
    private LinearLayout widgetContainer;
    private TextView desktopClock;
    private String lastWidgetIdSignature;
    private int pendingWidgetId=-1;
    private final Runnable desktopClockTick=new Runnable(){@Override public void run(){
        if(desktopClock!=null)desktopClock.setText("⊞     WinLauncher                         "+new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(new java.util.Date()));
        handler.postDelayed(this,30000L);
    }};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final Runnable slideshow= new Runnable(){@Override public void run(){if(prefs!=null&&prefs.wallpaperSlideshow()){prefs.setWallpaperIndex((prefs.wallpaperIndex()+1)%3);if(wallpaper!=null)wallpaper.invalidate();handler.postDelayed(this,60000L);}}};

    @Override protected void onCreate(Bundle state){super.onCreate(state);prefs=new LauncherPrefs(this);widgetManager=AppWidgetManager.getInstance(this);widgetHost=new AppWidgetHost(this,APP_WIDGET_HOST_ID);if(state!=null)pendingWidgetId=state.getInt("pending_widget_id",-1);build();}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("pending_widget_id",pendingWidgetId);super.onSaveInstanceState(out);}
    @Override protected void onStart(){
        super.onStart();widgetHost.startListening();renderWidgets();
        // BUG-22: the slideshow used to be scheduled in onCreate and never unscheduled, so it
        // kept cycling wallpapers in the background. It now lives between onStart/onStop.
        handler.removeCallbacks(slideshow);if(prefs.wallpaperSlideshow())handler.postDelayed(slideshow,60000L);
        handler.removeCallbacks(desktopClockTick);handler.post(desktopClockTick);
    }
    @Override protected void onStop(){handler.removeCallbacks(slideshow);handler.removeCallbacks(desktopClockTick);widgetHost.stopListening();super.onStop();}

    private void build(){
        FrameLayout frame=new FrameLayout(this);wallpaper=new BloomView();frame.addView(wallpaper,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout screen=new LinearLayout(this);screen.setOrientation(LinearLayout.VERTICAL);screen.setPadding(dp(12),dp(12),dp(12),dp(8));
        TextView title=new TextView(this);title.setText("Рабочий стол");title.setTextColor(UiTokens.textOnAccent());title.setTextSize(UiTokens.TEXT_HEADING);screen.addView(title);
        LinearLayout icons=new LinearLayout(this);icons.setOrientation(LinearLayout.HORIZONTAL);icons.setGravity(Gravity.CENTER_VERTICAL); // U-06: one row, not a 276dp stack
        addDesktopIcon(icons,"🌐","Edge Lite",()->startActivity(new Intent(this,EdgeLiteActivity.class)));
        addDesktopIcon(icons,"▦","Добавить виджет",this::showWidgetPicker);
        addDesktopIcon(icons,"⚙","Параметры",()->startActivity(new Intent(this,LauncherSettingsActivity.class)));
        Button apps=new Button(this);apps.setText("Все приложения");apps.setOnClickListener(v->showApps());screen.addView(apps,new LinearLayout.LayoutParams(dp(190),dp(UiTokens.MIN_TOUCH_TARGET)));
        screen.addView(icons,new LinearLayout.LayoutParams(-1,dp(100)));

        ScrollView widgetScroll=new ScrollView(this);widgetScroll.setFillViewport(true);widgetContainer=new LinearLayout(this);widgetContainer.setOrientation(LinearLayout.VERTICAL);widgetContainer.setPadding(0,dp(4),0,dp(4));widgetScroll.addView(widgetContainer);
        screen.addView(widgetScroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView taskbar=new TextView(this);desktopClock=taskbar;taskbar.setText("⊞     WinLauncher                         "+new java.text.SimpleDateFormat("HH:mm",java.util.Locale.getDefault()).format(new java.util.Date()));taskbar.setTextColor(UiTokens.textPrimary(prefs.darkTheme()));taskbar.setGravity(Gravity.CENTER_VERTICAL);taskbar.setPadding(dp(16),0,dp(16),0);taskbar.setBackgroundColor(prefs.darkTheme()?0xff202020:0xffe8e8e8);screen.addView(taskbar,new LinearLayout.LayoutParams(-1,dp(48)));
        frame.addView(screen,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
        attachBackgroundHandlers(wallpaper);
        // BUG-07: the widget ScrollView covers most of the desktop and swallows touches, so
        // the background handlers must also live on the widget container and the screen
        // layout itself (clicks on widgets and icons are consumed by those children first).
        attachBackgroundHandlers(widgetContainer);
        attachBackgroundHandlers(screen);
    }
    private void attachBackgroundHandlers(View target){
        target.setOnLongClickListener(v->{showBackgroundMenu();return true;});
        // BUG-07: ACTION_BUTTON_PRESS is delivered to OnGenericMotionListener, never to
        // OnTouchListener; the old touch check could therefore never fire.
        target.setOnGenericMotionListener((v,e)->{if(e.getAction()==MotionEvent.ACTION_BUTTON_PRESS&&(e.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){showBackgroundMenu();return true;}return false;});
        if(android.os.Build.VERSION.SDK_INT>=23)target.setOnContextClickListener(v->{showBackgroundMenu();return true;});
        target.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN&&(e.getButtonState()&MotionEvent.BUTTON_SECONDARY)!=0){showBackgroundMenu();return true;}return false;});
    }
    private void addDesktopIcon(LinearLayout parent,String glyph,String label,Runnable action){
        LinearLayout tile=new LinearLayout(this);tile.setOrientation(LinearLayout.VERTICAL);tile.setGravity(Gravity.CENTER);tile.setPadding(dp(8),dp(8),dp(8),dp(8));tile.setBackgroundColor(UiTokens.desktopTile());
        TextView icon=new TextView(this);icon.setText(glyph);icon.setTextSize(UiTokens.TEXT_TILE);icon.setTextColor(UiTokens.textOnAccent());icon.setGravity(Gravity.CENTER);tile.addView(icon,new LinearLayout.LayoutParams(dp(64),dp(UiTokens.MIN_TOUCH_TARGET)));
        TextView text=new TextView(this);text.setText(label);text.setTextColor(UiTokens.textOnAccent());text.setTextSize(UiTokens.TEXT_CAPTION);text.setGravity(Gravity.CENTER);tile.addView(text);
        tile.setOnClickListener(v->action.run());tile.setOnLongClickListener(v->{showBackgroundMenu();return true;});LinearLayout.LayoutParams tileLp=new LinearLayout.LayoutParams(dp(96),dp(92));tileLp.setMargins(dp(6),dp(6),dp(6),dp(6));parent.addView(tile,tileLp);
    }
        private void showBackgroundMenu(){
        // F-08(b): touch, generic-motion and context-click can all fire for one gesture; only
        // one menu may be open at a time.
        if(backgroundMenu!=null&&backgroundMenu.isShowing())return;
        String slideshowLabel=prefs.wallpaperSlideshow()?"Выключить смену обоев":"Включить смену обоев";
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Рабочий стол")
                .setItems(new String[]{"Следующие обои","Добавить виджет…",slideshowLabel,"Параметры"},(d,which)->{
                    if(which==0){
                        prefs.setWallpaperIndex((prefs.wallpaperIndex()+1)%3);
                        wallpaper.invalidate();
                    }else if(which==1){
                        showWidgetPicker();
                    }else if(which==2){
                        prefs.setWallpaperSlideshow(!prefs.wallpaperSlideshow());
                        handler.removeCallbacks(slideshow);
                        if(prefs.wallpaperSlideshow())handler.postDelayed(slideshow,60000L);
                    }else{
                        startActivity(new Intent(this,LauncherSettingsActivity.class));
                    }
                })
                .show();
        backgroundMenu=dialog;
        dialog.setOnDismissListener(d->{
            if(backgroundMenu==dialog)backgroundMenu=null;
        });
    }
    private void showApps(){
        PackageManager pm=getPackageManager();Intent query=new Intent(Intent.ACTION_MAIN,null);query.addCategory(Intent.CATEGORY_LAUNCHER);List<ResolveInfo> apps=pm.queryIntentActivities(query,0);Collections.sort(apps,(a,b)->String.valueOf(a.loadLabel(pm)).compareToIgnoreCase(String.valueOf(b.loadLabel(pm))));
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);
        if(appsDialog!=null&&appsDialog.isShowing())appsDialog.dismiss();
        for(ResolveInfo app:apps){TextView item=new TextView(this);item.setText("  "+app.loadLabel(pm));item.setTextSize(UiTokens.TEXT_ITEM);item.setGravity(Gravity.CENTER_VERTICAL);item.setPadding(dp(8),0,dp(8),0);list.addView(item,new LinearLayout.LayoutParams(-1,dp(UiTokens.MIN_TOUCH_TARGET)));item.setOnClickListener(v->{if(appsDialog!=null){appsDialog.dismiss();appsDialog=null;}try{Intent launch=pm.getLaunchIntentForPackage(app.activityInfo.packageName);if(launch!=null)startActivity(launch);}catch(RuntimeException ignored){}});}
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Приложения").setView(scroll).setNegativeButton("Закрыть",null).show();
        appsDialog=dialog;
        dialog.setOnDismissListener(d->{if(appsDialog==dialog)appsDialog=null;});
    }

    private void showWidgetPicker(){
        List<AppWidgetProviderInfo> providers=widgetManager.getInstalledProviders();if(providers==null||providers.isEmpty()){Toast.makeText(this,"Установленные виджеты не найдены",Toast.LENGTH_SHORT).show();return;}
        ArrayList<AppWidgetProviderInfo> available=new ArrayList<>();ArrayList<String> labels=new ArrayList<>();
        for(AppWidgetProviderInfo info:providers){if(info.provider==null)continue;available.add(info);CharSequence label=info.loadLabel(getPackageManager());labels.add((label==null?info.provider.getClassName():label)+" · "+info.provider.getPackageName());}
        new AlertDialog.Builder(this).setTitle("Добавить виджет на рабочий стол").setItems(labels.toArray(new String[0]),(dialog,index)->bindWidget(available.get(index))).setNegativeButton("Отмена",null).show();
    }
    private void bindWidget(AppWidgetProviderInfo info){
        int id=widgetHost.allocateAppWidgetId();pendingWidgetId=id;
        // U-06: try the silent bind first; fall back to the system bind screen.
        try{
            if(widgetManager.bindAppWidgetIdIfAllowed(id,info.provider)){
                if(info.configure!=null){
                    try{widgetHost.startAppWidgetConfigureActivityForResult(this,id,0,REQUEST_WIDGET_CONFIGURE,null);}
                    catch(RuntimeException e){Toast.makeText(this,"Не удалось настроить виджет: "+e.getMessage(),Toast.LENGTH_LONG).show();widgetHost.deleteAppWidgetId(id);pendingWidgetId=-1;}
                }else{saveWidget(id);pendingWidgetId=-1;}
                return;
            }
        }catch(RuntimeException ignored){}
        Intent bind=new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,id);bind.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER,info.provider);
        try{startActivityForResult(bind,REQUEST_WIDGET_BIND);}
        catch(RuntimeException e){Toast.makeText(this,"Не удалось привязать виджет: "+e.getMessage(),Toast.LENGTH_LONG).show();widgetHost.deleteAppWidgetId(id);pendingWidgetId=-1;}
    }
    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);int id=data==null?-1:data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID,-1);if(id<0)id=pendingWidgetId;
        if(requestCode==REQUEST_WIDGET_BIND){
            if(resultCode==RESULT_OK&&id>=0){AppWidgetProviderInfo info=widgetManager.getAppWidgetInfo(id);if(info!=null&&info.configure!=null){
                // D-08: configure through AppWidgetHost instead of a raw ACTION_APPWIDGET_CONFIGURE
                // intent. A direct start crashes with an uncaught SecurityException when the
                // provider's configure screen is not exported, and leaks the allocated id.
                try{widgetHost.startAppWidgetConfigureActivityForResult(this,id,0,REQUEST_WIDGET_CONFIGURE,null);}
                catch(RuntimeException e){Toast.makeText(this,"Не удалось настроить виджет: "+e.getMessage(),Toast.LENGTH_LONG).show();widgetHost.deleteAppWidgetId(id);pendingWidgetId=-1;}
                return;
            }saveWidget(id);}
            else if(id>=0)widgetHost.deleteAppWidgetId(id);
        }else if(requestCode==REQUEST_WIDGET_CONFIGURE){if(resultCode==RESULT_OK&&id>=0)saveWidget(id);else if(id>=0)widgetHost.deleteAppWidgetId(id);}
        pendingWidgetId=-1;
    }
    private void saveWidget(int id){Set<String> ids=prefs.appWidgetIds();ids.add(String.valueOf(id));prefs.setAppWidgetIds(ids);renderWidgets();}
    private void renderWidgets(){
        if(widgetContainer==null)return;
        // S32: a duplicated id in prefs used to create the same AppWidgetHostView twice, which
        // makes RemoteViews views share a single host and stop updating.
        java.util.Set<String> seenIds=new java.util.LinkedHashSet<>();
        for(String value:prefs.appWidgetIds())if(!seenIds.add(value))continue;
        // BUG-22: rebuilding every AppWidgetHostView on each onStart is wasteful; skip when
        // the hosted id set has not changed and the views are already there.
        String signature=seenIds.toString();
        if(signature.equals(lastWidgetIdSignature)&&widgetContainer.getChildCount()>0)return;
        lastWidgetIdSignature=signature;
        widgetContainer.removeAllViews();
        boolean pruned=false;
        for(String value:seenIds)try{
            int id=Integer.parseInt(value);AppWidgetProviderInfo info=widgetManager.getAppWidgetInfo(id);
            // BUG-22: a provider that was uninstalled leaves a dead id in prefs forever;
            // prune it instead of silently skipping it on every render.
            if(info==null){widgetHost.deleteAppWidgetId(id);Set<String> ids=prefs.appWidgetIds();ids.remove(value);prefs.setAppWidgetIds(ids);pruned=true;continue;} // F-08(c)
            AppWidgetHostView view=widgetHost.createView(this,id,info);view.setAppWidget(id,info);
            view.setOnLongClickListener(v->{new AlertDialog.Builder(this).setTitle("Виджет").setItems(new String[]{"Удалить виджет"},(d,which)->removeWidget(id)).setNegativeButton("Отмена",null).show();return true;});
            // BUG-22: AppWidgetProviderInfo.minHeight is already in px; dp() scaled it twice.
            // U-01 / I-08: stack a 28dp delete strip ABOVE the widget in a vertical
            // LinearLayout so the strip neither covers widget content nor clips the × glyph.
            LinearLayout wrap=new LinearLayout(this);wrap.setOrientation(LinearLayout.VERTICAL);
            LinearLayout strip=new LinearLayout(this);strip.setOrientation(LinearLayout.HORIZONTAL);strip.setGravity(Gravity.RIGHT|Gravity.CENTER_VERTICAL);strip.setPadding(0,0,dp(4),0);strip.setBackgroundColor(UiTokens.widgetStrip(prefs.darkTheme()));
            TextView del=new TextView(this);del.setText("×");del.setTextColor(UiTokens.textOnAccent());del.setTextSize(UiTokens.TEXT_ITEM);del.setGravity(Gravity.CENTER);del.setPadding(dp(20),0,dp(20),0);
            del.setContentDescription("Удалить виджет");
            del.setOnClickListener(v->{new AlertDialog.Builder(this).setTitle("Виджет").setItems(new String[]{"Удалить виджет"},(d,which)->removeWidget(id)).setNegativeButton("Отмена",null).show();});
            strip.addView(del,new LinearLayout.LayoutParams(-2,-1));
            wrap.addView(strip,new LinearLayout.LayoutParams(-1,dp(28)));
            wrap.addView(view,new LinearLayout.LayoutParams(-1,Math.max(dp(120),info.minHeight)));
            LinearLayout.LayoutParams wrapLp=new LinearLayout.LayoutParams(-1,-2);wrapLp.bottomMargin=dp(8);
            widgetContainer.addView(wrap,wrapLp);
        }catch(NumberFormatException ignored){}
        if(pruned)lastWidgetIdSignature=null;
    }
    private void removeWidget(int id){widgetHost.deleteAppWidgetId(id);Set<String> ids=prefs.appWidgetIds();ids.remove(String.valueOf(id));prefs.setAppWidgetIds(ids);renderWidgets();}

    @Override protected void onDestroy(){
        handler.removeCallbacks(slideshow);
        if(backgroundMenu!=null&&backgroundMenu.isShowing())backgroundMenu.dismiss();
        if(appsDialog!=null&&appsDialog.isShowing())appsDialog.dismiss();
        // J-06 (checked during the round-J audit, no change required): AppWidgetHost has no public destroy() — only
        // stopListening() (already called from onStop) and deleteHost(), which would delete
        // the user's widgets. The host is released with the process.
        super.onDestroy();
    }
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
    private final class BloomView extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        BloomView(){super(DesktopActivity.this);setBackgroundColor(UiTokens.wallpaperBase());}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);int index=prefs==null?0:prefs.wallpaperIndex()%3;canvas.drawColor(UiTokens.wallpaperBackdrop(index));float cx=getWidth()*.67f,cy=getHeight()*.55f,r=Math.min(getWidth(),getHeight())*.30f;
            for(int i=0;i<4;i++){paint.setColor(UiTokens.wallpaperPetal(index,i));Path petal=new Path();float angle=(float)(i*Math.PI/2);float sx=cx+(float)Math.cos(angle)*r*.14f,sy=cy+(float)Math.sin(angle)*r*.14f;petal.moveTo(sx,sy);petal.cubicTo(cx+(float)Math.cos(angle-.85)*r*.15f,cy+(float)Math.sin(angle-.85)*r*.15f,cx+(float)Math.cos(angle-.6)*r*1.2f,cy+(float)Math.sin(angle-.6)*r*1.2f,cx+(float)Math.cos(angle)*r*1.25f,cy+(float)Math.sin(angle)*r*1.25f);petal.cubicTo(cx+(float)Math.cos(angle+.6)*r*1.2f,cy+(float)Math.sin(angle+.6)*r*1.2f,cx+(float)Math.cos(angle+.85)*r*.15f,cy+(float)Math.sin(angle+.85)*r*.15f,sx,sy);petal.close();canvas.drawPath(petal,paint);}
        }
    }
}
