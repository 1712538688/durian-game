package com.durian.game;

import android.os.Bundle;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.*;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import java.net.InetSocketAddress;

public class MainActivity extends AppCompatActivity {

    private WebView webView;
    private LinearLayout connectLayout;
    private EditText nicknameInput, ipInput;
    private TextView ipDisplay;
    private GameServer server;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            connectLayout = findViewById(R.id.connect_layout);
            nicknameInput = findViewById(R.id.nickname_input);
            ipInput = findViewById(R.id.ip_input);
            Button createBtn = findViewById(R.id.create_btn);
            Button joinBtn = findViewById(R.id.join_btn);
            ipDisplay = findViewById(R.id.ip_display);
            webView = findViewById(R.id.webview);

            webView.getSettings().setJavaScriptEnabled(true);
            webView.getSettings().setAllowFileAccess(true);
            webView.setWebViewClient(new WebViewClient());

            createBtn.setOnClickListener(v -> {
                try {
                    String name = nicknameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, "请输入昵称", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    try { if (server != null) server.stop(); } catch (Exception e) {}
                    server = new GameServer(new InetSocketAddress(3456));
                    server.start();
                    String ip = Utils.getLocalIPAddress(this);
                    ipDisplay.setText("本机 IP: " + ip + " (告知其他玩家)");
                    ipDisplay.setVisibility(View.VISIBLE);
                    loadGame("127.0.0.1", name);
                } catch (Exception e) {
                    Toast.makeText(this, "创建失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

            joinBtn.setOnClickListener(v -> {
                try {
                    String name = nicknameInput.getText().toString().trim();
                    String ip = ipInput.getText().toString().trim();
                    if (name.isEmpty() || ip.isEmpty()) {
                        Toast.makeText(this, "请输入昵称和主机IP", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    loadGame(ip, name);
                } catch (Exception e) {
                    Toast.makeText(this, "加入失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "初始化错误: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadGame(String serverIP, String nickname) {
        try {
            connectLayout.setVisibility(View.GONE);
            webView.setVisibility(View.VISIBLE);
            webView.loadUrl("file:///android_asset/game.html");
            webView.setWebViewClient(new WebViewClient() {
                @Override
                public void onPageFinished(WebView view, String url) {
                    view.evaluateJavascript(
                        "connectToServer('" + serverIP + "', '" + nickname + "');", null);
                }
                @Override
                public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                    Toast.makeText(MainActivity.this, "页面错误: " + description, Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "加载游戏界面失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onDestroy() {
        try { if (server != null) server.stop(); } catch (Exception e) {}
        super.onDestroy();
    }
}
