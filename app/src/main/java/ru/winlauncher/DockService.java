package ru.winlauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.IBinder;

import ru.winlauncher.bridge.RootBridge;
import ru.winlauncher.bridge.RootServerLauncher;

/** Keeps the window controller and system-alert overlays alive while MainActivity is in background. */
public final class DockService extends Service {
    private static final String CHANNEL="winlauncher_dock";
    private static final int NOTIFICATION_ID=7101;
    private static volatile DockService instance;
    private static volatile RootBridge keptBridge;
    private static volatile WindowController keptController;
    private static volatile ChromeManager keptChrome;
    private static volatile boolean chromeRequested;

    public static void setChromeRequested(boolean requested){
        chromeRequested=requested;
    }

    public static boolean isChromeRequested(){
        return chromeRequested;
    }
    private static volatile TaskbarOverlay keptTaskbar;
    private static volatile TaskViewOverlay keptTaskView;
    private static volatile FullscreenEdgeManager keptEdges;
    private InputManager inputManager;
    private InputManager.InputDeviceListener inputDeviceListener;

    public static void keepAlive(Context context,RootBridge bridge,WindowController controller,ChromeManager chrome,
                                 TaskbarOverlay taskbar,TaskViewOverlay taskView,FullscreenEdgeManager edges){
        keptBridge=bridge;keptController=controller;keptChrome=chrome;keptTaskbar=taskbar;keptTaskView=taskView;keptEdges=edges;
        // L-06: the service owns the controller, so it also owns the static registration that
        // KeyService, ActionCenterOverlay and LauncherSettingsActivity read. The registration
        // has to be re-asserted on every binding, not only in the controller constructor.
        if(controller!=null)controller.claimActive();
        Intent i=new Intent(context.getApplicationContext(),DockService.class);
        if(Build.VERSION.SDK_INT>=26)context.getApplicationContext().startForegroundService(i);
        else context.getApplicationContext().startService(i);
    }
    public static boolean retains(WindowController controller){return controller!=null&&keptController==controller;}
    public static boolean isRunning(){return instance!=null;}
    public static RootBridge bridge(){return keptBridge;}
    public static WindowController controller(){return keptController;}
    public static ChromeManager chrome(){return keptChrome;}
    public static TaskbarOverlay taskbar(){return keptTaskbar;}
    public static TaskViewOverlay taskView(){return keptTaskView;}
    public static FullscreenEdgeManager edges(){return keptEdges;}

    @Override public void onCreate(){super.onCreate();instance=this;startForeground(NOTIFICATION_ID,buildNotification());if(keptController==null)createControlPlane();registerInputDeviceListener();startOwnedOverlays();}
    // H-01: with configChanges in the manifest, rotations reach the service even while
    // MainActivity is in the background; geometry and overlays must follow.
    @Override public void onConfigurationChanged(android.content.res.Configuration newConfig){
        super.onConfigurationChanged(newConfig);
        if(keptController!=null){keptController.updateDisplayGeometry();keptController.refreshUi();}
    }

    private void registerInputDeviceListener(){
        inputManager=(InputManager)getSystemService(INPUT_SERVICE);
        if(inputManager==null)return;
        inputDeviceListener=new InputManager.InputDeviceListener(){
            private void refreshDisplayAndUi(){
                if(keptController!=null){keptController.updateDisplayGeometry();keptController.refreshUi();}
            }
            @Override public void onInputDeviceAdded(int deviceId){refreshDisplayAndUi();}
            @Override public void onInputDeviceRemoved(int deviceId){refreshDisplayAndUi();}
            @Override public void onInputDeviceChanged(int deviceId){refreshDisplayAndUi();}
        };
        inputManager.registerInputDeviceListener(inputDeviceListener,null);
    }

    @Override public int onStartCommand(Intent intent,int flags,int startId){if(keptController==null)createControlPlane();startOwnedOverlays();return START_STICKY;}
    @Override public IBinder onBind(Intent intent){return null;}

    private void createControlPlane(){
        String token=RootServerLauncher.getOrCreateToken(this);
        keptBridge=new RootBridge(token);
        keptController=new WindowController(getApplicationContext(),keptBridge);
        keptChrome=new ChromeManager(getApplicationContext(),keptController);
        keptTaskbar=new TaskbarOverlay(getApplicationContext(),keptController);
        keptTaskView=new TaskViewOverlay(getApplicationContext(),keptController);
        keptTaskbar.setActions(new TaskbarOverlay.Actions(){
            @Override public void openTaskView(){if(keptTaskView!=null)keptTaskView.show();}
            @Override public void openStart(){Intent desktop=new Intent(DockService.this,DesktopActivity.class);desktop.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);startActivity(desktop);}
        });
        keptEdges=new FullscreenEdgeManager(getApplicationContext(),keptController,keptTaskbar);
    }

    private boolean hasOverlayPermission(){
        return Build.VERSION.SDK_INT<23||android.provider.Settings.canDrawOverlays(this);
    }

        private void startOwnedOverlays(){
        if(keptController!=null)keptController.startMonitoring();
        if(keptTaskbar!=null&&hasOverlayPermission())keptTaskbar.enable();
        if(keptEdges!=null&&hasOverlayPermission())keptEdges.enable();
        // F-10: titles are opt-in. Step 3 ("Сканировать задачи") only raises monitoring,
        // taskbar and edges; window titles start only after step 4 asked for them.
        if(keptChrome!=null&&chromeRequested&&keptChrome.hasOverlayPermission())keptChrome.enable();
    }

    private Notification buildNotification(){
        if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);nm.createNotificationChannel(new NotificationChannel(CHANNEL,"WinLauncher",NotificationManager.IMPORTANCE_LOW));}
        Intent open=new Intent(this,MainActivity.class);open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b=new Notification.Builder(this).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("WinLauncher активен").setContentText("Управление окнами и панель задач")
                .setOngoing(true).setContentIntent(pi);
        if(Build.VERSION.SDK_INT>=26)b.setChannelId(CHANNEL);
        return b.build();
    }

    @Override public void onDestroy(){
        if(inputManager!=null&&inputDeviceListener!=null)inputManager.unregisterInputDeviceListener(inputDeviceListener);
        inputDeviceListener=null;inputManager=null;
        if(keptChrome!=null)keptChrome.disable();
        if(keptEdges!=null)keptEdges.disable();
        if(keptTaskbar!=null)keptTaskbar.disable();
        if(keptTaskView!=null)keptTaskView.dismissActive();
        ActionCenterOverlay.dismissActive(); // S12a/S27: keyboard-opened panels are static singletons
        if(keptController!=null){keptController.stopMonitoring();keptController.releaseActive();}
        if(keptBridge!=null)keptBridge.close();
        keptBridge=null;keptController=null;keptChrome=null;keptTaskbar=null;keptTaskView=null;keptEdges=null;instance=null;
        super.onDestroy();
    }
}
