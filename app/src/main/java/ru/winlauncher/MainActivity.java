package ru.winlauncher;

import android.app.Activity;
import android.os.Bundle;
import android.os.Process;
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
    private String token;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        token = RootServerLauncher.getOrCreateToken(this);
        buildUi();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(16));
        TextView title = new TextView(this);
        title.setText("WinLauncher Root · ядро P1");
        title.setTextSize(22); title.setTextColor(0xff1a1a1a);
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this);
        note.setText("Диагностическая сборка: RootServer + постоянный RootBridge. Команды управления окном доступны после успешного подключения root.");
        note.setTextSize(14); note.setPadding(0, dp(8), 0, dp(12));
        root.addView(note);
        addButton(root, "1 · Запустить RootServer (su)", v -> startServer());
        addButton(root, "2 · Проверить соединение", v -> ensureBridge().ping(this::showResult));
        addButton(root, "3 · Получить список задач", v -> ensureBridge().tasks(this::showResult));
        status = new TextView(this);
        status.setTextIsSelectable(true); status.setTextSize(12); status.setGravity(Gravity.TOP);
        status.setText("Ожидание. Сначала нажмите «Запустить RootServer» и разрешите запрос SuperSU/Magisk.");
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

    private void startServer() {
        status.setText("Запускаю su/app_process…");
        new Thread(() -> {
            String result;
            try {
                Process p = RootServerLauncher.start(this, token, android.os.Process.myUid());
                int code = p.waitFor();
                result = "Команда su завершилась с кодом " + code + ". Теперь проверьте соединение.";
            } catch (Throwable t) { result = "Запуск не удался: " + t; }
            final String text = result;
            runOnUiThread(() -> status.setText(text));
        }, "root-server-launch").start();
    }

    private void showResult(JSONObject result) {
        status.setText(result.toString());
    }
    private int dp(float x) { return (int)(x * getResources().getDisplayMetrics().density + 0.5f); }

    @Override protected void onDestroy() {
        if (bridge != null) bridge.close();
        super.onDestroy();
    }
}
