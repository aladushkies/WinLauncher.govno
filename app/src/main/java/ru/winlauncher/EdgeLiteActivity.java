package ru.winlauncher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.Notification;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small system-WebView browser with in-memory tabs, DDG search, bounded URL history and DownloadManager. */
public final class EdgeLiteActivity extends Activity {
    private static final int REQUEST_DOWNLOAD_PERMISSION=4107;
    private final ArrayList<Tab> tabs=new ArrayList<>();
    private LauncherPrefs prefs;
    private boolean appliedDark; // U-07: theme the UI was built with
    private boolean appliedDarkKnown;
    private LinearLayout root,tabStrip;
    private EditText omnibox;
    private FrameSlot pageSlot;
    private Tab current;
    private boolean choosingHistory;

    private final class Tab {
        final WebView web;
        String url="about:blank",title="Новая вкладка";
        Tab(WebView web){this.web=web;}
    }
    private static final class FrameSlot extends LinearLayout {
        FrameSlot(Context c){super(c);setOrientation(VERTICAL);}
    }

    @Override protected void onCreate(Bundle state){
        super.onCreate(state);prefs=new LauncherPrefs(this);appliedDark=prefs.darkTheme();appliedDarkKnown=true;buildUi();
        // BUG-08: configChanges in the manifest now covers rotation/keyboard/navigation, and
        // the tab list is also saved so a process kill no longer loses every open tab.
        java.util.ArrayList<String> savedUrls=state==null?null:state.getStringArrayList("tab_urls");
        if(savedUrls!=null&&!savedUrls.isEmpty()){
            for(String url:savedUrls)addTab(url);
            int idx=state.getInt("tab_current",0);
            if(idx>=0&&idx<tabs.size()){setCurrent(tabs.get(idx));renderTabs();}
        }else addTab(prefs.browserLastUrl());
    }
    @Override protected void onSaveInstanceState(Bundle out){
        java.util.ArrayList<String> urls=new java.util.ArrayList<>();
        for(Tab tab:tabs)urls.add(tab.url);
        out.putStringArrayList("tab_urls",urls);
        out.putInt("tab_current",current==null?-1:tabs.indexOf(current));
        super.onSaveInstanceState(out);
    }
    private void buildUi(){
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(prefs.darkTheme()?0xff202020:0xfff4f4f4);
        HorizontalScrollView tabScroll=new HorizontalScrollView(this);tabScroll.setHorizontalScrollBarEnabled(false);tabStrip=new LinearLayout(this);tabStrip.setOrientation(LinearLayout.HORIZONTAL);tabScroll.addView(tabStrip);root.addView(tabScroll,new LinearLayout.LayoutParams(-1,dp(36)));
        LinearLayout toolbar=new LinearLayout(this);toolbar.setGravity(Gravity.CENTER_VERTICAL);toolbar.setPadding(dp(4),0,dp(4),0);toolbar.setBackgroundColor(prefs.darkTheme()?0xff2b2b2b:0xffe8e8e8);
        omnibox=new EditText(this);omnibox.setSingleLine(true);omnibox.setTextSize(13);omnibox.setHint("Поиск или адрес — DuckDuckGo");omnibox.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);omnibox.setImeOptions(EditorInfo.IME_ACTION_GO);boolean dark=prefs.darkTheme();omnibox.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);omnibox.setHintTextColor(dark?0xff9e9e9e:0xff666666);toolbar.addView(omnibox,new LinearLayout.LayoutParams(0,dp(40),1));
        Button go=new Button(this);go.setText("↵");go.setMinWidth(0);go.setMinimumWidth(0);go.setPadding(0,0,0,0);go.setOnClickListener(v->navigateFromOmnibox());toolbar.addView(go,new LinearLayout.LayoutParams(dp(44),dp(40)));
        Button history=new Button(this);history.setText("◷");history.setMinWidth(0);history.setMinimumWidth(0);history.setPadding(0,0,0,0);history.setOnClickListener(v->showHistory());toolbar.addView(history,new LinearLayout.LayoutParams(dp(40),dp(40)));
        Button add=new Button(this);add.setText("+");add.setMinWidth(0);add.setMinimumWidth(0);add.setPadding(0,0,0,0);add.setOnClickListener(v->addTab("about:blank"));toolbar.addView(add,new LinearLayout.LayoutParams(dp(40),dp(40)));
        root.addView(toolbar,new LinearLayout.LayoutParams(-1,dp(40)));
        pageSlot=new FrameSlot(this);root.addView(pageSlot,new LinearLayout.LayoutParams(-1,0,1));
        omnibox.setOnEditorActionListener((v,action,event)->{if(action==EditorInfo.IME_ACTION_GO||action==EditorInfo.IME_ACTION_DONE){navigateFromOmnibox();return true;}return false;});
        setContentView(root);
    }

    private void addTab(String url){
        // U-07 / I-05: the tab must know its URL immediately, and null URLs must normalize
        // to "about:blank" so setCurrent() can never NPE on tab.url.equals(...).
        String safeUrl=(url==null||url.length()==0)?"about:blank":url;
        WebView web=new WebView(this);configure(web);Tab tab=new Tab(web);tab.url=safeUrl;tabs.add(tab);setCurrent(tab);renderTabs();if(!"about:blank".equals(safeUrl))web.loadUrl(safeUrl);
    }
    private void configure(WebView web){
        WebSettings settings=web.getSettings();settings.setJavaScriptEnabled(true);settings.setDomStorageEnabled(true);settings.setLoadsImagesAutomatically(true);settings.setSupportMultipleWindows(false);settings.setJavaScriptCanOpenWindowsAutomatically(false);settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        web.setWebChromeClient(new WebChromeClient(){@Override public void onReceivedTitle(WebView view,String title){Tab t=find(view);if(t!=null){t.title=title==null||title.length()==0?"Вкладка":title;renderTabs();}}});
        web.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view,String url){return routeExternalOrLoad(view,url);}
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return routeExternalOrLoad(view,request.getUrl().toString());}
            @Override public void onPageStarted(WebView view,String url,android.graphics.Bitmap favicon){Tab t=find(view);if(t!=null){t.url=url;if(t==current)omnibox.setText(url);}}
            @Override public void onPageFinished(WebView view,String url){Tab t=find(view);if(t!=null){t.url=url;if(t==current)omnibox.setText(url);if(url.startsWith("http://")||url.startsWith("https://")){prefs.setBrowserLastUrl(url);prefs.addBrowserHistory(url);}}}
        });
        web.setDownloadListener(new DownloadListener(){@Override public void onDownloadStart(String url,String userAgent,String contentDisposition,String mimeType,long contentLength){download(url,userAgent,contentDisposition,mimeType);}});
    }
    private boolean routeExternalOrLoad(WebView view,String raw){
        if(raw==null)return false;Uri uri=Uri.parse(raw);String scheme=uri.getScheme();
        if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)||"about".equalsIgnoreCase(scheme))return false;
        try{Intent intent=new Intent(Intent.ACTION_VIEW,uri);startActivity(intent);}catch(RuntimeException ignored){Toast.makeText(this,"Нет приложения для этой ссылки",Toast.LENGTH_SHORT).show();}return true;
    }
    private Tab find(WebView web){for(Tab tab:tabs)if(tab.web==web)return tab;return null;}
    private void setCurrent(Tab tab){if(current!=null)pageSlot.removeView(current.web);current=tab;if(tab.web.getParent()!=null)((android.view.ViewGroup)tab.web.getParent()).removeView(tab.web);pageSlot.addView(tab.web,new LinearLayout.LayoutParams(-1,-1));omnibox.setText("about:blank".equals(tab.url)?"":tab.url);}
    private void navigateFromOmnibox(){if(current==null)return;String input=omnibox.getText().toString().trim();if(input.length()==0)return;current.web.loadUrl(resolve(input));}
    private String resolve(String input){
        String lower=input.toLowerCase(Locale.ROOT);if(lower.startsWith("http://")||lower.startsWith("https://"))return input;
        if(!input.contains(" ")&&input.contains("."))return "https://"+input;
        try{return "https://duckduckgo.com/?q="+URLEncoder.encode(input,"UTF-8");}catch(Exception ignored){return "https://duckduckgo.com/";}
    }
    private void renderTabs(){
        if(tabStrip==null)return;tabStrip.removeAllViews();for(Tab tab:new ArrayList<>(tabs)){
            // D-01: the tab cell used to have a single click handler that always closed the tab.
            // Split it into a select zone (label) and a separate close button.
            boolean dark=prefs.darkTheme();
            LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.HORIZONTAL);cell.setGravity(Gravity.CENTER_VERTICAL);cell.setBackgroundColor(tab==current?(dark?0xff2b2b2b:0xfff4f4f4):(dark?0xff3a3a3a:0xffd8d8d8));
            TextView label=new TextView(this);String title=tab.title.length()>16?tab.title.substring(0,15)+"…":tab.title;label.setText((tab==current?"● ":"")+title);label.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);label.setSingleLine(true);label.setGravity(Gravity.CENTER);label.setPadding(dp(10),0,dp(4),0);label.setOnClickListener(v->selectTab(tab));
            cell.addView(label,new LinearLayout.LayoutParams(-2,-1));
            TextView closeBtn=new TextView(this);closeBtn.setText("×");closeBtn.setTextColor(dark?0xfff5f5f5:0xff1a1a1a);closeBtn.setGravity(Gravity.CENTER);closeBtn.setPadding(dp(4),0,dp(10),0);closeBtn.setOnClickListener(v->closeTab(tab));
            cell.addView(closeBtn,new LinearLayout.LayoutParams(-2,-1));
            // BUG-03 (regression of D-01): the long-click listener used to sit on the cell, but
            // the clickable label and close button cover the whole cell and consume ACTION_DOWN,
            // so the cell never saw the long press. It lives on the label now; the drag shadow
            // still shows the whole cell.
            label.setOnLongClickListener(v->{int index=tabs.indexOf(tab);ClipData data=ClipData.newPlainText("edge-tab",String.valueOf(index));cell.startDragAndDrop(data,new View.DragShadowBuilder(cell),tab,0);return true;});
            cell.setOnDragListener((v,event)->{if(event.getAction()==android.view.DragEvent.ACTION_DROP&&event.getLocalState() instanceof Tab){Tab moving=(Tab)event.getLocalState();int from=tabs.indexOf(moving),to=tabs.indexOf(tab);if(from>=0&&to>=0&&from!=to){tabs.remove(from);tabs.add(to,moving);renderTabs();}return true;}return true;});
            tabStrip.addView(cell,new LinearLayout.LayoutParams(-2,-1));
        }
    }
    private void selectTab(Tab tab){
        if(tab==current)return;setCurrent(tab);renderTabs();
    }
    private void closeTab(Tab tab){
        if(tabs.size()==1){tab.web.loadUrl("about:blank");tab.url="about:blank";tab.title="Новая вкладка";omnibox.setText("");renderTabs();return;}
        int index=tabs.indexOf(tab);if(index<0)return;if(tab==current){Tab replacement=tabs.get(index==0?1:index-1);setCurrent(replacement);}tabs.remove(tab);tab.web.stopLoading();tab.web.destroy();renderTabs();
    }
    private void showHistory(){
        List<String> history=prefs.browserHistory();if(history.isEmpty()){Toast.makeText(this,"История пока пуста",Toast.LENGTH_SHORT).show();return;}
        String[] values=history.toArray(new String[0]);new AlertDialog.Builder(this).setTitle("История").setItems(values,(dialog,which)->{if(current!=null)current.web.loadUrl(values[which]);}).setNeutralButton("Очистить",(d,w)->{prefs.clearBrowserHistory();}).setNegativeButton("Закрыть",null).show();
    }
    private void download(String url,String userAgent,String contentDisposition,String mimeType){
        if(checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED){requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},REQUEST_DOWNLOAD_PERMISSION);Toast.makeText(this,"Разрешите доступ к файлам и повторите загрузку",Toast.LENGTH_LONG).show();return;}
        try{DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));request.setMimeType(mimeType);request.addRequestHeader("User-Agent",userAgent);String cookie=CookieManager.getInstance().getCookie(url);if(cookie!=null)request.addRequestHeader("Cookie",cookie);request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);String guessed=URLUtil.guessFileName(url,contentDisposition,mimeType);if(guessed==null||guessed.trim().length()==0)guessed=fileName(url);request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS,guessed);((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);Toast.makeText(this,"Загрузка добавлена в Android DownloadManager",Toast.LENGTH_SHORT).show();}
        catch(RuntimeException e){Toast.makeText(this,"Не удалось начать загрузку: "+e.getMessage(),Toast.LENGTH_LONG).show();}
    }
    private String fileName(String url){try{String path=Uri.parse(url).getLastPathSegment();if(path!=null&&path.length()>0)return path.replaceAll("[^A-Za-z0-9._-]","_");}catch(Exception ignored){}return "download-"+System.currentTimeMillis();}
    @Override protected void onResume(){
        super.onResume();
        // U-07: rebuild when the theme changed while we were in the background.
        if(appliedDarkKnown&&prefs!=null&&prefs.darkTheme()!=appliedDark){appliedDark=prefs.darkTheme();recreate();}
    }
    @Override public void onBackPressed(){if(current!=null&&current.web.canGoBack())current.web.goBack();else if(tabs.size()>1&&current!=null)closeTab(current);else super.onBackPressed();}
    @Override protected void onStop(){
        // S31: Edge Lite restored the first URL of the session, not the last visited page.
        if(current!=null&&current.url!=null&&(current.url.startsWith("http://")||current.url.startsWith("https://")))prefs.setBrowserLastUrl(current.url);
        super.onStop();
    }
    @Override protected void onDestroy(){for(Tab tab:tabs){tab.web.stopLoading();tab.web.destroy();}tabs.clear();super.onDestroy();}
    private int dp(float value){return(int)(value*getResources().getDisplayMetrics().density+.5f);}
}
