package com.example.myapplication.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.myapplication.MainActivity;
import com.example.myapplication.R;
import com.example.myapplication.SoundManager;
import com.example.myapplication.SPUtils;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 客户端，用于连接脚本服务器接收消息通知
 */
public class NotifyWebSocketClient {
    private static final String TAG = "NotifyWebSocket";
    private static final int RECONNECT_DELAY_MS = 5000; // 重连延迟5秒
    private static final int MAX_RECONNECT_ATTEMPTS = 10; // 最大重连次数

    private Context context;
    private WebSocketClient webSocketClient;
    private Handler reconnectHandler;
    private Runnable reconnectRunnable;
    private boolean isRunning = false;
    private boolean isConnecting = false; // 防止重复连接
    private int reconnectAttempts = 0;
    private ConnectionCallback connectionCallback;

    public interface ConnectionCallback {
        void onConnected();

        void onDisconnected(String reason);

        void onError(String error);
    }

    public NotifyWebSocketClient(Context context) {
        this.context = context.getApplicationContext();
        this.reconnectHandler = new Handler(Looper.getMainLooper());
    }

    public void setConnectionCallback(ConnectionCallback callback) {
        this.connectionCallback = callback;
    }

    /**
     * 连接到 WebSocket 服务器
     */
    public void connect() {
        // 必定输出的日志，用于确认方法被调用
        Log.d(TAG, "===== connect() 被调用 =====");

        if (isRunning) {
            Log.d(TAG, "已经在运行中");
            return;
        }

        // 防止重复连接
        if (isConnecting) {
            Log.d(TAG, "正在连接中，跳过重复调用");
            return;
        }
        isConnecting = true;

        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        int serverPort = SPUtils.getPrefs().getInt("server_port", 1225);

        Log.i(TAG, "服务器地址: " + serverUrl + ":" + serverPort);

        if (serverUrl.isEmpty()) {
            Log.i(TAG, "服务器地址未设置，无法连接");
            isConnecting = false;
            if (connectionCallback != null) {
                connectionCallback.onError("服务器地址未设置");
            }
            return;
        }

        // 构建 WebSocket URI
        String wsUrl = String.format(
                Locale.US,
                "ws://%s:%d/ws/notify",
                serverUrl,
                serverPort
        );
        Log.d(TAG, "正在连接到: " + wsUrl);

        try {
            URI uri = new URI(wsUrl);
            Log.i(TAG, "创建 WebSocketClient，URI: " + uri);

            webSocketClient = new WebSocketClient(uri) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    Log.i(TAG, "WebSocket 已连接");
                    isRunning = true;
                    isConnecting = false;
                    reconnectAttempts = 0;
                    if (connectionCallback != null) {
                        new Handler(Looper.getMainLooper()).post(() -> connectionCallback.onConnected());
                    }
                }

                @Override
                public void onMessage(String message) {
                    handleMessage(message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    Log.i(TAG, "WebSocket 已关闭: " + reason + " (code: " + code + ")");
                    isRunning = false;
                    isConnecting = false;
                    if (connectionCallback != null) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                connectionCallback.onDisconnected(reason));
                    }
                    // 尝试重连
                    scheduleReconnect();
                }

