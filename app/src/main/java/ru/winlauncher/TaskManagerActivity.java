package ru.winlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** Small task manager surface: live task list, restore minimized tasks, and close a selected task. */
public final class TaskManagerActivity extends Activity implements WindowController.Listener {
    private WindowController controller;
    private LinearLayout rows;
    private TextView status;
    private boolean listening;
    private LauncherPrefs prefs; // U-07
    private boolean appliedDark;
    private boolean appliedDarkKnown;

    @Override protected void onCreate(Bundle state){super.onCreate(state);
        prefs=new LauncherPrefs(this);appliedDark=prefs.darkTheme();appliedDarkKnown=true;
        controller=DockService.controller();if(controller==null)controller=WindowController.active();buildUi();if(controller!=null){controller.addListener(this);listening=true;controller.refreshTasks((ok,message)->{if(status!=null)status.setText(ok?message:"Не удалось обновить список: "+message);});}}
    private void buildUi(){
        boolean dark=new LauncherPrefs(this).darkTheme();
        LinearLayout page=new LinearLayout(this);page.setOrientation(LinearLayout.VERTICAL);page.setPadding(dp(14),dp(10),dp(14),dp(10));page.setBackgroundColor(UiTokens.page(dark));
        TextView title=new TextView(this);title.setText("Диспетчер задач");title.setTextSize(UiTokens.TEXT_HEADING);title.setTextColor(UiTokens.textPrimary(dark));page.addView(title);
        status=new TextView(this);status.setText(controller==null?"Запустите RootServer и управление окнами из WinLauncher.":"Загрузка задач…");status.setTextSize(UiTokens.TEXT_BUTTON);status.setTextColor(dark?UiTokens.textSecondary(dark):UiTokens.textPrimary(dark));status.setPadding(0,dp(6),0,dp(8));page.addView(status);
        Button refresh=new Button(this);refresh.setText("Обновить");refresh.setOnClickListener(v->{if(controller!=null)controller.refreshTasks((ok,message)->status.setText(ok?message:"Ошибка: "+message));});page.addView(refresh);
        ScrollView scroll=new ScrollView(this);rows=new LinearLayout(this);rows.setOrientation(LinearLayout.VERTICAL);scroll.addView(rows);page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(page);
    }
    @Override public void onWindowsChanged(List<Win> windows){render(windows);}
        private void render(List<Win> windows){
        if(rows==null)return;
        rows.removeAllViews();
        boolean dark=new LauncherPrefs(this).darkTheme();
        if(windows==null||windows.isEmpty()){
            TextView empty=new TextView(this);
            empty.setText("Нет управляемых задач.");
            empty.setTextColor(UiTokens.textPrimary(dark));
            empty.setPadding(dp(8),dp(12),dp(8),dp(12));
            rows.addView(empty);
            return;
        }
        for(Win win:windows){
            LinearLayout row=new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(6),dp(4),dp(6),dp(4));
            row.setBackgroundColor(UiTokens.surfaceRow(dark));
            LinearLayout labels=new LinearLayout(this);
            labels.setOrientation(LinearLayout.VERTICAL);
            TextView name=new TextView(this);
            name.setText(win.title==null?win.packageName:win.title);
            name.setTextSize(UiTokens.TEXT_ROW);
            name.setTextColor(UiTokens.textPrimary(dark));
            name.setSingleLine(true);
            labels.addView(name);
            TextView detail=new TextView(this);
            detail.setText(win.packageName+" · "+win.state+" · desk "+(win.desktopId+1));
            detail.setTextSize(UiTokens.TEXT_MICRO);
            detail.setTextColor(UiTokens.textTaskMuted(dark));
            labels.addView(detail);
            row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
            if(win.state==Win.State.MINIMIZED){
                Button restore=new Button(this);
                restore.setText("Вернуть");
                restore.setOnClickListener(v->{
                    if(controller==null)return;
                    // M-03 (was F-04): activateWindow switches to the window's own desktop
                    // and restores exactly once.
                    controller.activateWindow(win);
                });
                row.addView(restore,new LinearLayout.LayoutParams(dp(86),dp(UiTokens.MIN_TOUCH_TARGET)));
            }
            Button close=new Button(this);
            close.setText("Закрыть");
            close.setAllCaps(false); // U-07: ALL CAPS was clipped at the fixed width
            close.setContentDescription("Закрыть задачу: "+(win.title==null?win.packageName:win.title));
            close.setOnClickListener(v->{
                if(controller!=null){
                    controller.close(win);
                    status.setText("Отправлен запрос закрытия: "+win.packageName);
                }
            });
            row.addView(close,new LinearLayout.LayoutParams(-2,dp(UiTokens.MIN_TOUCH_TARGET)));
            rows.addView(row,new LinearLayout.LayoutParams(-1,-2));
            View divider=new View(this);
            divider.setBackgroundColor(UiTokens.dividerLine());
            rows.addView(divider,new LinearLayout.LayoutParams(-1,dp(1)));
        }
    }
    @Override protected void onResume(){super.onResume();if(appliedDarkKnown&&prefs!=null&&prefs.darkTheme()!=appliedDark){appliedDark=prefs.darkTheme();recreate();}if(controller!=null)controller.refreshTasks(null);} // U-07
    @Override protected void onDestroy(){if(listening&&controller!=null)controller.removeListener(this);super.onDestroy();}
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
}
