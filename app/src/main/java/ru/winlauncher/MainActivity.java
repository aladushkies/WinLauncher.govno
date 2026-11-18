package ru.winlauncher;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
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
    private String token;

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
        title.setText("WinLauncher Root · P1 + окна");
        title.setTextSize(21); title.setTextColor(0xff1a1a1a);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("Ядро RootServer/RootBridge и первый контроллер свободных окон. Экспериментальный код: сначала проверьте на тестовом окне.");
        note.setTextSize(13); note.setPadding(0, dp(6), 0, dp(8));
        root.addView(note);
        addButton(root, "1 · Запустить RootServer (su)", v -> startServer());
        addButton(root, "2 · Проверить root-соединение", v -> ensureBridge().ping(this::showResult));
        addButton(root, "3 · Сканировать задачи", v -> {
            ensureController(); controller.refreshTasks((ok, text) -> status.setText(text));
            controller.startMonitoring();
        });
        addButton(root, "4 · Включить заголовки окон", v -> enableChrome());
        addButton(root, "5 · Скрыть заголовки", v -> { if (chrome != null) chrome.disable(); status.setText("Заголовки скрыты."); });
        status = new TextView(this);
        status.setTextIsSelectable(true); status.setTextSize(12); status.setGravity(Gravity.TOP);
        status.setText("Сначала запустите RootServer и подтвердите запрос SuperSU/Magisk. Для заголовков требуется SYSTEM_ALERT_WINDOW.");
        ScrollView scroll = new ScrollView(this); scroll.addView(status);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private void addButton(LinearLayout root, String label, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(label); b.setOnClickListener(listener);
        root.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }

    private RootBridge ensureBridge() {
        if (bridge == null) bridge = new RootBridge(token);
        return bridge;
    }

    private void ensureController() {
        if (controller != null) return;
        controller = new WindowController(this, ensureBridge());
        controller.setErrorListener(message -> status.setText("Ошибка RootServer: " + message));
        controller.addListener(wins -> {
            if (chrome == null || !chrome.isEnabled()) status.setText("Обнаружено задач: " + wins.size());
        });
        chrome = new ChromeManager(this, controller);
        chrome.setStatusListener(message -> status.setText(message));
    }

    private void enableChrome() {
        ensureController();
        controller.updateDisplayGeometry();
        controller.startMonitoring();
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
        if (controller != null) {
            controller.updateDisplayGeometry();
            controller.refreshTasks(null);
        }
    }

    @Override protected void onDestroy() {
        if (chrome != null) chrome.disable();
        if (controller != null) controller.stopMonitoring();
        if (bridge != null) bridge.close();
        super.onDestroy();
    }
}