                @Override
                public void onError(Exception ex) {
                    Log.e(TAG, "WebSocket 错误: " + ex.getMessage(), ex);
                    isRunning = false;
                    isConnecting = false;
                    if (connectionCallback != null) {
                        new Handler(Looper.getMainLooper()).post(() ->
                                connectionCallback.onError(ex.getMessage()));
                    }
                    // 发生错误时也尝试重连
                    scheduleReconnect();
                }
            };

            Log.d(TAG, "调用 webSocketClient.connect()...");
            webSocketClient.connect();
            Log.d(TAG, "webSocketClient.connect() 已调用");
        } catch (Exception e) {
            Log.e(TAG, "连接失败: " + e.getMessage(), e);
            isRunning = false;
            isConnecting = false;
            if (connectionCallback != null) {
                connectionCallback.onError("连接失败: " + e.getMessage());
            }
            scheduleReconnect();
        }
    }

    /**
     * 处理收到的消息
     */
    private void handleMessage(String message) {
        try {
            JSONObject json = new JSONObject(message);
            String type = json.optString("type", "normal");
            String title = json.optString("title", "新消息");
            String content = json.optString("content", "");
            String importance = json.optString("importance", "normal");

            Log.d(TAG, "收到消息: " + title + " - " + content);

            // 发送系统通知
            sendSystemNotification(title, content, importance);

        } catch (JSONException e) {
            Log.e(TAG, "消息解析失败: " + e.getMessage());
        }
    }

    /**
     * 发送系统通知
     */
    private void sendSystemNotification(String title, String content, String importance) {
        SoundManager.NotifyType[] values = SoundManager.NotifyType.values();
        SoundManager.NotifyType type = SoundManager.NotifyType.NORMAL;
        for (SoundManager.NotifyType value : values) {
            if (Objects.equals(value.getName(), importance)) {
                type = value;
            }
        }

        String channelId = type.getChannelId();

        // 创建点击通知时的 Intent，打开 MainActivity 并导航到控制面板
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra("navigate_to", "control_panel");
        
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(context, 0, intent, flags);

        Notification notification = new NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_stat_name)
                .setContentTitle(title)
                .setContentText(content)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 锁屏可见
                .setPriority(NotificationCompat.PRIORITY_HIGH) // 高优先级
                .setContentIntent(pendingIntent) // 点击跳转
                .build();

        try {
            NotificationManagerCompat.from(context)
                    .notify((int) System.currentTimeMillis(), notification);
        } catch (SecurityException e) {
            Log.w(TAG, "没有通知权限: " + e.getMessage());
        }
    }

    /**
     * 安排重连
     */
    private void scheduleReconnect() {
        Log.d(TAG, "===== scheduleReconnect() 被调用，当前尝试次数: " + reconnectAttempts);

        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "达到最大重连次数，停止重连");
            return;
        }

        // 移除之前的重连任务（如果有）
        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
        }

        reconnectAttempts++;
        Log.i(TAG, "将在 " + (RECONNECT_DELAY_MS / 1000) + " 秒后重连 (尝试 " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");

        reconnectRunnable = () -> {
            Log.d(TAG, "===== 重连任务执行，isRunning=" + isRunning);
            if (!isRunning) {
                Log.d(TAG, "调用 connect() 进行重连...");
                connect();
            } else {
                Log.d(TAG, "已经在运行中，跳过重连");
            }
        };

        reconnectHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
        Log.d(TAG, "重连任务已安排");
    }

    /**
     * 断开连接
     */
    public void disconnect() {
        Log.e(TAG, "===== disconnect() 被调用 =====");
        isRunning = false;
        reconnectAttempts = 0; // 重置重连计数

        if (reconnectRunnable != null) {
            reconnectHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }

        if (webSocketClient != null) {
            try {
                webSocketClient.close();
            } catch (Exception e) {
                Log.e(TAG, "关闭 WebSocket 时出错: " + e.getMessage());
            }
            webSocketClient = null;
        }
        Log.e(TAG, "断开连接完成");
    }

    /**
     * 检查是否已连接
     */
    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isOpen();
    }

    /**
     * 重置重连计数，允许重新连接
     */
    public void resetReconnectAttempts() {
        reconnectAttempts = 0;
        Log.d(TAG, "重连计数已重置");
    }

    /**
     * 强制重新连接（供用户手动触发）
     */
    public void forceReconnect() {
        Log.d(TAG, "===== forceReconnect() 被调用 =====");
        // 先断开现有连接
        disconnect();
        // 重置重连计数
        resetReconnectAttempts();
        // 稍延后重新连接
        reconnectHandler.postDelayed(this::connect, 300);
    }

    /**
     * 获取当前重连尝试次数
     */
    public int getReconnectAttempts() {
        return reconnectAttempts;
    }

    /**
     * 获取最大重连次数
     */
    public int getMaxReconnectAttempts() {
        return MAX_RECONNECT_ATTEMPTS;
    }
}
