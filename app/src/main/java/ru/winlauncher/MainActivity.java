package ru.winlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import ru.winlauncher.bridge.RootBridge;
import ru.winlauncher.bridge.RootServerLauncher;

public final class MainActivity extends Activity {
    private TextView status;
    private RootBridge bridge;
    private WindowController controller;
    private ChromeManager chrome;
    private boolean appliedDark;
    private boolean appliedDarkKnown;
    private TaskbarOverlay taskbar;
    private FullscreenEdgeManager fullscreenEdges;
    private TaskViewOverlay taskView;
    private WindowController.Listener windowsListener;
    private WindowController.ErrorListener errorListener;
    private String token;
    private CheckBox windowsBehaviorBox;
    private CheckBox hideStatusBox;
    private boolean syncingChecks; // BUG-23: onResume sync must not re-fire the listeners

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = RootServerLauncher.getOrCreateToken(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(12));
        TextView title = new TextView(this);
        title.setText("WinLauncher Root · Window Core");
        title.setTextSize(UiTokens.TEXT_TITLE); title.setTextColor(UiTokens.textPrimary(false));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("RootServer, управление freeform-окнами, Snap, resize grips, taskbar и fullscreen-зоны. Hidden API и stack IDs требуют проверки на Samsung; начинайте с тестовых окон.");
        note.setTextSize(UiTokens.TEXT_BUTTON); note.setPadding(0, dp(6), 0, dp(8));
        root.addView(note);
        LauncherPrefs prefs = new LauncherPrefs(this);
        boolean dark=prefs.darkTheme();
        appliedDark=dark;
        appliedDarkKnown=true;
        if(dark){
            root.setBackgroundColor(UiTokens.page(true));
            title.setTextColor(UiTokens.textPrimary(true));
            note.setTextColor(UiTokens.textSecondary(true));
        }
        CheckBox windowsBehavior=new CheckBox(this);
        windowsBehaviorBox=windowsBehavior;
        windowsBehavior.setText("Поведение как в Windows: кнопка MAX восстанавливает NORMAL");
        if(dark)windowsBehavior.setTextColor(UiTokens.textPrimary(true));
        windowsBehavior.setChecked(prefs.windowsButtonBehavior());
        windowsBehavior.setOnCheckedChangeListener((button,checked)->{
            if(syncingChecks)return;
            prefs.setWindowsButtonBehavior(checked);
            if(controller!=null)controller.setWindowsButtonBehavior(checked);
        });
        root.addView(windowsBehavior);
        CheckBox hideStatus = new CheckBox(this);
        hideStatusBox=hideStatus;
        hideStatus.setText("Скрывать системный статус-бар и шторку (root)");
        if(dark)hideStatus.setTextColor(UiTokens.textPrimary(true));
        hideStatus.setChecked(prefs.hideStatusBar());
        hideStatus.setOnCheckedChangeListener((button, checked) -> {
            if(syncingChecks)return;
            prefs.setHideStatusBar(checked);
            if (controller != null) controller.setHideStatusBar(checked);
        });
        root.addView(hideStatus);
        addButton(root, "Edge Lite · вкладки и DuckDuckGo", v -> startActivity(new Intent(this, EdgeLiteActivity.class)));
        addButton(root, "Рабочий стол и виджеты на нём", v -> startActivity(new Intent(this, DesktopActivity.class)));
        addButton(root, "Простой диспетчер задач", v -> startActivity(new Intent(this, TaskManagerActivity.class)));
        addButton(root, "Параметры профиля и fullscreen-зон", v -> startActivity(new Intent(this, LauncherSettingsActivity.class)));
        addButton(root, "Настроить accessibility-калибровку", v -> setupAccessibility());
        addButton(root, "1 · Запустить RootServer (su)", v -> startServer());
        addButton(root, "2 · Проверить root-соединение", v -> ensureBridge().ping(this::showResult));
        addButton(root, "3 · Сканировать задачи", v -> {
            ensureController(); controller.refreshTasks((ok, text) -> status.setText(text)); startOverlays();
        });
        addButton(root, "4 · Включить заголовки окон", v -> {
            DockService.setChromeRequested(true);
            enableChrome();
        });
        addButton(root, "5 · Скрыть заголовки", v -> {
            DockService.setChromeRequested(false);
            if (chrome != null) chrome.disable();
            status.setText("Заголовки скрыты.");
        });
        status = new TextView(this);
        if(dark)status.setTextColor(UiTokens.textPrimary(true));
        status.setTextIsSelectable(true); status.setTextSize(UiTokens.TEXT_CAPTION); status.setGravity(Gravity.TOP);
        status.setMinHeight(dp(96)); // U-02
        status.setText("Сначала запустите RootServer и подтвердите запрос SuperSU/Magisk. Для заголовков требуется SYSTEM_ALERT_WINDOW.");
        // U-02 / I-06: add status directly inside the single pageScroll (no nested ScrollView).
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        ScrollView pageScroll = new ScrollView(this); pageScroll.setFillViewport(true); pageScroll.addView(root);
        setContentView(pageScroll);
    }

    private void addButton(LinearLayout root, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(listener);
        root.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }

    private RootBridge ensureBridge() {
        if (bridge == null) bridge = DockService.bridge();
        if (bridge == null) bridge = new RootBridge(token);
        return bridge;
    }

    private void ensureController() {
        if (controller != null && windowsListener != null) return;
        if (controller == null) {
            controller = DockService.controller();
            if (controller != null) {
                bridge = DockService.bridge();
                chrome = DockService.chrome();
                taskbar = DockService.taskbar();
                taskView = DockService.taskView();
                fullscreenEdges = DockService.edges();
            } else {
                controller = new WindowController(getApplicationContext(), ensureBridge());
                chrome = new ChromeManager(getApplicationContext(), controller);
                taskbar = new TaskbarOverlay(getApplicationContext(), controller);
                taskView = new TaskViewOverlay(getApplicationContext(), controller);
                final TaskViewOverlay serviceTaskView = taskView;
                taskbar.setActions(new TaskbarOverlay.Actions() {
                    @Override public void openTaskView() { serviceTaskView.show(); }
                    @Override public void openStart() { Intent desktop=new Intent(getApplicationContext(),DesktopActivity.class);desktop.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(desktop); }
                });
                fullscreenEdges = new FullscreenEdgeManager(getApplicationContext(), controller, taskbar);
                DockService.keepAlive(this, bridge, controller, chrome, taskbar, taskView, fullscreenEdges);
            }
        }
        errorListener = message -> { if (status != null) status.setText("Ошибка RootServer: " + message); };
        windowsListener = wins -> {
            if (status != null && (chrome == null || !chrome.isEnabled()))
                status.setText("Обнаружено задач: " + wins.size());
        };
        controller.setErrorListener(errorListener);
        controller.addListener(windowsListener);
        if (chrome != null) chrome.setStatusListener(message -> { if (status != null) status.setText(message); });
    }

    private void startOverlays() {
        controller.startMonitoring();
        // BUG-23: enabling overlays without the SYSTEM_ALERT_WINDOW permission silently
        // no-ops inside WindowManager; say so instead of pretending they are on.
        if(android.os.Build.VERSION.SDK_INT>=23&&!Settings.canDrawOverlays(this)){
            status.setText("Нет разрешения SYSTEM_ALERT_WINDOW: панель задач и краевые зоны не показаны. Нажмите «Включить заголовки» и выдайте разрешение.");
            return;
        }
        if(taskbar!=null)taskbar.enable();
        if(fullscreenEdges!=null)fullscreenEdges.enable();
    }

    private void enableChrome() {
        ensureController();
        controller.updateDisplayGeometry();
        startOverlays();
        if (!chrome.hasOverlayPermission()) {
            status.setText("Нет SYSTEM_ALERT_WINDOW. Открываю системный экран разрешения; после выдачи нажмите «Включить заголовки» ещё раз.");
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                status.setText("Выдайте root-командой: appops set " + getPackageName() + " SYSTEM_ALERT_WINDOW allow");
            }
            return;
        }
        chrome.enable();
        status.setText("Заголовки включены. Для проверки откройте тестовые задачи в свободном режиме.");
    }

    private void setupAccessibility() {
        status.setText("Включаю KeyService через root settings…");
        ensureBridge().enableAccessibility(result->{
            if(result.optBoolean("ok",false))status.setText("KeyService включён. Откройте свободное окно Samsung для калибровки caption.");
            else {status.setText("Root-настройка не удалась: "+result.optString("error")+". Открываю системный экран.");startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));}
        });
    }

    private void startServer() {
        status.setText("Запускаю su/app_process…");
        new Thread(() -> {
            String result;
            try {
                java.lang.Process p = RootServerLauncher.start(this, token, android.os.Process.myUid());
                int code = p.waitFor();
                result = "Команда su завершилась с кодом " + code + ". Затем проверьте соединение.";
            } catch (Throwable t) { result = "Запуск не удался: " + t; }
            final String text = result;
            runOnUiThread(() -> status.setText(text));
        }, "root-server-launch").start();
    }

    private void showResult(JSONObject result) { status.setText(result.toString()); }
    private int dp(float x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }

        @Override protected void onResume() {
        super.onResume();
        LauncherPrefs syncPrefs=new LauncherPrefs(this);
        boolean darkNow=syncPrefs.darkTheme();
        if(!appliedDarkKnown){
            appliedDark=darkNow;
            appliedDarkKnown=true;
        }else if(darkNow!=appliedDark){
            appliedDark=darkNow;
            recreate();
            return;
        }
        // M-01: after recreate()/rotation the local controller is null or stale; re-attach
        // it before the sync below (and step 5) use it.
        WindowController serviceController=DockService.controller();
        if(serviceController!=null&&controller!=serviceController)ensureController();
        syncingChecks=true;
        if(windowsBehaviorBox!=null)windowsBehaviorBox.setChecked(syncPrefs.windowsButtonBehavior());
        if(hideStatusBox!=null)hideStatusBox.setChecked(syncPrefs.hideStatusBar());
        syncingChecks=false;
        if (controller != null) {
            LauncherPrefs prefs=new LauncherPrefs(this);
            if(controller.hideStatusBar()!=prefs.hideStatusBar())controller.setHideStatusBar(prefs.hideStatusBar());
            if(controller.windowsButtonBehavior()!=prefs.windowsButtonBehavior())controller.setWindowsButtonBehavior(prefs.windowsButtonBehavior());
            controller.updateDisplayGeometry();
            controller.refreshTasks(null);
        }
    }

    @Override protected void onDestroy() {
        if (controller != null && windowsListener != null) controller.removeListener(windowsListener);
        if (controller != null) controller.setErrorListener(null);
        if (chrome != null) chrome.setStatusListener(null);
        // Once retained, DockService owns overlay/window lifecycles; an Activity recreation must
        // not tear down the desktop surfaces or close the persistent RootBridge.
        if (!DockService.retains(controller)) {
            if (chrome != null) chrome.disable();
            if (fullscreenEdges != null) fullscreenEdges.disable();
            if (taskbar != null) taskbar.disable();
            if (controller != null) { controller.stopMonitoring(); controller.releaseActive(); }
            if (bridge != null) bridge.close();
        }
        super.onDestroy();
    }
}
