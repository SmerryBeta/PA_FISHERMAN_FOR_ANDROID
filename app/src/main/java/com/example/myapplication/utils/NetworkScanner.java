package com.example.myapplication.utils;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 局域网扫描工具，用于寻找脚本服务器
 */
public class NetworkScanner {
    private static final String TAG = "NetworkScanner";
    private static final int DEFAULT_PORT = 1225;
    private static final int CONNECT_TIMEOUT_MS = 2000;
    private static final int THREAD_POOL_SIZE = 50;

    private ExecutorService executorService;
    private Handler mainHandler;

    public interface ScanCallback {
        void onServerFound(String ip, int port, String info);

        void onScanComplete(List<ServerInfo> servers);

        void onProgress(int current, int total);
    }

    public static class ServerInfo {
        public String ip;
        public int port;
        public String info;

        public ServerInfo(String ip, int port, String info) {
            this.ip = ip;
            this.port = port;
            this.info = info;
        }
    }

    public NetworkScanner() {
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executorService = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    }

    /**
     * 扫描局域网寻找服务器
     *
     * @param subnet   子网地址，如 "192.168.1"
     * @param port     要扫描的端口
     * @param callback 回调接口
     */
    public void scan(String subnet, int port, ScanCallback callback) {
        List<ServerInfo> foundServers = java.util.Collections.synchronizedList(new ArrayList<>());
        final int totalHosts = 254;
        final int[] scannedCount = {0};

        Log.d(TAG, "开始扫描子网: " + subnet + ".x:" + port);

        for (int i = 1; i <= 254; i++) {
            final String ip = subnet + "." + i;
            executorService.execute(() -> {
                try {
                    // 直接尝试连接端口（跳过 isReachable，因为在非 root Android 上不可靠）
                    if (isPortOpen(ip, port)) {
                        Log.d(TAG, "端口开放: " + ip + ":" + port);
                        // 尝试获取服务器信息
                        String info = getServerInfo(ip, port);
                        if (info != null) {
                            ServerInfo serverInfo = new ServerInfo(ip, port, info);
                            foundServers.add(serverInfo);
                            mainHandler.post(() -> callback.onServerFound(ip, port, info));
                        } else {
                            // 即使无法获取 server_info，也标记为找到端口开放的服务器
                            String defaultInfo = ip + ":" + port;
                            ServerInfo serverInfo = new ServerInfo(ip, port, defaultInfo);
                            foundServers.add(serverInfo);
                            mainHandler.post(() -> callback.onServerFound(ip, port, defaultInfo));
                        }
                    }
                } catch (Exception e) {
                    Log.d(TAG, "扫描错误 " + ip + ": " + e.getMessage());
                } finally {
                    synchronized (scannedCount) {
                        scannedCount[0]++;
                        final int progress = scannedCount[0];
                        mainHandler.post(() -> callback.onProgress(progress, totalHosts));
                    }
                }
            });
        }

        // 等待所有扫描完成
        executorService.execute(() -> {
            try {
                // 等待直到所有扫描完成或超时
                int waitTime = 0;
                while (scannedCount[0] < totalHosts && waitTime < 15000) {
                    Thread.sleep(500);
                    waitTime += 500;
                }
                mainHandler.post(() -> callback.onScanComplete(new ArrayList<>(foundServers)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * 根据本机IP自动扫描整个局域网
     */
    public void scanLocalNetwork(int port, ScanCallback callback) {
        String localIp = getLocalIpAddress();
        if (localIp == null || localIp.equals("127.0.0.1")) {
            mainHandler.post(() -> callback.onScanComplete(new ArrayList<>()));
            return;
        }

        // 提取子网
        String subnet = localIp.substring(0, localIp.lastIndexOf('.'));
        scan(subnet, port, callback);
    }

    /**
     * 检查端口是否开放
     */
    private boolean isPortOpen(String ip, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(ip, port), CONNECT_TIMEOUT_MS);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 获取服务器信息
     */
    private String getServerInfo(String ip, int port) {
        HttpURLConnection conn = null;
        try {
            String urlStr = String.format(
                    Locale.US,
                    "http://%s:%d/server_info",
                    ip, port
            );
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(CONNECT_TIMEOUT_MS);
            conn.setRequestMethod("GET");

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(sb.toString());
                return json.optString("ip", ip) + ":" + json.optInt("port", port);
            }
        } catch (Exception e) {
            Log.d(TAG, "获取服务器信息失败: " + ip + ":" + port);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return null;
    }

    /**
     * 获取本机IP地址
     */
    private String getLocalIpAddress() {
        try {
            java.net.NetworkInterface wifiInterface = java.net.NetworkInterface.getByName("wlan0");
            if (wifiInterface == null) {
                // 尝试其他常见接口名
                wifiInterface = java.net.NetworkInterface.getByName("eth0");
            }
            if (wifiInterface != null) {
                java.util.Enumeration<java.net.InetAddress> addresses = wifiInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof java.net.Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }

            // 备用方案
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                java.util.Enumeration<java.net.InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    java.net.InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof java.net.Inet4Address) {
                        String ip = address.getHostAddress();
                        if (ip == null) {
                            return null;
                        }
                        // 优先返回局域网地址
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                                ip.matches("^172\\.(1[6-9]|2\\d|3[0-1])\\..*")) {
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "获取本机IP失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 停止扫描
     */
    public void stop() {
        if (executorService != null && !executorService.isShutdown()) {
            executorService.shutdownNow();
        }
    }
}
