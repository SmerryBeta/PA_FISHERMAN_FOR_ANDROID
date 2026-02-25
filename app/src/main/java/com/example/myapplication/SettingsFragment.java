package com.example.myapplication;

import android.app.ProgressDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.myapplication.databinding.FragmentSettingsBinding;
import com.example.myapplication.services.NotifyService;
import com.example.myapplication.utils.NetworkScanner;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置界面 Fragment
 * 用于配置服务器连接参数
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private NetworkScanner networkScanner;
    private Handler mainHandler;
    
    // 服务器列表相关
    private ServerListAdapter serverListAdapter;
    private List<NetworkScanner.ServerInfo> foundServers = new ArrayList<>();
    
    // 服务绑定相关
    private NotifyService notifyService;
    private boolean serviceBound = false;
    private Handler statusUpdateHandler;
    private Runnable statusUpdateRunnable;
    
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
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> updateConnectionStatus());
                    }
                }

                @Override
                public void onDisconnected(String reason) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> updateConnectionStatus());
                    }
                }

                @Override
                public void onError(String error) {
                    if (isAdded()) {
                        requireActivity().runOnUiThread(() -> updateConnectionStatus());
                    }
                }
            });
            
            // 立即更新状态
            updateConnectionStatus();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            serviceBound = false;
            notifyService = null;
            updateConnectionStatus();
        }
    };

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentSettingsBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        networkScanner = new NetworkScanner();
        mainHandler = new Handler(Looper.getMainLooper());
        statusUpdateHandler = new Handler(Looper.getMainLooper());

        // 加载已保存的设置
        loadSettings();

        // 设置按钮点击事件
        binding.btnSave.setOnClickListener(v -> saveSettings());
        binding.btnScan.setOnClickListener(v -> startScan());
        binding.btnTest.setOnClickListener(v -> testConnection());
        binding.btnReconnect.setOnClickListener(v -> forceReconnect());
        
        // 设置服务器列表
        setupServerList();
        
        // 绑定服务
        bindNotifyService();
        
        // 启动状态定时更新
        startStatusUpdates();
    }
    
    private void bindNotifyService() {
        Intent intent = new Intent(requireContext(), NotifyService.class);
        requireContext().bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }
    
    private void startStatusUpdates() {
        statusUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isAdded() && binding != null) {
                    updateConnectionStatus();
                    statusUpdateHandler.postDelayed(this, 2000); // 每2秒更新一次
                }
            }
        };
        statusUpdateHandler.post(statusUpdateRunnable);
    }
    
    private void updateConnectionStatus() {
        if (!isAdded() || binding == null) return;
        
        boolean notifyEnabled = SPUtils.getPrefs().getBoolean("notify", true);
        
        if (!notifyEnabled) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            binding.tvConnectionStatus.setText("提示器已禁用");
            binding.btnReconnect.setEnabled(false);
            return;
        }
        
        if (!serviceBound || notifyService == null) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
            binding.tvConnectionStatus.setText("服务未连接");
            binding.btnReconnect.setEnabled(true);
            return;
        }
        
        if (notifyService.isConnected()) {
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connected);
            binding.tvConnectionStatus.setText("已连接到服务器");
            binding.btnReconnect.setEnabled(true);
        } else {
            int attempts = notifyService.getReconnectAttempts();
            int maxAttempts = notifyService.getMaxReconnectAttempts();
            
            if (attempts >= maxAttempts) {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_disconnected);
                binding.tvConnectionStatus.setText("连接失败（已达最大重试次数）");
                binding.btnReconnect.setEnabled(true);
            } else if (attempts > 0) {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
                binding.tvConnectionStatus.setText("正在重连... (" + attempts + "/" + maxAttempts + ")");
                binding.btnReconnect.setEnabled(true);
            } else {
                binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
                binding.tvConnectionStatus.setText("正在连接...");
                binding.btnReconnect.setEnabled(true);
            }
        }
    }
    
    private void forceReconnect() {
        if (!SPUtils.getPrefs().getBoolean("notify", true)) {
            Toast.makeText(requireContext(), "请先启用提示器", Toast.LENGTH_SHORT).show();
            return;
        }
        
        if (serviceBound && notifyService != null) {
            Toast.makeText(requireContext(), "正在重新连接...", Toast.LENGTH_SHORT).show();
            notifyService.forceReconnect();
            
            // 立即更新状态显示
            binding.statusIndicator.setBackgroundResource(R.drawable.status_dot_connecting);
            binding.tvConnectionStatus.setText("正在连接...");
        } else {
            Toast.makeText(requireContext(), "服务未运行，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadSettings() {
        String serverUrl = SPUtils.getPrefs().getString("server_url", "");
        int serverPort = SPUtils.getPrefs().getInt("server_port", 1225);
        boolean autoScan = SPUtils.getPrefs().getBoolean("auto_scan", true);
        boolean notify = SPUtils.getPrefs().getBoolean("notify", true);

        binding.etServerUrl.setText(serverUrl);
        binding.etServerPort.setText(String.valueOf(serverPort));
        binding.switchAutoScan.setChecked(autoScan);
        binding.notify.setChecked(notify);
    }

    private void saveSettings() {
        String serverUrl = binding.etServerUrl.getText().toString().trim();
        String portStr = binding.etServerPort.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            binding.etServerUrl.setError("请输入服务器地址");
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(portStr);
            if (serverPort < 1 || serverPort > 65535) {
                binding.etServerPort.setError("端口范围 1-65535");
                return;
            }
        } catch (NumberFormatException e) {
            binding.etServerPort.setError("请输入有效的端口号");
            return;
        }

        // 保存设置
        SPUtils.getPrefs().edit()
                .putString("server_url", serverUrl)
                .putInt("server_port", serverPort)
                .putBoolean("auto_scan", binding.switchAutoScan.isChecked())
                .putBoolean("notify", binding.notify.isChecked())
                .apply();

        Toast.makeText(requireContext(), "设置已保存", Toast.LENGTH_SHORT).show();

        // 如果服务正在运行，通知重新连接
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).onServerSettingsChanged();
        }
    }
    
    private void setupServerList() {
        serverListAdapter = new ServerListAdapter(foundServers, server -> {
            // 点击服务器时，填入地址并尝试连接
            binding.etServerUrl.setText(server.ip);
            binding.etServerPort.setText(String.valueOf(server.port));
            Toast.makeText(requireContext(), "已选择: " + server.ip + ":" + server.port, Toast.LENGTH_SHORT).show();
        });
        binding.rvServerList.setLayoutManager(new LinearLayoutManager(requireContext()));
        binding.rvServerList.setAdapter(serverListAdapter);
    }

    private void startScan() {
        // 清空之前的结果
        foundServers.clear();
        if (serverListAdapter != null) {
            serverListAdapter.notifyDataSetChanged();
        }
        binding.tvServerListTitle.setVisibility(View.GONE);
        binding.rvServerList.setVisibility(View.GONE);
        
        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setMessage("正在扫描局域网...");
        progressDialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progressDialog.setMax(254);
        progressDialog.setCancelable(false);
        progressDialog.show();

        networkScanner.scanLocalNetwork(1225, new NetworkScanner.ScanCallback() {
            @Override
            public void onServerFound(String ip, int port, String info) {
                if (!isAdded() || getContext() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded() || binding == null) return;
                    // 检查是否已存在
                    for (NetworkScanner.ServerInfo s : foundServers) {
                        if (s.ip.equals(ip)) return;
                    }
                    foundServers.add(new NetworkScanner.ServerInfo(ip, port, info));
                    serverListAdapter.notifyItemInserted(foundServers.size() - 1);
                    // 显示列表
                    binding.tvServerListTitle.setVisibility(View.VISIBLE);
                    binding.rvServerList.setVisibility(View.VISIBLE);
                });
            }

            @Override
            public void onScanComplete(List<NetworkScanner.ServerInfo> servers) {
                if (!isAdded() || getContext() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    progressDialog.dismiss();
                    if (foundServers.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "未找到服务器，请手动输入地址", Toast.LENGTH_LONG).show();
                        binding.tvServerListTitle.setVisibility(View.GONE);
                        binding.rvServerList.setVisibility(View.GONE);
                    } else {
                        Toast.makeText(requireContext(),
                                "扫描完成，找到 " + foundServers.size() + " 个服务器",
                                Toast.LENGTH_SHORT).show();
                        // 如果只找到一个，自动填入
                        if (foundServers.size() == 1) {
                            NetworkScanner.ServerInfo server = foundServers.get(0);
                            binding.etServerUrl.setText(server.ip);
                            binding.etServerPort.setText(String.valueOf(server.port));
                        }
                    }
                });
            }

            @Override
            public void onProgress(int current, int total) {
                if (!isAdded() || getContext() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    progressDialog.setProgress(current);
                });
            }
        });
    }

    private void testConnection() {
        if (binding.etServerUrl.getText() == null) {
            Toast.makeText(requireContext(), "请先输入地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (binding.etServerPort.getText() == null) {
            Toast.makeText(requireContext(), "请输入端口", Toast.LENGTH_SHORT).show();
            return;
        }
        String serverUrl = binding.etServerUrl.getText().toString().trim();
        String portStr = binding.etServerPort.getText().toString().trim();

        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "请先输入服务器地址", Toast.LENGTH_SHORT).show();
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            Toast.makeText(requireContext(), "端口号无效", Toast.LENGTH_SHORT).show();
            return;
        }

        ProgressDialog progressDialog = new ProgressDialog(requireContext());
        progressDialog.setMessage("正在测试连接...");
        progressDialog.show();

        // 在后台线程测试连接
        new Thread(() -> {
            try {
                java.net.Socket socket = new java.net.Socket();
                socket.connect(new java.net.InetSocketAddress(serverUrl, serverPort), 3000);
                socket.close();

                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(),
                            "连接成功！", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    if (!isAdded() || getContext() == null) return;
                    progressDialog.dismiss();
                    Toast.makeText(requireContext(),
                            "连接失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        
        // 停止状态更新
        if (statusUpdateHandler != null && statusUpdateRunnable != null) {
            statusUpdateHandler.removeCallbacks(statusUpdateRunnable);
        }
        
        // 解绑服务
        if (serviceBound) {
            try {
                requireContext().unbindService(serviceConnection);
            } catch (Exception ignored) {}
            serviceBound = false;
        }
        
        if (networkScanner != null) {
            networkScanner.stop();
        }
        binding = null;
    }
    
    // 服务器列表适配器
    private static class ServerListAdapter extends RecyclerView.Adapter<ServerListAdapter.ViewHolder> {
        private final List<NetworkScanner.ServerInfo> servers;
        private final OnServerClickListener listener;
        
        interface OnServerClickListener {
            void onServerClick(NetworkScanner.ServerInfo server);
        }
        
        ServerListAdapter(List<NetworkScanner.ServerInfo> servers, OnServerClickListener listener) {
            this.servers = servers;
            this.listener = listener;
        }
        
        @NonNull
        @Override
        public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_server, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
            NetworkScanner.ServerInfo server = servers.get(position);
            holder.tvServerIp.setText(server.ip + ":" + server.port);
            holder.tvServerInfo.setText(server.info);
            holder.itemView.setOnClickListener(v -> listener.onServerClick(server));
        }
        
        @Override
        public int getItemCount() {
            return servers.size();
        }
        
        static class ViewHolder extends RecyclerView.ViewHolder {
            TextView tvServerIp;
            TextView tvServerInfo;
            
            ViewHolder(@NonNull View itemView) {
                super(itemView);
                tvServerIp = itemView.findViewById(R.id.tvServerIp);
                tvServerInfo = itemView.findViewById(R.id.tvServerInfo);
            }
        }
    }
}
