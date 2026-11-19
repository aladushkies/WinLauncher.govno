package ru.winlauncher;

import android.app.Activity;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Settings for title-button behavior, display profile, and app-specific fullscreen edges. */
public final class LauncherSettingsActivity extends Activity {
    private LauncherPrefs prefs;
    private boolean initialDark; // F-09(b)
    private ListView appListView;
    private final ArrayList<AppEntry> appEntries = new ArrayList<>();
    private AppRowAdapter appAdapter;

    private static final class AppEntry {
        String label;
        String packageName;
        Drawable icon;
    }
    private static final class ViewHolder {
        android.widget.ImageView icon;
        TextView label;
        TextView pkg;
        Switch toggle;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = new LauncherPrefs(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(16), dp(12), dp(16), dp(12));
        TextView title = new TextView(this);
        title.setText("Параметры WinLauncher"); title.setTextSize(22); title.setTextColor(0xff1a1a1a);
        page.addView(title);

        CheckBox windowsButton=new CheckBox(this);
        windowsButton.setText("Поведение как в Windows: MAX → NORMAL; F11/жест → FULLSCREEN");
        windowsButton.setChecked(prefs.windowsButtonBehavior());
        windowsButton.setOnCheckedChangeListener((b,checked)->prefs.setWindowsButtonBehavior(checked));
        page.addView(windowsButton);

        CheckBox escapeFullscreen=new CheckBox(this);
        escapeFullscreen.setText("Esc выходит из FULLSCREEN (по умолчанию выключено)");
        escapeFullscreen.setChecked(prefs.escapeExitsFullscreen());
        escapeFullscreen.setOnCheckedChangeListener((b,checked)->prefs.setEscapeExitsFullscreen(checked));
        page.addView(escapeFullscreen);

        CheckBox hide = new CheckBox(this);
        hide.setText("Скрывать системный статус-бар и блокировать раскрытие шторки");
        hide.setChecked(prefs.hideStatusBar());
        hide.setOnCheckedChangeListener((b, checked) -> prefs.setHideStatusBar(checked));
        page.addView(hide);

        CheckBox dark=new CheckBox(this);
        dark.setText("Тёмная тема заголовков, flyouts, Task View и краевых панелей");
        // F-09(b): remember the theme the rows were built with and rebuild on change,
        // otherwise the adapter keeps white text on the white list background.
        initialDark=prefs.darkTheme();
        dark.setChecked(initialDark);
        dark.setOnCheckedChangeListener((b,checked)->{
            prefs.setDarkTheme(checked);
            initialDark=checked;
            recreate();
        });
        page.addView(dark);

        CheckBox twoSwipe = new CheckBox(this);
        twoSwipe.setText("Fullscreen-зоны: требовать двойной свайп");
        twoSwipe.setChecked(prefs.doubleSwipeEdges());
        twoSwipe.setOnCheckedChangeListener((b, checked) -> prefs.setDoubleSwipeEdges(checked));
        page.addView(twoSwipe);

        CheckBox shake = new CheckBox(this);
        shake.setText("Встряхнуть окно — свернуть остальные окна (Aero Shake)");
        shake.setChecked(prefs.shakeToMinimize());
        shake.setOnCheckedChangeListener((b, checked) -> prefs.setShakeToMinimize(checked));
        page.addView(shake);

        TextView gapLabel = new TextView(this);
        gapLabel.setText("Зазор Snap: " + prefs.snapGapPx() + " px (0–8 px)");
        page.addView(gapLabel);
        SeekBar gap = new SeekBar(this);
        gap.setMax(8);
        gap.setProgress(prefs.snapGapPx());
        gap.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.setSnapGapPx(progress);
                gapLabel.setText("Зазор Snap: " + progress + " px (0–8 px)");
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        page.addView(gap);

        TextView profileLabel = new TextView(this);
        profileLabel.setText("Профиль ввода (Авто: мышь → Mouse, иначе Touch)");
        profileLabel.setPadding(0, dp(12), 0, 0); page.addView(profileLabel);
        RadioGroup profiles = new RadioGroup(this);
        RadioButton auto = radio("Автоматически", "AUTO");
        RadioButton mouse = radio("Мышь", "MOUSE");
        RadioButton touch = radio("Сенсорный экран", "TOUCH");
        profiles.addView(auto); profiles.addView(mouse); profiles.addView(touch);
        String selected = prefs.inputProfile();
        profiles.check("MOUSE".equals(selected) ? mouse.getId() : "TOUCH".equals(selected) ? touch.getId() : auto.getId());
        profiles.setOnCheckedChangeListener((group, id) -> {
            if (id == auto.getId()) prefs.setAutoInputProfile();
            else prefs.setMouseProfile(id == mouse.getId());
        });
        page.addView(profiles);

