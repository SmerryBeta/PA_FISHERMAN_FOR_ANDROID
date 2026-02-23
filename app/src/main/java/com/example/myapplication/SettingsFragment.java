package com.example.myapplication;

import android.app.ProgressDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.myapplication.databinding.FragmentSettingsBinding;
import com.example.myapplication.utils.NetworkScanner;

import java.util.List;

/**
 * 设置界面 Fragment
 * 用于配置服务器连接参数
 */
public class SettingsFragment extends Fragment {

    private FragmentSettingsBinding binding;
    private NetworkScanner networkScanner;
    private Handler mainHandler;

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

        // 加载已保存的设置
        loadSettings();

        // 设置按钮点击事件
        binding.btnSave.setOnClickListener(v -> saveSettings());
        binding.btnScan.setOnClickListener(v -> startScan());
        binding.btnTest.setOnClickListener(v -> testConnection());
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

    private void startScan() {
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
                    binding.etServerUrl.setText(ip);
                    binding.etServerPort.setText(String.valueOf(port));
                    Toast.makeText(requireContext(),
                            "找到服务器: " + info, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onScanComplete(List<NetworkScanner.ServerInfo> servers) {
                if (!isAdded() || getContext() == null) return;
                requireActivity().runOnUiThread(() -> {
                    if (!isAdded()) return;
                    progressDialog.dismiss();
                    if (servers.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "未找到服务器，请手动输入地址", Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(requireContext(),
                                "扫描完成，找到 " + servers.size() + " 个服务器",
                                Toast.LENGTH_SHORT).show();
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
        if (networkScanner != null) {
            networkScanner.stop();
        }
        binding = null;
    }
}
