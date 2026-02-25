package com.example.myapplication;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import com.example.myapplication.utils.ToastUtils;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.navigation.ui.AppBarConfiguration;
import androidx.navigation.ui.NavigationUI;

import com.example.myapplication.databinding.ActivityMainBinding;
import com.example.myapplication.services.NotifyService;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.snackbar.Snackbar;

/**
 * MainActivity：应用主 Activity
 * 功能：
 * 1. 承载 Fragment（控制面板、设置等）
 * 2. 管理通知服务连接
 * 3. 处理底部导航
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private AppBarConfiguration appBarConfiguration;
    private ActivityMainBinding binding;
    private SoundManager soundManager;

    // 服务相关
    private NotifyService notifyService;
    private boolean serviceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            NotifyService.LocalBinder binder = (NotifyService.LocalBinder) service;
            notifyService = binder.getService();
            serviceBound = true;
            
            // 设置连接状态回调
            notifyService.setStatusCallback(new NotifyService.ConnectionStatusCallback() {
                @Override
                public void onConnected() {
                    runOnUiThread(() -> {
                        ToastUtils.showSuccessToast(MainActivity.this, "已连接到服务器");
                    });
                }

                @Override
                public void onDisconnected(String reason) {
                    runOnUiThread(() -> {
                        Log.d(TAG, "连接断开: " + reason);
                    });
                }

                @Override
                public void onError(String error) {
                    runOnUiThread(() -> {
                        ToastUtils.showErrorToast(MainActivity.this, "连接错误: " + error);
                    });
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            notifyService = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SPUtils.init(getBaseContext());

        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        // 初始化声音管理器
        soundManager = new SoundManager(this);
        soundManager.createAllChannel();

        // 设置导航
        setupNavigation();

        // 绑定服务
        bindNotifyService();

        // 检查是否需要自动扫描服务器
        checkAutoScan();
        
        // 处理通知点击跳转
        handleNotificationIntent(getIntent());
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNotificationIntent(intent);
    }
    
    private void handleNotificationIntent(Intent intent) {
        if (intent != null && "control_panel".equals(intent.getStringExtra("navigate_to"))) {
            // 导航到控制面板
            NavController navController = Navigation.findNavController(
                    this, R.id.nav_host_fragment_content_main);
            navController.navigate(R.id.ControlPanelFragment);
        }
    }

    private void setupNavigation() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);

        appBarConfiguration = new AppBarConfiguration.Builder(
                R.id.ControlPanelFragment, R.id.SettingsFragment)
                .build();

        // 设置底部导航
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            NavigationUI.setupWithNavController(bottomNav, navController);
        }
    }

    private void bindNotifyService() {
        Intent intent = new Intent(this, NotifyService.class);
        
        // 先绑定服务
        bindService(intent, serviceConnection, BIND_AUTO_CREATE);
        
        // 仅当之前已启用通知且服务未运行时，才启动服务
        // 注意：NotifyService.onCreate 中已经会检查 notify 设置并调用 enable()
        // 所以这里不需要发送 ACTION_ENABLE，避免重复连接
        if (SPUtils.getPrefs().getBoolean("notify", false)) {
            // 只启动服务，不发送 ACTION_ENABLE
            ContextCompat.startForegroundService(this, intent);
        }
    }

    private void checkAutoScan() {
        // 如果启用了自动扫描且没有配置服务器地址
        boolean autoScan = SPUtils.getPrefs().getBoolean("auto_scan", true);
        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        
        if (autoScan && serverUrl.isEmpty()) {
            // 提示用户扫描服务器
            Snackbar.make(binding.getRoot(), 
                    "请先在设置中扫描或配置服务器地址", 
                    Snackbar.LENGTH_LONG)
                    .setAction("去设置", v -> {
                        NavController navController = Navigation.findNavController(
                                this, R.id.nav_host_fragment_content_main);
                        navController.navigate(R.id.SettingsFragment);
                    })
                    .show();
        }
    }

    /**
     * 服务器设置改变时调用
     */
    public void onServerSettingsChanged() {
        // 重新连接服务
        if (serviceBound && notifyService != null) {
            notifyService.reconnect();
        }
        
        // 刷新控制面板
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);
        
        // 如果当前在控制面板页面，刷新它
        if (navController.getCurrentDestination() != null &&
            navController.getCurrentDestination().getId() == R.id.ControlPanelFragment) {
            // 通知控制面板刷新
            // 由于 Fragment 会重新加载，这里不需要特别处理
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            NavController navController = Navigation.findNavController(
                    this, R.id.nav_host_fragment_content_main);
            navController.navigate(R.id.SettingsFragment);
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);
        return NavigationUI.navigateUp(navController, appBarConfiguration)
                || super.onSupportNavigateUp();
    }

    @Override
    public void onBackPressed() {
        // 处理 WebView 返回
        NavController navController = Navigation.findNavController(
                this, R.id.nav_host_fragment_content_main);
        
        if (navController.getCurrentDestination() != null &&
            navController.getCurrentDestination().getId() == R.id.ControlPanelFragment) {
            // 尝试让控制面板的 WebView 返回
            // 这里可以通过 ViewModel 或其他方式通信
        }
        
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}
