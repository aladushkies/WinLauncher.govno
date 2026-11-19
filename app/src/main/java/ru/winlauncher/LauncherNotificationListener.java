package ru.winlauncher;

import android.app.Notification;
import android.app.PendingIntent;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Optional user-authorized bridge for displaying Android's active notifications in Action Center. */
public final class LauncherNotificationListener extends NotificationListenerService {
    public static final class Entry {
        public final String key,packageName,title,text;
        public final long postedAt;
        public final PendingIntent contentIntent;
        Entry(String key,String packageName,String title,String text,long postedAt,PendingIntent contentIntent){
            this.key=key;this.packageName=packageName;this.title=title;this.text=text;this.postedAt=postedAt;this.contentIntent=contentIntent;
        }
    }
    private static final Object LOCK=new Object();
    private static final ArrayList<Entry> entries=new ArrayList<>();
    private static volatile boolean connected;

    public static boolean isConnected(){return connected;}
    public static List<Entry> snapshot(){synchronized(LOCK){return Collections.unmodifiableList(new ArrayList<>(entries));}}

    @Override public void onListenerConnected(){super.onListenerConnected();connected=true;StatusBarNotification[] active=null;try{active=getActiveNotifications();}catch(RuntimeException ignored){}synchronized(LOCK){entries.clear();if(active!=null)for(StatusBarNotification sbn:active)addOrReplace(toEntry(sbn));}}
    // BUG-24: on disconnect the cached entries (and their PendingIntents) must be dropped,
    // and getActiveNotifications() can throw while the system is rebinding the listener.
    @Override public void onListenerDisconnected(){connected=false;synchronized(LOCK){entries.clear();}super.onListenerDisconnected();}
    @Override public void onNotificationPosted(StatusBarNotification sbn){if(sbn!=null)synchronized(LOCK){addOrReplace(toEntry(sbn));}}
    @Override public void onNotificationRemoved(StatusBarNotification sbn){if(sbn==null)return;synchronized(LOCK){for(int i=entries.size()-1;i>=0;i--)if(entries.get(i).key.equals(sbn.getKey()))entries.remove(i);}}

    private static void addOrReplace(Entry entry){
        for(int i=entries.size()-1;i>=0;i--)if(entries.get(i).key.equals(entry.key))entries.remove(i);
        entries.add(0,entry);while(entries.size()>30)entries.remove(entries.size()-1);
    }
    private static Entry toEntry(StatusBarNotification sbn){
        Notification n=sbn.getNotification();CharSequence title=null,text=null;
        if(n!=null&&n.extras!=null){title=n.extras.getCharSequence(Notification.EXTRA_TITLE);text=n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT);if(text==null)text=n.extras.getCharSequence(Notification.EXTRA_TEXT);}
        String key=sbn.getKey()==null?"":sbn.getKey(); // S21: getKey() is nullable; equals() used to NPE
        return new Entry(key,sbn.getPackageName(),title==null?"Уведомление":String.valueOf(title),text==null?"":String.valueOf(text),sbn.getPostTime(),n==null?null:n.contentIntent);
    }
}
