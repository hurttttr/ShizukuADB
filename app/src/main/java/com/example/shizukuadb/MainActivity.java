package com.example.shizukuadb;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    
    private TextView tvStatus, tvIpInfo;
    private Button btnStart;
    private ProgressBar progressBar;
    private Handler mainHandler;
    private ExecutorService executor;
    
    private static final String SERVER_URL = "http://your-server.com:8080/report";
    private static final int ADB_PORT = 5555;
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        mainHandler = new Handler(Looper.getMainLooper());
        executor = Executors.newSingleThreadExecutor();
        
        initViews();
        checkShizuku();
    }
    
    private void initViews() {
        tvStatus = findViewById(R.id.tvStatus);
        tvIpInfo = findViewById(R.id.tvIpInfo);
        btnStart = findViewById(R.id.btnStart);
        progressBar = findViewById(R.id.progressBar);
        
        btnStart.setOnClickListener(v -> startProcess());
        tvIpInfo.setOnLongClickListener(v -> {
            copyToClipboard("adb connect " + tvIpInfo.getText().toString());
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
            return true;
        });
    }
    
    private void checkShizuku() {
        executor.execute(() -> {
            boolean ok = isShizukuAvailable();
            mainHandler.post(() -> {
                if (!ok) {
                    tvStatus.setText("⚠️ 请先启动 Shizuku");
                    btnStart.setEnabled(false);
                } else {
                    tvStatus.setText("✅ 就绪");
                }
            });
        });
    }
    
    private boolean isShizukuAvailable() {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"rish", "-c", "echo test"});
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void startProcess() {
        btnStart.setEnabled(false);
        progressBar.setVisibility(android.view.View.VISIBLE);
        
        executor.execute(() -> {
            try {
                updateStatus("开启无线ADB...", 30);
                enableWirelessAdb();
                Thread.sleep(1500);
                
                updateStatus("获取IP地址...", 60);
                String ip = getWifiIp();
                if (ip == null) throw new Exception("无法获取IP");
                
                updateStatus("上报服务端...", 90);
                boolean ok = reportToServer(ip);
                
                final String addr = ip + ":" + ADB_PORT;
                mainHandler.post(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvIpInfo.setText(addr);
                    tvIpInfo.setVisibility(android.view.View.VISIBLE);
                    tvStatus.setText(ok ? "✅ 成功" : "⚠️ 上报失败");
                    copyToClipboard("adb connect " + addr);
                    btnStart.setText("重新启动");
                    btnStart.setEnabled(true);
                });
                
            } catch (Exception e) {
                mainHandler.post(() -> {
                    progressBar.setVisibility(android.view.View.GONE);
                    tvStatus.setText("❌ " + e.getMessage());
                    btnStart.setEnabled(true);
                });
            }
        });
    }
    
    private boolean enableWirelessAdb() {
        try {
            Runtime.getRuntime().exec(new String[]{"rish", "-c", "setprop service.adb.tcp.port " + ADB_PORT}).waitFor();
            Thread.sleep(200);
            Runtime.getRuntime().exec(new String[]{"rish", "-c", "stop adbd"}).waitFor();
            Thread.sleep(500);
            Runtime.getRuntime().exec(new String[]{"rish", "-c", "start adbd"}).waitFor();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    
    private String getWifiIp() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (ni.getName().equalsIgnoreCase("wlan0")) {
                    for (java.net.InetAddress addr : Collections.list(ni.getInetAddresses())) {
                        if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                            return addr.getHostAddress();
                        }
                    }
                }
            }
        } catch (Exception e) {}
        return null;
    }
    
    private boolean reportToServer(String ip) {
        try {
            URL url = new URL(SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(5000);
            conn.setDoOutput(true);
            
            String json = String.format(
                "{\"device\":\"%s\",\"ip\":\"%s\",\"port\":%d,\"time\":%d}",
                android.os.Build.MODEL, ip, ADB_PORT, System.currentTimeMillis()/1000
            );
            
            DataOutputStream os = new DataOutputStream(conn.getOutputStream());
            os.writeBytes(json);
            os.close();
            
            return conn.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void updateStatus(String msg, int prog) {
        mainHandler.post(() -> {
            tvStatus.setText(msg);
            progressBar.setProgress(prog);
        });
    }
    
    private void copyToClipboard(String text) {
        ClipboardManager cb = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cb.setPrimaryClip(ClipData.newPlainText("adb", text));
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