        TextView edgeTitle = new TextView(this);
        edgeTitle.setText("Отключить краевые зоны для приложений");
        edgeTitle.setTextSize(18); edgeTitle.setPadding(0, dp(12), 0, dp(6)); page.addView(edgeTitle);
        // BUG-19: the app list is a recycling ListView now (was a ScrollView + LinearLayout
        // with one row per package, all built synchronously in onCreate).
        appListView = new ListView(this);
        // U-02 / I-07: attach the header view BEFORE setAdapter for strict API compatibility.
        appListView.addHeaderView(page, null, false);
        appAdapter = new AppRowAdapter();
        appListView.setAdapter(appAdapter);
        if (prefs.darkTheme()) {
            page.setBackgroundColor(0xff202020);
            applyDarkText(page);
            appListView.setBackgroundColor(0xff202020);
        }
        setContentView(appListView);
        populateApps();
    }

    private void applyDarkText(View view) {
        if (view instanceof TextView) ((TextView) view).setTextColor(0xfff5f5f5);
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyDarkText(group.getChildAt(i));
        }
    }

    private RadioButton radio(String label, String tag) {
        RadioButton b = new RadioButton(this); b.setText(label); b.setTag(tag); b.setId(View.generateViewId()); return b;
    }

    // BUG-19: enumerating 150–300 packages, resolving every label and icon and inflating a
    // row per package used to run synchronously on the UI thread in onCreate (visible freeze,
    // ANR risk on the 2 GB tablet). Enumeration, sorting and icon loading now run on a
    // background thread; the adapter binds preloaded entries into recycled rows.
    private void populateApps() {
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            ArrayList<AppEntry> loaded = new ArrayList<>();
            for (ApplicationInfo app : apps) {
                if (getPackageName().equals(app.packageName)) continue;
                AppEntry entry = new AppEntry();
                entry.label = String.valueOf(pm.getApplicationLabel(app));
                entry.packageName = app.packageName;
                try { entry.icon = pm.getApplicationIcon(app); } catch (Exception ignored) { }
                loaded.add(entry);
            }
            // U-08: sort by the cached labels — one getApplicationLabel call per app.
            Collections.sort(loaded,(a,b)->a.label.compareToIgnoreCase(b.label));
            runOnUiThread(() -> {
                // F-09(c): the background load can outlive the activity.
                if (isFinishing() || isDestroyed()) return;
                appEntries.clear();
                appEntries.addAll(loaded);
                appAdapter.notifyDataSetChanged();
            });
        }, "winlauncher-app-list").start();
    }

    private final class AppRowAdapter extends android.widget.BaseAdapter {
        @Override public int getCount() { return appEntries.size(); }
        @Override public Object getItem(int position) { return appEntries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View convertView, android.view.ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(LauncherSettingsActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(16), dp(4), dp(16), dp(4));
                android.widget.ImageView icon = new android.widget.ImageView(LauncherSettingsActivity.this);
                row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(36)));
                LinearLayout names = new LinearLayout(LauncherSettingsActivity.this);
                names.setOrientation(LinearLayout.VERTICAL);
                TextView label = new TextView(LauncherSettingsActivity.this); label.setSingleLine(true);
                TextView pkg = new TextView(LauncherSettingsActivity.this); pkg.setTextSize(10); pkg.setTextColor(0xff666666);
                names.addView(label); names.addView(pkg);
                LinearLayout.LayoutParams namesLp=new LinearLayout.LayoutParams(0,-2,1);namesLp.leftMargin=dp(8);row.addView(names,namesLp); // U-08
                Switch toggle = new Switch(LauncherSettingsActivity.this);
                row.addView(toggle);
                holder = new ViewHolder();
                holder.icon = icon; holder.label = label; holder.pkg = pkg; holder.toggle = toggle;
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }
            AppEntry entry = appEntries.get(position);
            holder.icon.setImageDrawable(entry.icon);
            holder.label.setText(entry.label);
            holder.label.setTextColor(prefs.darkTheme() ? 0xfff5f5f5 : 0xff1a1a1a);
            holder.pkg.setText(entry.packageName);
            holder.pkg.setTextColor(prefs.darkTheme() ? 0xffaaaaaa : 0xff666666); // U-08
            // Detach before setChecked so recycling a row never writes the previous
            // package's state into the new row's preference.
            holder.toggle.setOnCheckedChangeListener(null);
            holder.toggle.setChecked(prefs.areEdgesDisabled(entry.packageName));
            holder.toggle.setOnCheckedChangeListener((button, checked) -> prefs.setEdgeDisabled(entry.packageName, checked));
            return convertView;
        }
    }

    @Override protected void onResume(){
        super.onResume();
        if(prefs!=null&&prefs.darkTheme()!=initialDark){
            initialDark=prefs.darkTheme();
            recreate();
        }
    }

    @Override protected void onStop(){
        super.onStop();
        WindowController controller=WindowController.active();
        if(controller!=null){
            // S44: status-bar and theme switches took effect only after an app restart.
            if(controller.hideStatusBar()!=prefs.hideStatusBar())controller.setHideStatusBar(prefs.hideStatusBar());
            controller.snapController().settingsChanged();
            controller.refreshTasks(null);
            controller.refreshUi();
        }
    }

    private int dp(float v) { return (int)(v * getResources().getDisplayMetrics().density + 0.5f); }
}
