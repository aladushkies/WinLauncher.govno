package ru.winlauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Small preference store for the behavior switches required by the window manager. */
public final class LauncherPrefs {
    private static final String FILE = "launcher_settings";
    private final SharedPreferences p;
    public LauncherPrefs(Context context) { p = context.getSharedPreferences(FILE, Context.MODE_PRIVATE); }

    public boolean windowsButtonBehavior() { return p.getBoolean("windows_button_behavior", false); }
    public void setWindowsButtonBehavior(boolean value) { p.edit().putBoolean("windows_button_behavior", value).apply(); }
    public boolean escapeExitsFullscreen() { return p.getBoolean("escape_exits_fullscreen", false); }
    public void setEscapeExitsFullscreen(boolean value) { p.edit().putBoolean("escape_exits_fullscreen", value).apply(); }
    public boolean hideStatusBar() { return p.getBoolean("hide_status_bar", true); }
    public void setHideStatusBar(boolean value) { p.edit().putBoolean("hide_status_bar", value).apply(); }
    public boolean darkTheme() { return p.getBoolean("dark_theme", false); }
    public void setDarkTheme(boolean value) { p.edit().putBoolean("dark_theme", value).apply(); }
    public String inputProfile() { return p.getString("input_profile", "AUTO"); }
    public boolean mouseProfile() {
        String profile = p.getString("input_profile", "AUTO");
        if ("MOUSE".equals(profile)) return true;
        if ("TOUCH".equals(profile)) return false;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && (device.getSources() & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE) return true;
        }
        return false;
    }
    public void setMouseProfile(boolean value) { p.edit().putString("input_profile", value ? "MOUSE" : "TOUCH").apply(); }
    public void setAutoInputProfile() { p.edit().putString("input_profile", "AUTO").apply(); }
    public boolean doubleSwipeEdges() { return p.getBoolean("double_swipe_edges", false); }
    public void setDoubleSwipeEdges(boolean value) { p.edit().putBoolean("double_swipe_edges", value).apply(); }
    public boolean shakeToMinimize() { return p.getBoolean("shake_to_minimize", false); }
    public void setShakeToMinimize(boolean value) { p.edit().putBoolean("shake_to_minimize", value).apply(); }
    public int snapGapPx() { return Math.max(0, Math.min(8, p.getInt("snap_gap_px", 4))); }
    public void setSnapGapPx(int value) { p.edit().putInt("snap_gap_px", Math.max(0, Math.min(8, value))).apply(); }
    public boolean animationsEnabled(){return p.getBoolean("animations_enabled",false);}
    public void setAnimationsEnabled(boolean value){p.edit().putBoolean("animations_enabled",value).apply();}

    public int wallpaperIndex(){return Math.max(0,p.getInt("wallpaper_index",0));}
    public void setWallpaperIndex(int index){p.edit().putInt("wallpaper_index",Math.max(0,index)).apply();}
    public boolean wallpaperSlideshow(){return p.getBoolean("wallpaper_slideshow",false);}
    public void setWallpaperSlideshow(boolean enabled){p.edit().putBoolean("wallpaper_slideshow",enabled).apply();}

    public int virtualDesktopCount(){return Math.max(1,Math.min(4,p.getInt("virtual_desktop_count",1)));}
    public int currentVirtualDesktop(){return Math.max(0,Math.min(virtualDesktopCount()-1,p.getInt("current_virtual_desktop",0)));}
    public void setVirtualDesktopCount(int count){p.edit().putInt("virtual_desktop_count",Math.max(1,Math.min(4,count))).apply();}
    public void setCurrentVirtualDesktop(int id){p.edit().putInt("current_virtual_desktop",Math.max(0,Math.min(virtualDesktopCount()-1,id))).apply();}
    public int taskDesktop(int taskId,int fallback){return p.getInt("task_desktop_"+taskId,fallback);}
    public void setTaskDesktop(int taskId,int desktop){p.edit().putInt("task_desktop_"+taskId,Math.max(0,Math.min(3,desktop))).apply();}
    public void clearTaskDesktop(int taskId){p.edit().remove("task_desktop_"+taskId).apply();}
    public Set<String> virtualDesktopHiddenTasks(){return new HashSet<>(p.getStringSet("virtual_hidden_tasks",new HashSet<>()));}
    public void setVirtualDesktopHiddenTasks(Set<String> ids){p.edit().putStringSet("virtual_hidden_tasks",new HashSet<>(ids)).apply();}

    public String browserLastUrl(){return p.getString("browser_last_url","https://duckduckgo.com/");}
    public void setBrowserLastUrl(String value){p.edit().putString("browser_last_url",value).apply();}
    public List<String> browserHistory(){String raw=p.getString("browser_history","");ArrayList<String> out=new ArrayList<>();for(String line:raw.split("\\n"))if(line.length()>0)out.add(line);return out;}
    public void addBrowserHistory(String value){ArrayList<String> out=new ArrayList<>();out.add(value);for(String old:browserHistory())if(!old.equals(value)&&out.size()<100)out.add(old);StringBuilder b=new StringBuilder();for(String item:out){if(b.length()>0)b.append('\n');b.append(item);}p.edit().putString("browser_history",b.toString()).apply();}
    public void clearBrowserHistory(){p.edit().remove("browser_history").apply();}
    public Set<String> appWidgetIds(){return new HashSet<>(p.getStringSet("app_widget_ids",new HashSet<>()));}
    public void setAppWidgetIds(Set<String> ids){p.edit().putStringSet("app_widget_ids",new HashSet<>(ids)).apply();}

    public Set<String> disabledEdgePackages() {
        return new HashSet<>(p.getStringSet("edge_disabled_packages", new HashSet<>()));
    }
    public void setEdgeDisabled(String packageName, boolean disabled) {
        Set<String> values = disabledEdgePackages();
        if (disabled) values.add(packageName); else values.remove(packageName);
        p.edit().putStringSet("edge_disabled_packages", values).apply();
    }
    public boolean areEdgesDisabled(String packageName) { return disabledEdgePackages().contains(packageName); }

    public int captionHeight(int fallbackPx) { return p.getInt("samsung_caption_height", fallbackPx); }
    public int captionLeftInset() { return p.getInt("samsung_caption_left", 0); }
    public int captionRightInset() { return p.getInt("samsung_caption_right", 0); }
    public boolean isCaptionCalibrated() { return p.getBoolean("samsung_caption_calibrated", false); }
    public void setCaptionCalibration(int height, int left, int right) {
        p.edit().putInt("samsung_caption_height", Math.max(24, height))
                .putInt("samsung_caption_left", Math.max(0, left))
                .putInt("samsung_caption_right", Math.max(0, right))
                .putBoolean("samsung_caption_calibrated", true).apply();
    }

    public void setWindowBounds(int taskId, int left, int top, int right, int bottom, String state) {
        p.edit().putString("task_" + taskId, left + "," + top + "," + right + "," + bottom + "," + state).apply();
    }
    public void saveMinimizedState(int taskId,String packageName,int left,int top,int right,int bottom,String state,String groupId,String layout,int cell,String cellBounds) {
        p.edit().putString("task_"+taskId,packageName+"|"+state+"|"+left+","+top+","+right+","+bottom+"|"+(groupId==null?"":groupId)+"|"+(layout==null?"":layout)+"|"+cell+"|"+(cellBounds==null?"":cellBounds)).apply();
    }
    public String savedWindow(int taskId) { return p.getString("task_" + taskId, null); }
    public void clearSavedSnapGroup(int taskId){
        String saved=savedWindow(taskId);if(saved==null)return;String[] parts=saved.split("\\|",-1);if(parts.length<6)return;
        parts[3]="";parts[4]="";parts[5]="-1";if(parts.length>6)parts[6]="";
        // Once detached, a minimized snapped task no longer has a cell to restore into.
        if("SNAPPED".equals(parts[1]))parts[1]="NORMAL";
        StringBuilder value=new StringBuilder();for(int i=0;i<parts.length;i++){if(i>0)value.append('|');value.append(parts[i]);}
        p.edit().putString("task_"+taskId,value.toString()).apply();
    }
    public void clearSavedWindow(int taskId) { p.edit().remove("task_"+taskId).apply(); }
}
