package com.example.myapplication.services;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.app.Service;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;

import com.example.myapplication.NotifyNotificationFactory;
import com.example.myapplication.SPUtils;

/**
 * 通知服务 - 使用 WebSocket 连接脚本服务器接收消息
 */
public class NotifyService extends Service {
    private static final String TAG = "NotifyService";
    private static final String WAKE_LOCK_TAG = "NotifyService:WakeLock";

    private NotifyWebSocketClient webSocketClient;
    private final IBinder binder = new LocalBinder();
    private PowerManager.WakeLock wakeLock;

    public static final String ACTION_ENABLE = "notify_enable";
    public static final String ACTION_DISABLE = "notify_disable";
    public static final String ACTION_RESTART = "notify_restart";

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

        // 获取 WakeLock 保持 CPU 活跃
        acquireWakeLock();

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
        
        // 如果已经连接，不重复连接
        if (webSocketClient != null && webSocketClient.isConnected()) {
            Log.d(TAG, "WebSocket 已连接，跳过重复连接");
            return;
        }
        
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

    /**
     * 强制重新连接（用户手动触发）
     */
    public void forceReconnect() {
        if (webSocketClient != null) {
            webSocketClient.forceReconnect();
        }
    }

    /**
     * 获取当前重连尝试次数
     */
    public int getReconnectAttempts() {
        return webSocketClient != null ? webSocketClient.getReconnectAttempts() : 0;
    }

    /**
     * 获取最大重连次数
     */
    public int getMaxReconnectAttempts() {
        return webSocketClient != null ? webSocketClient.getMaxReconnectAttempts() : 10;
    }

    public boolean isConnected() {
        return webSocketClient != null && webSocketClient.isConnected();
    }

    @Override
    public void onDestroy() {
        Log.e(TAG, "===== NotifyService onDestroy =====");
        super.onDestroy();
        if (webSocketClient != null) {
            webSocketClient.disconnect();
        }
        releaseWakeLock();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.e(TAG, "===== onTaskRemoved - 应用被从最近任务列表移除 =====");
        super.onTaskRemoved(rootIntent);
        
        // 如果用户已开启通知功能，尝试重启服务
        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            scheduleRestart();
        }
    }

    /**
     * 获取 WakeLock 保持 CPU 活跃
     */
    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        WAKE_LOCK_TAG
                );
                wakeLock.acquire(10 * 60 * 1000L); // 10分钟后自动释放，避免电量浪费
                Log.d(TAG, "WakeLock 已获取");
            }
        }
    }

    /**
     * 释放 WakeLock
     */
    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            wakeLock = null;
            Log.d(TAG, "WakeLock 已释放");
        }
    }

    /**
     * 调度服务重启
     */
    private void scheduleRestart() {
        Log.d(TAG, "调度服务重启...");
        Intent restartIntent = new Intent(this, NotifyService.class);
        restartIntent.setAction(ACTION_RESTART);
        
        int flags = PendingIntent.FLAG_ONE_SHOT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        
        PendingIntent pendingIntent = PendingIntent.getService(
                this, 0, restartIntent, flags
        );
        
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 1000, // 1秒后重启
                    pendingIntent
            );
            Log.d(TAG, "重启任务已调度");
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand - action: " + (intent != null ? intent.getAction() : "null"));
        
        // 确保 WakeLock 有效
        acquireWakeLock();
        
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ENABLE.equals(action) || ACTION_RESTART.equals(action)) {
                enable();
            } else if (ACTION_DISABLE.equals(action)) {
                disable();
            }
        }
        return START_STICKY; // 服务被杀后系统会尝试重启
    }
}

