package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;

public class SPUtils {
    private static final String SP_NAME = "app_settings";
    private static SharedPreferences sPrefs;

    // 初始化，建议在Application中调用一次
    public static void init(Context context) {
        if (sPrefs == null) {
            sPrefs = context.getApplicationContext().
                    getSharedPreferences(SP_NAME, Context.MODE_PRIVATE);
        }
    }

    // 对外提供获取方法
    public static SharedPreferences getPrefs() {
        if (sPrefs == null) {
            throw new IllegalStateException("请先调用init方法初始化");
        }
        return sPrefs;
    }
}
