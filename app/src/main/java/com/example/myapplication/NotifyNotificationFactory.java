package com.example.myapplication;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.example.myapplication.utils.NotificationIconUtils;

public final class NotifyNotificationFactory {

    private static final String CHANNEL_ID = "notify_service";
    private static final String CHANNEL_NAME = "通知服务";
    private static final int IMPORTANCE = NotificationManager.IMPORTANCE_LOW;

    private NotifyNotificationFactory() {
        // 禁止实例化
    }

    /**
     * 创建前台 Service 用的常驻通知
     */
    public static Notification create(Context context) {
        createChannelIfNeeded(context);

        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_name)  // 使用我们创建的纯色通知图标
                .setContentTitle("通知服务运行中")
                .setContentText("正在监听外部消息")
                .setOngoing(true)          // 不可划掉
                .setSilent(true)           // 不响铃
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private static void createChannelIfNeeded(Context context) {

        NotificationManager nm =
                context.getSystemService(NotificationManager.class);

        if (nm.getNotificationChannel(CHANNEL_ID) != null) return;

        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        CHANNEL_NAME,
                        IMPORTANCE
                );

        channel.setSound(null, null); // 前台通知绝不出声
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC); // 锁屏显示通知及内容

        nm.createNotificationChannel(channel);
    }
}
