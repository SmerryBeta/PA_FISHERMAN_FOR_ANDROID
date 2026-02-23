package com.example.myapplication.utils;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.core.graphics.drawable.DrawableCompat;

import com.example.myapplication.R;

public class NotificationIconUtils {
    
    /**
     * 将 JPG 图片转换为适合作为通知图标的 Drawable
     * @param context 上下文
     * @return 适合作为通知图标的 Drawable
     */
    public static Drawable getNotificationIconFromJpg(Context context) {
        try {
            // 读取 JPG 图片
            Bitmap bitmap = BitmapFactory.decodeResource(context.getResources(), R.drawable.logo);
            
            // 调整大小（通知图标建议 24x24 dp）
            int size = (int) (24 * context.getResources().getDisplayMetrics().density);
            Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, size, size, true);
            
            // 转换为 Drawable
            Drawable drawable = new BitmapDrawable(context.getResources(), scaledBitmap);
            
            // 设置为白色（通知栏图标通常显示为白色）
            DrawableCompat.setTint(drawable, 0xFFFFFFFF);
            
            return drawable;
        } catch (Exception e) {
            // 如果转换失败，返回默认图标
            return context.getDrawable(R.drawable.ic_stat_name);
        }
    }
}