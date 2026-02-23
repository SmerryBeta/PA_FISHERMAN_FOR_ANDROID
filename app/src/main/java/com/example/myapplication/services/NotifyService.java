package com.example.myapplication.services;

import android.app.Notification;
import android.content.Intent;
import android.app.Service;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.myapplication.NotifyNotificationFactory;
import com.example.myapplication.SPUtils;

/**
 * 通知服务 - 使用 WebSocket 连接脚本服务器接收消息
 */
public class NotifyService extends Service {
    private static final String TAG = "NotifyService";

    private NotifyWebSocketClient webSocketClient;
    private final IBinder binder = new LocalBinder();

    public static final String ACTION_ENABLE = "notify_enable";
    public static final String ACTION_DISABLE = "notify_disable";

    private ConnectionStatusCallback statusCallback;

    public interface ConnectionStatusCallback {
        void onConnected();
        void onDisconnected(String reason);
        void onError(String error);
    }

    public class LocalBinder extends Binder {
        public NotifyService getService() {
            return NotifyService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.e(TAG, "===== NotifyService onCreate =====");

        // 创建前台通知
        Notification notification = NotifyNotificationFactory.create(this);
        startForeground(1, notification);

        // 初始化 WebSocket 客户端
        Log.e(TAG, "初始化 WebSocket 客户端...");
        webSocketClient = new NotifyWebSocketClient(getBaseContext());
        webSocketClient.setConnectionCallback(new NotifyWebSocketClient.ConnectionCallback() {
            @Override
            public void onConnected() {
                if (statusCallback != null) {
                    statusCallback.onConnected();
                }
            }

            @Override
            public void onDisconnected(String reason) {
                if (statusCallback != null) {
                    statusCallback.onDisconnected(reason);
                }
            }

            @Override
            public void onError(String error) {
                if (statusCallback != null) {
                    statusCallback.onError(error);
                }
            }
        });

        // 如果之前已启用，自动连接
        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            enable();
        }
    }

    public void setStatusCallback(ConnectionStatusCallback callback) {
        this.statusCallback = callback;
    }

    public void disable() {
        SPUtils.getPrefs().edit()
                .putBoolean("notify", false)
                .apply();

        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }

    public void enable() {
        Log.d(TAG, "===== enable() 被调用 =====");
        SPUtils.getPrefs().edit()
                .putBoolean("notify", true)
                .apply();

        if (webSocketClient != null) {
            Log.d(TAG, "调用 webSocketClient.connect()...");
            webSocketClient.connect();
        } else {
            Log.d(TAG, "webSocketClient 为 null!");
        }
    }

    /**
     * 重新连接到服务器（用于服务器地址变更后）
     */
    public void reconnect() {
        if (webSocketClient != null) {
            webSocketClient.disconnect();
            // 短暂延迟后重新连接
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (SPUtils.getPrefs().getBoolean("notify", false)) {
                    webSocketClient.connect();
                }
            }, 500);
        }
    }

    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isConnected();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ENABLE.equals(action)) {
                enable();
            } else if (ACTION_DISABLE.equals(action)) {
                disable();
            }
        }
        return START_STICKY;
    }
}

