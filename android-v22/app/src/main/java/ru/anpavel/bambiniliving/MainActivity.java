package ru.anpavel.bambiniliving;
import android.app.Activity; import android.graphics.Color; import android.os.Bundle; import android.view.*; import android.webkit.*; import java.util.*;
public class MainActivity extends Activity {
 private static final String BASE="https://bambini.anpavel.ru";
 private static final String APP_KEY=("_SEs08BNhi4G1ZRKuYI_"+"mimSSeEtOL8WiG1g0qe_"+"5qgoLJVxTEb7Z2_geKZl-Vxn");
 private WebView web;
 public void onCreate(Bundle b){super.onCreate(b);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);web=new WebView(this);web.setBackgroundColor(Color.BLACK);web.setLayerType(View.LAYER_TYPE_HARDWARE,null);WebSettings s=web.getSettings();s.setJavaScriptEnabled(true);s.setDomStorageEnabled(true);s.setMediaPlaybackRequiresUserGesture(false);CookieManager.getInstance().setAcceptCookie(true);web.setWebViewClient(new WebViewClient(){public boolean shouldOverrideUrlLoading(WebView v,WebResourceRequest r){String u=r.getUrl().toString();if(u.contains("/login")){enroll();return true;}return !u.startsWith(BASE);} public void onPageFinished(WebView v,String u){if(u.contains("/login"))enroll();}});setContentView(web);immersive();enroll();}
 private void enroll(){Map<String,String> h=new HashMap<>();h.put("X-Bambini-App-Key",APP_KEY);web.loadUrl(BASE+"/living/app",h);}
 private void immersive(){web.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN|View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);}
 public void onWindowFocusChanged(boolean h){super.onWindowFocusChanged(h);if(h)immersive();} public void onBackPressed(){web.loadUrl(BASE+"/living");}
}
