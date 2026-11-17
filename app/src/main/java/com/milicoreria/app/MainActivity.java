package com.milicoreria.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.Intent;
import android.content.ClipData;
import android.net.Uri;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.graphics.Color;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.InputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends Activity {
    private WebView webView;
    private static final int PICK_IMAGE = 1001;
    private static final int TAKE_PHOTO = 1002;

    private Uri cameraUri;
    private File cameraFile;
    private String pendingCallback = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(20, 83, 45));
        window.setNavigationBarColor(Color.rgb(20, 83, 45));
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        );

        webView = new WebView(this);
        webView.setBackgroundColor(Color.rgb(245, 247, 245));

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        webView.addJavascriptInterface(new PhotoBridge(), "AndroidPhotos");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                return servePrivatePhoto(request.getUrl());
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(
                    WebView view, String url) {
                return servePrivatePhoto(Uri.parse(url));
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
        setContentView(webView);

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/index.html");
        }
    }

    private File getPhotoDirectory() {
        File dir = new File(getFilesDir(), "product_images");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private String photoUrl(File file) {
        return "https://licoreria.local/product_images/"
                + Uri.encode(file.getName());
    }

    private WebResourceResponse servePrivatePhoto(Uri uri) {
        if (!"licoreria.local".equals(uri.getHost())) return null;

        String path = uri.getPath();
        if (path == null || !path.startsWith("/product_images/")) {
            return null;
        }

        try {
            String name = path.substring("/product_images/".length());
            File file = new File(getPhotoDirectory(), name);
            String root = getPhotoDirectory().getCanonicalPath();
            if (!file.getCanonicalPath().startsWith(root + File.separator)
                    || !file.isFile()) {
                return null;
            }

            String extension = name.substring(
                name.lastIndexOf('.') + 1
            ).toLowerCase(Locale.ROOT);

            String mime = "image/jpeg";
            if (extension.equals("png")) mime = "image/png";
            if (extension.equals("webp")) mime = "image/webp";
            if (extension.equals("gif")) mime = "image/gif";

            return new WebResourceResponse(
                mime, "UTF-8", new FileInputStream(file)
            );
        } catch (Exception e) {
            return null;
        }
    }

    private class PhotoBridge {
        @JavascriptInterface
        public void chooseGallery(String callback) {
            runOnUiThread(() -> {
                pendingCallback = callback;
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("image/*");
                startActivityForResult(intent, PICK_IMAGE);
            });
        }

        @JavascriptInterface
        public void takePhoto(String callback) {
            runOnUiThread(() -> {
                try {
                    pendingCallback = callback;

                    cameraFile = new File(
                        getPhotoDirectory(),
                        "camera_" + UUID.randomUUID() + ".jpg"
                    );

                    cameraUri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".fileprovider",
                        cameraFile
                    );

                    Intent intent = new Intent(
                        MediaStore.ACTION_IMAGE_CAPTURE
                    );
                    intent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    intent.setClipData(
                        ClipData.newRawUri("photo", cameraUri)
                    );
                    intent.addFlags(
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                        | Intent.FLAG_GRANT_READ_URI_PERMISSION
                    );

                    startActivityForResult(intent, TAKE_PHOTO);
                } catch (Exception e) {
                    sendPhotoResult("");
                }
            });
        }
    }

    private String copyImage(Uri source) {
        if (source == null) return "";

        try {
            String extension = "jpg";
            String mime = getContentResolver().getType(source);

            if ("image/png".equals(mime)) extension = "png";
            if ("image/webp".equals(mime)) extension = "webp";
            if ("image/gif".equals(mime)) extension = "gif";

            String name = "producto_"
                + new SimpleDateFormat(
                    "yyyyMMdd_HHmmss", Locale.US
                  ).format(new Date())
                + "_" + UUID.randomUUID().toString().substring(0, 8)
                + "." + extension;

            File destination = new File(getPhotoDirectory(), name);

            try (InputStream input =
                    getContentResolver().openInputStream(source);
                 FileOutputStream output =
                    new FileOutputStream(destination)) {

                if (input == null) return "";

                byte[] buffer = new byte[8192];
                int count;

                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }

            return photoUrl(destination);

        } catch (IOException e) {
            return "";
        }
    }

    private void sendPhotoResult(String url) {
        if (webView == null || pendingCallback.isEmpty()) return;

        String callback = pendingCallback.replaceAll(
            "[^a-zA-Z0-9_$]", ""
        );

        String safeUrl = url.replace("\\", "\\\\")
                             .replace("'", "\\'");

        webView.post(() -> webView.evaluateJavascript(
            callback + "('" + safeUrl + "')", null
        ));

        pendingCallback = "";
    }

    @Override
    protected void onActivityResult(
            int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == PICK_IMAGE) {
            if (resultCode == RESULT_OK && data != null
                    && data.getData() != null) {
                sendPhotoResult(copyImage(data.getData()));
            } else {
                sendPhotoResult("");
            }
        }

        if (requestCode == TAKE_PHOTO) {
            if (resultCode == RESULT_OK && cameraFile != null
                    && cameraFile.isFile()
                    && cameraFile.length() > 0) {
                sendPhotoResult(photoUrl(cameraFile));
            } else {
                if (cameraFile != null) cameraFile.delete();
                sendPhotoResult("");
            }

            cameraUri = null;
            cameraFile = null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) webView.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
