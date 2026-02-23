package com.example.myapplication;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.myapplication.databinding.FragmentControlPanelBinding;

import java.util.Locale;

/**
 * 控制面板 Fragment
 * 使用 WebView 嵌入脚本的网页控制界面
 */
public class ControlPanelFragment extends Fragment {

    private FragmentControlPanelBinding binding;
    private String serverUrl;
    private int serverPort;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        binding = FragmentControlPanelBinding.inflate(inflater, container, false);
        return binding.getRoot();
    }

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 获取服务器地址
        loadServerSettings();

        // 配置 WebView
        WebSettings settings = binding.webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        // 禁用缩放控制（包括双击放大）
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(false);
        // 禁用双击缩放
        settings.setUseWideViewPort(true);

        binding.webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                // 允许在 WebView 内加载链接
                return false;
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                binding.progressBar.setVisibility(ProgressBar.VISIBLE);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                binding.progressBar.setVisibility(ProgressBar.GONE);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                super.onReceivedError(view, errorCode, description, failingUrl);
                binding.progressBar.setVisibility(ProgressBar.GONE);
                Toast.makeText(requireContext(), 
                        "加载失败: " + description, Toast.LENGTH_SHORT).show();
            }
        });

        binding.webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                binding.progressBar.setProgress(newProgress);
            }
        });

        // 加载页面
        loadWebPage();
    }

    private void loadServerSettings() {
        serverUrl = SPUtils.getPrefs().getString("server_url", "");
        serverPort = SPUtils.getPrefs().getInt("server_port", 1225);
    }

    private void loadWebPage() {
        if (serverUrl.isEmpty()) {
            // 显示提示信息
            binding.webView.loadData(
                "<html><body style='text-align:center;padding:50px;'>" +
                "<h2>未配置服务器</h2>" +
                "<p>请先在设置中配置服务器地址</p>" +
                "</body></html>",
                "text/html", "UTF-8"
            );
            return;
        }

        String url = String.format(
                Locale.US,
                "http://%s:%d",
                serverUrl,
                serverPort
        );
        binding.webView.loadUrl(url);
    }

    /**
     * 重新加载页面（当服务器设置改变时调用）
     */
    public void reload() {
        loadServerSettings();
        loadWebPage();
    }

    /**
     * 检查 WebView 是否可以返回
     */
    public boolean canGoBack() {
        return binding != null && binding.webView.canGoBack();
    }

    /**
     * 返回上一页
     */
    public void goBack() {
        if (binding != null) {
            binding.webView.goBack();
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (binding != null) {
            binding.webView.stopLoading();
            binding.webView.destroy();
        }
        binding = null;
    }
}
