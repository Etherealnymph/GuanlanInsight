package com.etherealnymph.guanlan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Typeface;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

/**
 * A focused Android 14 WebView shell for the 观澜 / Insight Space sites.
 *
 * <p>It deliberately uses the framework only: no AndroidX, no third-party
 * runtime dependencies, no JavaScript bridge. The two entry points are a
 * floating bottom bar, while back/forward/reload and a live progress line live
 * in the top chrome.</p>
 */
public class MainActivity extends Activity {

    private static final String INSIGHT_URL =
            "https://etherealnymph.github.io/web-projects/insight-space/index.html";
    private static final String PORTAL_URL =
            "https://etherealnymph.github.io/web-projects/";
    private static final String ALLOWED_HOST = "etherealnymph.github.io";

    private static final int SECTION_UNKNOWN = -1;
    private static final int SECTION_INSIGHT = 0;
    private static final int SECTION_PORTAL = 1;

    private static final int REQUEST_FILE_CHOOSER = 0x51A1;
    private static final String STATE_LAST_URL = "last_url";
    private static final String TAG = "Guanlan";
    private static final String PREFS_UI = "ui";
    private static final String PREF_FAB_RIGHT = "fab_right";
    private static final String PREF_FAB_Y = "fab_y";

    private FrameLayout root;
    private WebView webView;
    private View topBar;
    private View bottomBar;
    private View loadingOverlay;
    private ScrollView errorOverlay;
    private ProgressBar pageProgress;
    private OrbitLoadingView orbitView;

    private ImageButton backButton;
    private ImageButton reloadButton;
    private ImageButton searchButton;

    private TextView brandSubtitle;
    private TextView loadingTitle;
    private TextView loadingPercent;
    private TextView errorTitle;
    private TextView errorMessage;
    private TextView errorTarget;
    private TextView errorRetry;
    private TextView errorPortal;

    private ImageView switchIcon;
    private TextView switchLabel;

    private View searchBar;
    private EditText searchInput;
    private TextView searchGo;
    private ImageButton searchClose;
    private boolean searchVisible;
    private boolean browsingExternal;

    private boolean fabOnRight = true;
    private float fabYFraction = 1f;
    private boolean fabDragging;
    private boolean fabLayoutPosted;
    private float fabDownRawX;
    private float fabDownRawY;
    private int fabStartX;
    private int fabStartY;
    private int fabCurrentX;
    private int fabCurrentY;
    private int fabTouchSlop;

    private ValueCallback<Uri[]> filePathCallback;
    private View customView;
    private FrameLayout customViewContainer;
    private WebChromeClient.CustomViewCallback customViewCallback;

    private boolean destroyed;
    private boolean rendererGone;

    private int insetTop;
    private int insetBottom;
    private int insetLeft;
    private int insetRight;
    private int imeBottom;
    private boolean imeVisible;

    private boolean pageLoading;
    private boolean hasContent;
    private boolean errorVisible;
    private long loadingShownAt;
    private String pendingUrl = INSIGHT_URL;
    private String lastFailedUrl;
    private int currentSection = SECTION_INSIGHT;

    private final Runnable hideLoadingRunnable = () -> {
        if (pageLoading || errorVisible || destroyed || loadingOverlay == null) {
            return;
        }
        loadingOverlay.animate().cancel();
        loadingOverlay.animate()
                .alpha(0f)
                .setDuration(220L)
                .withEndAction(() -> {
                    if (loadingOverlay == null || pageLoading || errorVisible) {
                        return;
                    }
                    loadingOverlay.setVisibility(View.GONE);
                    if (orbitView != null) {
                        orbitView.stop();
                    }
                })
                .start();
    };

    private final Runnable hideProgressRunnable = () -> {
        if (!pageLoading && !destroyed && pageProgress != null) {
            pageProgress.animate().alpha(0f).setDuration(220L).start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            configureWindow();

            String previousCrash = CrashLog.readCurrent(this);
            if (previousCrash != null) {
                showPreviousCrash(previousCrash);
                return;
            }

            try {
                setContentView(R.layout.activity_main);
            } catch (Throwable error) {
                showStartupFailure(error);
                return;
            }

            // Must run after setContentView: some OEM implementations (notably
            // OPPO/ColorOS) return a null DecorView through PhoneWindow before
            // content is attached, which makes getInsetsController() throw.
            applyEdgeToEdge();

            bindViews();
            loadFabPosition();
            try {
                setupWebView();
                setupChrome();
                setupInsets();
            } catch (Throwable error) {
                showStartupFailure(error);
                return;
            }

            loadInitialPage(savedInstanceState);
        } catch (Throwable error) {
            showStartupFailure(error);
        }
    }

    private void loadInitialPage(Bundle savedInstanceState) {
        String restoredUrl = savedInstanceState == null
                ? null
                : savedInstanceState.getString(STATE_LAST_URL);

        if (savedInstanceState != null) {
            WebBackForwardList restored = webView.restoreState(savedInstanceState);
            if (restored != null) {
                hasContent = true;
                currentSection = sectionForUrl(restoredUrl);
                pendingUrl = restoredUrl == null ? INSIGHT_URL : restoredUrl;
                updateTabStyles();
                showLoadingOverlay(currentSection, false);
                return;
            }
        }

        if (restoredUrl != null) {
            currentSection = sectionForUrl(restoredUrl);
            pendingUrl = restoredUrl;
            showLoadingOverlay(currentSection, false);
            updateTabStyles();
            webView.loadUrl(restoredUrl);
        } else {
            currentSection = SECTION_INSIGHT;
            pendingUrl = INSIGHT_URL;
            updateTabStyles();
            showLoadingOverlay(SECTION_INSIGHT, false);
            webView.loadUrl(INSIGHT_URL);
        }
    }

    private void showPreviousCrash(String crash) {
        copyToClipboard(crash);
        String preview = crash;
        if (preview.length() > 1600) {
            preview = preview.substring(0, 1600) + "\n…";
        }
        new AlertDialog.Builder(this)
                .setTitle("上次启动未完成")
                .setMessage("崩溃信息已复制到剪贴板，可直接粘贴发送。\n\n" + preview)
                .setPositiveButton("继续打开", (dialog, which) -> {
                    CrashLog.clear(this);
                    recreate();
                })
                .setNegativeButton("退出", (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish())
                .show();
    }

    private void showStartupFailure(Throwable error) {
        if (error != null) {
            CrashLog.write(this, error, "startup");
        }
        try {
            String log = CrashLog.read(this);
            if (TextUtils.isEmpty(log)) {
                log = error == null ? "未知错误" : Log.getStackTraceString(error);
            }
            final String finalLog = log;
            copyToClipboard(finalLog);

            LinearLayout container = new LinearLayout(this);
            container.setOrientation(LinearLayout.VERTICAL);
            container.setBackgroundColor(getColor(R.color.bg));
            container.setPadding(dp(20), dp(44), dp(20), dp(24));

            TextView title = new TextView(this);
            title.setText("启动没有完成");
            title.setTextColor(getColor(R.color.text_primary));
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            container.addView(title);

            TextView hint = new TextView(this);
            hint.setText("错误信息已复制到剪贴板。请截图或粘贴反馈，方便定位问题。");
            hint.setTextColor(getColor(R.color.text_secondary));
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            hint.setPadding(0, dp(10), 0, dp(14));
            container.addView(hint);

            TextView body = new TextView(this);
            body.setText(finalLog);
            body.setTextColor(getColor(R.color.text_secondary));
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextIsSelectable(true);
            body.setPadding(dp(10), dp(10), dp(10), dp(10));

            ScrollView scroll = new ScrollView(this);
            scroll.setBackgroundColor(getColor(R.color.surface));
            scroll.addView(body, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            container.addView(scroll, scrollParams);

            Button copy = new Button(this);
            copy.setText("再次复制错误信息");
            copy.setOnClickListener(view -> copyToClipboard(finalLog));
            container.addView(copy, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            Button close = new Button(this);
            close.setText("关闭应用");
            close.setOnClickListener(view -> finish());
            container.addView(close, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            setContentView(container);
        } catch (Throwable fallbackError) {
            Log.e(TAG, "Could not show startup failure screen", fallbackError);
            finish();
        }
    }

    private void copyToClipboard(String text) {
        if (TextUtils.isEmpty(text)) {
            return;
        }
        try {
            ClipboardManager manager =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (manager != null) {
                manager.setPrimaryClip(ClipData.newPlainText("Guanlan crash log", text));
                Toast.makeText(this, "错误信息已复制", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable error) {
            Log.w(TAG, "Could not copy crash log", error);
        }
    }

    private void configureWindow() {
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setNavigationBarDividerColor(Color.TRANSPARENT);
        }
    }

    private void applyEdgeToEdge() {
        try {
            Window window = getWindow();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = window.getInsetsController();
                if (controller != null) {
                    // Clear the LIGHT_* appearance flags so both bars use light
                    // icons over the deep navy chrome.
                    controller.setSystemBarsAppearance(
                            0,
                            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                    | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
                    controller.setSystemBarsBehavior(
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                }
            } else {
                window.getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
        } catch (Throwable error) {
            // Edge-to-edge is cosmetic; never let an OEM Window implementation
            // take down the whole app at launch.
            Log.w(TAG, "Could not apply edge-to-edge window flags", error);
        }
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        webView = findViewById(R.id.web_view);
        topBar = findViewById(R.id.top_bar);
        bottomBar = findViewById(R.id.bottom_bar);
        loadingOverlay = findViewById(R.id.loading_overlay);
        errorOverlay = findViewById(R.id.error_overlay);
        pageProgress = findViewById(R.id.page_progress);
        orbitView = findViewById(R.id.orbit_view);

        backButton = findViewById(R.id.btn_back);
        reloadButton = findViewById(R.id.btn_reload);
        searchButton = findViewById(R.id.btn_search);

        brandSubtitle = findViewById(R.id.brand_subtitle);
        loadingTitle = findViewById(R.id.loading_title);
        loadingPercent = findViewById(R.id.loading_percent);

        errorTitle = findViewById(R.id.error_title);
        errorMessage = findViewById(R.id.error_message);
        errorTarget = findViewById(R.id.error_target);
        errorRetry = findViewById(R.id.error_retry);
        errorPortal = findViewById(R.id.error_portal);

        switchIcon = findViewById(R.id.switch_icon);
        switchLabel = findViewById(R.id.switch_label);

        searchBar = findViewById(R.id.search_bar);
        searchInput = findViewById(R.id.search_input);
        searchGo = findViewById(R.id.search_go);
        searchClose = findViewById(R.id.search_close);
    }

    private void setupWebView() {
        webView.setBackgroundColor(getColor(R.color.web_bg));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setLongClickable(true);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setGeolocationEnabled(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setLoadsImagesAutomatically(true);
        settings.setBlockNetworkImage(false);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setDefaultTextEncodingName("UTF-8");
        settings.setSaveFormData(false);
        settings.setSafeBrowsingEnabled(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, false);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppChromeClient());
        webView.setDownloadListener(new AppDownloadListener());
    }

    private void setupChrome() {
        backButton.setOnClickListener(view -> {
            haptic(view);
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
        });
        reloadButton.setOnClickListener(view -> {
            haptic(view);
            if (webView != null) {
                webView.reload();
            }
        });

        View brandBlock = findViewById(R.id.brand_block);
        brandBlock.setOnClickListener(view -> {
            if (webView != null) {
                webView.scrollTo(0, 0);
            }
        });

        bottomBar.setOnClickListener(view -> {
            haptic(view);
            openSection(shouldOfferPortal() ? SECTION_PORTAL : SECTION_INSIGHT);
        });

        searchButton.setOnClickListener(view -> showSearchBar());
        searchClose.setOnClickListener(view -> hideSearchBar());
        searchGo.setOnClickListener(view -> submitSearch());
        searchInput.setOnEditorActionListener((view, actionId, event) -> {
            boolean enter = event != null
                    && event.getAction() == android.view.KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || enter) {
                submitSearch();
                return true;
            }
            return false;
        });

        errorRetry.setOnClickListener(view -> {
            haptic(view);
            retryLastPage();
        });
        errorPortal.setOnClickListener(view -> {
            haptic(view);
            openSection(SECTION_PORTAL);
        });

        setupFabDrag();
        updateTabStyles();
        updateNavButtons();
        pageProgress.setAlpha(0f);
    }

    private void loadFabPosition() {
        SharedPreferences preferences = getSharedPreferences(PREFS_UI, MODE_PRIVATE);
        fabOnRight = preferences.getBoolean(PREF_FAB_RIGHT, true);
        fabYFraction = preferences.getFloat(PREF_FAB_Y, 1f);
        if (fabYFraction < 0f) {
            fabYFraction = 0f;
        } else if (fabYFraction > 1f) {
            fabYFraction = 1f;
        }
    }

    private void saveFabPosition() {
        getSharedPreferences(PREFS_UI, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_FAB_RIGHT, fabOnRight)
                .putFloat(PREF_FAB_Y, fabYFraction)
                .apply();
    }

    private void showSearchBar() {
        if (searchVisible || searchBar == null) {
            return;
        }
        searchVisible = true;
        applyChromeLayout();
        searchBar.animate().cancel();
        searchBar.setVisibility(View.VISIBLE);
        searchBar.setAlpha(0f);
        searchBar.animate().alpha(1f).setDuration(140L).start();

        String current = webView == null ? null : webView.getUrl();
        searchInput.setText(current == null ? "" : current);
        if (searchInput.length() > 0) {
            searchInput.setSelection(0, searchInput.length());
        }
        searchInput.requestFocus();
        searchBar.postDelayed(() -> {
            if (!searchVisible || destroyed) {
                return;
            }
            InputMethodManager manager =
                    (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (manager != null) {
                manager.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
            }
        }, 120L);
    }

    private void hideSearchBar() {
        if (!searchVisible) {
            return;
        }
        searchVisible = false;
        hideKeyboard();
        searchInput.clearFocus();
        searchBar.animate().cancel();
        searchBar.animate()
                .alpha(0f)
                .setDuration(120L)
                .withEndAction(() -> {
                    if (!searchVisible && searchBar != null) {
                        searchBar.setVisibility(View.GONE);
                    }
                })
                .start();
        applyChromeLayout();
    }

    private void submitSearch() {
        String text = searchInput.getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            showToast(getString(R.string.search_empty));
            return;
        }
        hideSearchBar();
        loadFromAddress(text);
    }

    private void loadFromAddress(String text) {
        if (webView == null) {
            return;
        }
        String raw = text.trim();
        if (raw.startsWith("/") || raw.startsWith("web-projects/")) {
            raw = "https://" + ALLOWED_HOST + "/" + raw.replaceFirst("^/+", "");
        } else if (!looksLikeUrl(raw)) {
            raw = "https://cn.bing.com/search?q=" + Uri.encode(raw);
        } else if (!raw.contains("://")) {
            raw = "https://" + raw;
        }

        Uri uri;
        try {
            uri = Uri.parse(raw);
        } catch (Throwable error) {
            showToast(getString(R.string.search_invalid));
            return;
        }
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) && isInternalHost(uri)) {
            uri = uri.buildUpon().scheme("https").build();
            raw = uri.toString();
            scheme = "https";
        }
        if ("http".equalsIgnoreCase(scheme)) {
            // Cleartext is disabled in this app; let the system browser handle it.
            launchView(uri);
            return;
        }
        if (!"https".equalsIgnoreCase(scheme) || TextUtils.isEmpty(uri.getHost())) {
            showToast(getString(R.string.search_invalid));
            return;
        }

        boolean internal = isInternalHost(uri);
        browsingExternal = !internal;
        int section = sectionForUrl(raw);
        currentSection = section;
        pendingUrl = raw;
        updateTabStyles();
        showLoadingOverlay(section, true);
        webView.loadUrl(raw);
    }

    private boolean looksLikeUrl(String text) {
        if (TextUtils.isEmpty(text)) {
            return false;
        }
        if (text.startsWith("/")) {
            return true;
        }
        String lower = text.toLowerCase(Locale.US);
        if (lower.startsWith("http://")
                || lower.startsWith("https://")
                || lower.startsWith("www.")) {
            return true;
        }
        if (text.contains(" ")) {
            return false;
        }
        if (lower.startsWith("localhost")
                || lower.matches("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d+)?(?:/.*)?$")) {
            return true;
        }
        return text.contains(".");
    }

    private boolean isInternalHost(Uri uri) {
        String host = uri.getHost();
        return host != null
                && (ALLOWED_HOST.equalsIgnoreCase(host)
                || ("www." + ALLOWED_HOST).equalsIgnoreCase(host));
    }

    private boolean shouldOfferPortal() {
        if (currentSection != SECTION_PORTAL) {
            return true;
        }
        return webView == null || !isSamePage(webView.getUrl(), PORTAL_URL);
    }

    private void setupInsets() {
        root.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = windowInsets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                Insets ime = windowInsets.getInsets(WindowInsets.Type.ime());
                insetTop = bars.top;
                insetBottom = bars.bottom;
                insetLeft = bars.left;
                insetRight = bars.right;
                imeBottom = ime.bottom;
                imeVisible = windowInsets.isVisible(WindowInsets.Type.ime());
            } else {
                insetTop = windowInsets.getSystemWindowInsetTop();
                insetBottom = windowInsets.getSystemWindowInsetBottom();
                insetLeft = windowInsets.getSystemWindowInsetLeft();
                insetRight = windowInsets.getSystemWindowInsetRight();
                imeBottom = 0;
                imeVisible = false;
            }
            applyChromeLayout();
            return windowInsets;
        });
        root.requestApplyInsets();
    }

    private void applyChromeLayout() {
        if (root == null || webView == null) {
            return;
        }

        if (customView != null) {
            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            searchBar.setVisibility(View.GONE);
            return;
        }

        int screenWidth = getResources().getDisplayMetrics().widthPixels;

        int topSide = insetLeft + dp(12);
        int topWidth = Math.min(
                screenWidth - topSide - insetRight - dp(12),
                dp(256));
        FrameLayout.LayoutParams topParams = (FrameLayout.LayoutParams) topBar.getLayoutParams();
        topParams.width = Math.max(dp(210), topWidth);
        topParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        topParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        topParams.setMargins(topSide, insetTop + dp(6), 0, 0);
        topBar.setLayoutParams(topParams);
        topBar.setVisibility(searchVisible ? View.GONE : View.VISIBLE);

        FrameLayout.LayoutParams searchParams =
                (FrameLayout.LayoutParams) searchBar.getLayoutParams();
        searchParams.width = Math.min(
                screenWidth - insetLeft - insetRight - dp(24),
                dp(520));
        searchParams.height = dp(50);
        searchParams.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        searchParams.setMargins(topSide, insetTop + dp(6), 0, 0);
        searchBar.setLayoutParams(searchParams);

        bottomBar.setVisibility(
                imeVisible || errorVisible || searchVisible ? View.GONE : View.VISIBLE);
        placeFab();

        int topHeight = topBar.getHeight() > 0 ? topBar.getHeight() : dp(50);
        int contentTop = insetTop + dp(6) + topHeight + dp(6);
        int contentBottom = imeVisible
                ? imeBottom + dp(8)
                : insetBottom + dp(8);

        // Margins, not padding: fixed/sticky CSS headers inside the page still
        // honour the reduced WebView viewport, so the site header cannot slide
        // underneath the floating top bar.
        FrameLayout.LayoutParams webParams =
                (FrameLayout.LayoutParams) webView.getLayoutParams();
        webParams.setMargins(insetLeft, contentTop, insetRight, contentBottom);
        webView.setLayoutParams(webParams);
        webView.setPadding(0, 0, 0, 0);

        loadingOverlay.setPadding(
                insetLeft + dp(28),
                insetTop + dp(30),
                insetRight + dp(28),
                insetBottom + dp(30));

        errorOverlay.setPadding(
                insetLeft + dp(8),
                insetTop + dp(76),
                insetRight + dp(8),
                insetBottom + dp(88));
    }

    private int[] fabBounds() {
        int width = root.getWidth() > 0
                ? root.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        int height = root.getHeight() > 0
                ? root.getHeight()
                : getResources().getDisplayMetrics().heightPixels;
        int size = dp(60);
        int minX = insetLeft + dp(10);
        int maxX = Math.max(minX, width - insetRight - dp(10) - size);
        int minY = insetTop + dp(66);
        int maxY = Math.max(minY, height - insetBottom - dp(10) - size);
        return new int[]{minX, maxX, minY, maxY, size};
    }

    private void placeFab() {
        if (root == null || bottomBar == null || fabDragging || destroyed) {
            return;
        }
        if (root.getWidth() <= 0 || root.getHeight() <= 0) {
            if (!fabLayoutPosted) {
                fabLayoutPosted = true;
                root.post(() -> {
                    fabLayoutPosted = false;
                    placeFab();
                });
            }
            return;
        }
        int[] bounds = fabBounds();
        int minX = bounds[0];
        int maxX = bounds[1];
        int minY = bounds[2];
        int maxY = bounds[3];
        int size = bounds[4];

        int x = fabOnRight ? maxX : minX;
        int y = Math.round(minY + (maxY - minY) * fabYFraction);
        if (y < minY) {
            y = minY;
        } else if (y > maxY) {
            y = maxY;
        }
        fabCurrentX = x;
        fabCurrentY = y;

        FrameLayout.LayoutParams params =
                (FrameLayout.LayoutParams) bottomBar.getLayoutParams();
        params.width = size;
        params.height = size;
        params.gravity = android.view.Gravity.TOP | android.view.Gravity.START;
        params.setMargins(x, y, 0, 0);
        bottomBar.setLayoutParams(params);
    }

    private void setupFabDrag() {
        fabTouchSlop = ViewConfiguration.get(this).getScaledTouchSlop();
        bottomBar.setOnTouchListener((view, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    fabDragging = false;
                    fabStartX = view.getLeft();
                    fabStartY = view.getTop();
                    fabCurrentX = fabStartX;
                    fabCurrentY = fabStartY;
                    fabDownRawX = event.getRawX();
                    fabDownRawY = event.getRawY();
                    view.setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getRawX() - fabDownRawX;
                    float dy = event.getRawY() - fabDownRawY;
                    if (!fabDragging
                            && Math.hypot(dx, dy) > fabTouchSlop) {
                        fabDragging = true;
                        view.setPressed(false);
                    }
                    if (fabDragging) {
                        int[] bounds = fabBounds();
                        int x = clamp(fabStartX + Math.round(dx), bounds[0], bounds[1]);
                        int y = clamp(fabStartY + Math.round(dy), bounds[2], bounds[3]);
                        fabCurrentX = x;
                        fabCurrentY = y;
                        FrameLayout.LayoutParams params =
                                (FrameLayout.LayoutParams) view.getLayoutParams();
                        params.leftMargin = x;
                        params.topMargin = y;
                        view.setLayoutParams(params);
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    view.setPressed(false);
                    if (fabDragging) {
                        int[] bounds = fabBounds();
                        int centerLine = (bounds[0] + bounds[1] + bounds[4]) / 2;
                        fabOnRight = (fabCurrentX + bounds[4] / 2) >= centerLine;
                        fabYFraction = bounds[3] == bounds[2]
                                ? 1f
                                : (fabCurrentY - bounds[2])
                                / (float) (bounds[3] - bounds[2]);
                        saveFabPosition();
                        fabDragging = false;
                        placeFab();
                    } else {
                        fabDragging = false;
                        view.performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    fabDragging = false;
                    view.setPressed(false);
                    placeFab();
                    return true;
                default:
                    return false;
            }
        });
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void handleBack() {
        if (searchVisible) {
            hideSearchBar();
            return;
        }
        if (customView != null) {
            exitFullscreen();
            return;
        }
        if (errorVisible) {
            hideErrorImmediately();
            if (webView != null && webView.canGoBack()) {
                webView.goBack();
            } else {
                finish();
            }
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            finish();
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        handleBack();
    }

    private void openSection(int section) {
        if (webView == null) {
            return;
        }

        if (searchVisible) {
            hideSearchBar();
        }
        browsingExternal = false;
        String target = section == SECTION_INSIGHT ? INSIGHT_URL : PORTAL_URL;

        if (!errorVisible && isSamePage(webView.getUrl(), target)) {
            webView.scrollTo(0, 0);
            return;
        }

        hideKeyboard();
        currentSection = section;
        pendingUrl = target;
        updateTabStyles();
        showLoadingOverlay(section, true);
        webView.loadUrl(target);
    }

    private void retryLastPage() {
        String target = lastFailedUrl;
        if (TextUtils.isEmpty(target)) {
            target = currentSection == SECTION_PORTAL ? PORTAL_URL : INSIGHT_URL;
        }
        int section = sectionForUrl(target);
        if (section == SECTION_UNKNOWN) {
            section = currentSection;
        }
        currentSection = section;
        pendingUrl = target;
        updateTabStyles();
        showLoadingOverlay(section, true);
        webView.loadUrl(target);
    }

    private void showLoadingOverlay(int section, boolean animate) {
        if (loadingOverlay == null) {
            return;
        }
        hideErrorImmediately();
        loadingTitle.setText(loadingTitleFor(section));
        loadingPercent.setText(getString(R.string.loading_percent, 0));
        loadingOverlay.removeCallbacks(hideLoadingRunnable);
        loadingOverlay.animate().cancel();
        loadingOverlay.setVisibility(View.VISIBLE);
        if (animate) {
            loadingOverlay.setAlpha(0f);
            loadingOverlay.animate().alpha(1f).setDuration(160L).start();
        } else {
            loadingOverlay.setAlpha(1f);
        }
        loadingShownAt = SystemClock.uptimeMillis();
        if (orbitView != null) {
            orbitView.start();
        }
    }

    private int loadingTitleFor(int section) {
        if (section == SECTION_PORTAL) {
            return R.string.loading_portal;
        }
        if (section == SECTION_INSIGHT) {
            return R.string.loading_insight;
        }
        return R.string.loading_unknown;
    }

    private void hideLoadingOverlaySoon() {
        if (loadingOverlay == null) {
            return;
        }
        loadingOverlay.removeCallbacks(hideLoadingRunnable);
        long elapsed = SystemClock.uptimeMillis() - loadingShownAt;
        long delay = Math.max(0L, 420L - elapsed);
        loadingOverlay.postDelayed(hideLoadingRunnable, delay);
    }

    private void hideLoadingOverlayNow() {
        if (loadingOverlay == null) {
            return;
        }
        loadingOverlay.removeCallbacks(hideLoadingRunnable);
        loadingOverlay.animate().cancel();
        loadingOverlay.setVisibility(View.GONE);
        if (orbitView != null) {
            orbitView.stop();
        }
    }

    private void showError(String title, String message, String url, int code) {
        if (destroyed || errorOverlay == null) {
            return;
        }
        pageLoading = false;
        errorVisible = true;
        lastFailedUrl = url;
        if (searchVisible) {
            hideSearchBar();
        }
        bottomBar.setVisibility(View.GONE);

        hideLoadingOverlayNow();
        pageProgress.animate().cancel();
        pageProgress.setAlpha(0f);

        errorTitle.setText(title);
        errorMessage.setText(message);
        String host = "—";
        if (!TextUtils.isEmpty(url)) {
            Uri uri = Uri.parse(url);
            host = uri.getHost() == null ? url : uri.getHost();
        }
        String detail = getString(R.string.error_target, host);
        if (code != 0) {
            detail += "   ·   " + getString(R.string.error_code, code);
        }
        errorTarget.setText(detail);

        errorOverlay.scrollTo(0, 0);
        errorOverlay.setVisibility(View.VISIBLE);
        errorOverlay.setAlpha(0f);
        errorOverlay.animate().alpha(1f).setDuration(200L).start();
        errorTitle.announceForAccessibility(title);

        updateSectionFromUrl(url);
        updateNavButtons();
    }

    private void hideErrorImmediately() {
        errorVisible = false;
        if (errorOverlay != null) {
            errorOverlay.animate().cancel();
            errorOverlay.setVisibility(View.GONE);
        }
        if (bottomBar != null && !imeVisible && !searchVisible) {
            bottomBar.setVisibility(View.VISIBLE);
        }
    }

    private void updateSectionFromUrl(String url) {
        currentSection = sectionForUrl(url);
        updateTabStyles();
    }

    private int sectionForUrl(String url) {
        if (TextUtils.isEmpty(url)) {
            return SECTION_UNKNOWN;
        }
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (host == null) {
            return SECTION_UNKNOWN;
        }
        boolean internal = ALLOWED_HOST.equalsIgnoreCase(host)
                || ("www." + ALLOWED_HOST).equalsIgnoreCase(host);
        if (!internal) {
            return SECTION_UNKNOWN;
        }
        if (path.startsWith("/web-projects/insight-space")) {
            return SECTION_INSIGHT;
        }
        if (path.equals("/web-projects") || path.startsWith("/web-projects/")) {
            return SECTION_PORTAL;
        }
        return SECTION_UNKNOWN;
    }

    private void updateTabStyles() {
        if (switchIcon == null) {
            return;
        }

        boolean toPortal = shouldOfferPortal();
        switchIcon.setImageResource(toPortal ? R.drawable.ic_grid : R.drawable.ic_mark);
        switchLabel.setText(toPortal
                ? R.string.switch_portal_label
                : R.string.switch_insight_label);
        bottomBar.setContentDescription(getString(
                toPortal ? R.string.tab_portal_desc : R.string.tab_insight_desc));
        switchIcon.setColorFilter(getColor(R.color.accent));
        switchLabel.setTextColor(getColor(R.color.accent));

        if (currentSection == SECTION_INSIGHT) {
            brandSubtitle.setText(R.string.brand_insight);
        } else if (currentSection == SECTION_PORTAL) {
            brandSubtitle.setText(R.string.brand_portal);
        } else {
            brandSubtitle.setText(R.string.brand_unknown);
        }
    }

    private void updateNavButtons() {
        if (webView == null || backButton == null) {
            return;
        }
        boolean canGoBack = webView.canGoBack();
        backButton.setEnabled(canGoBack);
        backButton.setAlpha(canGoBack ? 1f : 0.28f);
    }

    private void updateNavButtonsSoon() {
        if (webView != null) {
            webView.post(this::updateNavButtons);
        }
    }

    private boolean isSamePage(String current, String target) {
        if (TextUtils.isEmpty(current) || TextUtils.isEmpty(target)) {
            return false;
        }
        return normalizedPage(current).equals(normalizedPage(target));
    }

    private String normalizedPage(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.US);
        String path = uri.getPath() == null ? "/" : uri.getPath();
        if (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return host + path;
    }

    private boolean matchesPendingUrl(String url) {
        return TextUtils.isEmpty(pendingUrl) || isSamePage(url, pendingUrl);
    }

    private boolean handleUri(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        String lower = scheme.toLowerCase(Locale.US);

        if ("http".equals(lower) || "https".equals(lower)) {
            if (isInternalHost(uri)) {
                if ("http".equals(lower)) {
                    Uri secure = uri.buildUpon().scheme("https").build();
                    if (webView != null) {
                        webView.loadUrl(secure.toString());
                    }
                    browsingExternal = false;
                    return true;
                }
                browsingExternal = false;
                return false;
            }
            if ("http".equals(lower)) {
                return launchView(uri);
            }
            if (browsingExternal) {
                return false;
            }
            return launchView(uri);
        }

        if ("mailto".equals(lower) || "tel".equals(lower) || "sms".equals(lower)
                || "geo".equals(lower) || "market".equals(lower)) {
            return launchView(uri);
        }

        if ("intent".equals(lower)) {
            try {
                Intent intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
                startActivity(intent);
                return true;
            } catch (Exception exception) {
                showToast(getString(R.string.unsupported_link));
                return true;
            }
        }

        if ("javascript".equals(lower) || "data".equals(lower) || "file".equals(lower)) {
            showToast(getString(R.string.unsupported_link));
            return true;
        }

        return launchView(uri);
    }

    private boolean launchView(Uri uri) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, uri);
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            startActivity(intent);
            showToast(getString(R.string.external_opened));
        } catch (ActivityNotFoundException exception) {
            showToast(getString(R.string.no_browser));
        } catch (Exception exception) {
            showToast(getString(R.string.unsupported_link));
        }
        return true;
    }

    private boolean hasNetwork() {
        ConnectivityManager manager =
                (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (manager == null) {
            return true;
        }
        Network network = manager.getActiveNetwork();
        if (network == null) {
            return false;
        }
        NetworkCapabilities capabilities = manager.getNetworkCapabilities(network);
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager manager =
                (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (manager != null) {
            manager.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    private void haptic(View view) {
        if (view != null) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (webView != null) {
            webView.onPause();
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (root != null) {
            root.requestApplyInsets();
            applyChromeLayout();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_LAST_URL, pendingUrl);
        if (!rendererGone && webView != null) {
            try {
                webView.saveState(outState);
            } catch (Exception exception) {
                Log.w(TAG, "Unable to save WebView state", exception);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE_CHOOSER) {
            return;
        }
        if (filePathCallback == null) {
            return;
        }
        Uri[] results = WebChromeClient.FileChooserParams.parseResult(resultCode, data);
        filePathCallback.onReceiveValue(results);
        filePathCallback = null;
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
        }
        if (customViewContainer != null && root != null) {
            customViewContainer.removeAllViews();
            root.removeView(customViewContainer);
            customViewContainer = null;
            customView = null;
            customViewCallback = null;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (webView != null) {
            webView.stopLoading();
            webView.removeAllViews();
            if (root != null) {
                root.removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private final class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (request == null || !request.isForMainFrame()) {
                return false;
            }
            return handleUri(request.getUrl());
        }

        @SuppressWarnings("deprecation")
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleUri(Uri.parse(url));
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (destroyed || url == null) {
                return;
            }
            pageLoading = true;
            pendingUrl = url;
            if (searchVisible) {
                hideSearchBar();
            }
            if (isInternalHost(Uri.parse(url))) {
                browsingExternal = false;
            }
            hideErrorImmediately();
            updateSectionFromUrl(url);
            updateNavButtonsSoon();

            pageProgress.removeCallbacks(hideProgressRunnable);
            pageProgress.animate().cancel();
            pageProgress.setAlpha(1f);
            pageProgress.setProgress(3, false);

            if (loadingOverlay.getVisibility() != View.VISIBLE && !hasContent) {
                showLoadingOverlay(currentSection, true);
            }
            if (loadingPercent != null) {
                loadingPercent.setText(getString(R.string.loading_percent, 3));
            }

            reloadButton.animate().cancel();
            reloadButton.setRotation(0f);
            reloadButton.animate().rotation(360f).setDuration(520L).start();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            if (destroyed) {
                return;
            }
            pageLoading = false;
            hasContent = true;
            if (url != null) {
                pendingUrl = url;
                updateSectionFromUrl(url);
            }
            updateNavButtons();
            pageProgress.setProgress(100, true);
            pageProgress.removeCallbacks(hideProgressRunnable);
            pageProgress.postDelayed(hideProgressRunnable, 280L);

            if (!errorVisible) {
                hideLoadingOverlaySoon();
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            updateNavButtons();
        }

        @Override
        public void onReceivedError(
                WebView view,
                WebResourceRequest request,
                WebResourceError error) {
            if (request == null || !request.isForMainFrame() || error == null) {
                return;
            }
            String url = request.getUrl().toString();
            if (url.startsWith("about:")) {
                return;
            }
            if (!matchesPendingUrl(url)) {
                return;
            }
            int code = error.getErrorCode();
            String title;
            String message;
            if (!hasNetwork()) {
                title = getString(R.string.error_title_offline);
                message = getString(R.string.error_offline);
            } else if (code == WebViewClient.ERROR_TIMEOUT) {
                title = getString(R.string.error_title);
                message = getString(R.string.error_timeout);
            } else if (code == WebViewClient.ERROR_HOST_LOOKUP) {
                title = getString(R.string.error_title);
                message = getString(R.string.error_host);
            } else {
                title = getString(R.string.error_title);
                message = getString(R.string.error_network);
            }
            showError(title, message, url, code);
        }

        @Override
        public void onReceivedHttpError(
                WebView view,
                WebResourceRequest request,
                WebResourceResponse errorResponse) {
            if (request == null || !request.isForMainFrame() || errorResponse == null) {
                return;
            }
            int status = errorResponse.getStatusCode();
            if (status < 400) {
                return;
            }
            if (!matchesPendingUrl(request.getUrl().toString())) {
                return;
            }
            String title;
            if (status == 404) {
                title = getString(R.string.error_title_not_found);
            } else if (status >= 500) {
                title = getString(R.string.error_title_server);
            } else {
                title = getString(R.string.error_title);
            }
            String message = getString(R.string.error_http, status);
            showError(title, message, request.getUrl().toString(), status);
        }

        @Override
        public void onReceivedSslError(
                WebView view,
                SslErrorHandler handler,
                SslError error) {
            if (handler != null) {
                handler.cancel();
            }
            String url = error != null && error.getUrl() != null
                    ? error.getUrl()
                    : pendingUrl;
            showError(
                    getString(R.string.error_title_ssl),
                    getString(R.string.error_ssl),
                    url,
                    WebViewClient.ERROR_FAILED_SSL_HANDSHAKE);
        }

        @Override
        public boolean onRenderProcessGone(
                WebView view,
                RenderProcessGoneDetail detail) {
            rendererGone = true;
            boolean crashed = detail != null && detail.didCrash();
            Log.w(TAG, "WebView renderer gone, crashed=" + crashed);
            showToast(getString(R.string.renderer_restart));
            root.postDelayed(() -> {
                if (!isFinishing() && !destroyed) {
                    recreate();
                }
            }, 360L);
            return true;
        }
    }

    private final class AppChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            if (destroyed) {
                return;
            }
            pageProgress.setAlpha(1f);
            pageProgress.setProgress(newProgress, true);
            loadingPercent.setText(getString(R.string.loading_percent, newProgress));
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            // The brand line deliberately stays stable; the page title is visible
            // inside the web content itself.
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {
            if (MainActivity.this.filePathCallback != null) {
                MainActivity.this.filePathCallback.onReceiveValue(null);
            }
            MainActivity.this.filePathCallback = filePathCallback;

            Intent intent;
            try {
                intent = fileChooserParams.createIntent();
            } catch (Exception exception) {
                MainActivity.this.filePathCallback = null;
                showToast(getString(R.string.unsupported_link));
                return false;
            }
            if (intent == null) {
                MainActivity.this.filePathCallback = null;
                showToast(getString(R.string.unsupported_link));
                return false;
            }
            if (fileChooserParams.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE) {
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            }
            try {
                startActivityForResult(
                        Intent.createChooser(intent, getString(R.string.choose_file)),
                        REQUEST_FILE_CHOOSER);
            } catch (ActivityNotFoundException exception) {
                MainActivity.this.filePathCallback = null;
                showToast(getString(R.string.no_browser));
                return false;
            }
            return true;
        }

        @Override
        public boolean onJsAlert(
                WebView view,
                String url,
                String message,
                final android.webkit.JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.app_name_full)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> result.confirm())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsConfirm(
                WebView view,
                String url,
                String message,
                final android.webkit.JsResult result) {
            new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.app_name_full)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok,
                            (dialog, which) -> result.confirm())
                    .setNegativeButton(android.R.string.cancel,
                            (dialog, which) -> result.cancel())
                    .setOnCancelListener(dialog -> result.cancel())
                    .show();
            return true;
        }

        @Override
        public boolean onJsPrompt(
                WebView view,
                String url,
                String message,
                String defaultValue,
                final android.webkit.JsPromptResult result) {
            int padding = dp(20);
            FrameLayout container = new FrameLayout(MainActivity.this);
            container.setPadding(padding, dp(4), padding, 0);
            EditText input = new EditText(MainActivity.this);
            input.setSingleLine(false);
            input.setMinLines(1);
            input.setMaxLines(4);
            input.setHint(R.string.dialog_prompt_hint);
            input.setText(defaultValue == null ? "" : defaultValue);
            input.setSelection(input.getText().length());
            container.addView(input, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            AlertDialog dialog = new AlertDialog.Builder(MainActivity.this)
                    .setTitle(R.string.app_name_full)
                    .setMessage(message)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok,
                            (d, which) -> result.confirm(input.getText().toString()))
                    .setNegativeButton(android.R.string.cancel,
                            (d, which) -> result.cancel())
                    .setOnCancelListener(d -> result.cancel())
                    .create();
            dialog.show();
            input.requestFocus();
            if (dialog.getWindow() != null) {
                dialog.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
            }
            return true;
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            if (request != null) {
                request.deny();
            }
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                if (callback != null) {
                    callback.onCustomViewHidden();
                }
                return;
            }
            if (view.getParent() instanceof ViewGroup) {
                ((ViewGroup) view.getParent()).removeView(view);
            }
            if (searchVisible) {
                hideSearchBar();
            }
            customView = view;
            customViewCallback = callback;
            customViewContainer = new FrameLayout(MainActivity.this);
            customViewContainer.setBackgroundColor(Color.BLACK);
            customViewContainer.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            root.addView(customViewContainer, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            topBar.setVisibility(View.GONE);
            bottomBar.setVisibility(View.GONE);
            loadingOverlay.setVisibility(View.GONE);
            if (orbitView != null) {
                orbitView.stop();
            }
            hideErrorImmediately();
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            enterImmersive();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) {
                return;
            }
            customViewCallback = null;
            cleanupCustomView();
        }
    }

    private final class AppDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimeType,
                long contentLength) {
            if (TextUtils.isEmpty(url)) {
                return;
            }
            if (url.startsWith("blob:") || url.startsWith("data:")) {
                showToast(getString(R.string.download_unsupported));
                return;
            }
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                showToast(getString(R.string.download_unsupported));
                return;
            }
            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                if (TextUtils.isEmpty(fileName)) {
                    fileName = "guanlan-download";
                }
                DownloadManager.Request request = new DownloadManager.Request(uri);
                request.setMimeType(mimeType);
                String cookie = CookieManager.getInstance().getCookie(url);
                if (!TextUtils.isEmpty(cookie)) {
                    request.addRequestHeader("Cookie", cookie);
                }
                String agent = webView == null
                        ? userAgent
                        : webView.getSettings().getUserAgentString();
                if (!TextUtils.isEmpty(agent)) {
                    request.addRequestHeader("User-Agent", agent);
                }
                request.setTitle(fileName);
                request.setDescription(getString(R.string.app_name_full));
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(true);
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(
                        Environment.DIRECTORY_DOWNLOADS, fileName);

                DownloadManager manager =
                        (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
                if (manager == null) {
                    showToast(getString(R.string.download_failed));
                    return;
                }
                manager.enqueue(request);
                showToast(getString(R.string.download_started, fileName));
            } catch (Exception exception) {
                Log.w(TAG, "Download failed", exception);
                showToast(getString(R.string.download_failed));
            }
        }
    }

    private void enterImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private void exitFullscreen() {
        WebChromeClient.CustomViewCallback callback = customViewCallback;
        customViewCallback = null;
        cleanupCustomView();
        if (callback != null) {
            callback.onCustomViewHidden();
        }
    }

    private void cleanupCustomView() {
        if (customViewContainer != null && root != null) {
            customViewContainer.removeAllViews();
            root.removeView(customViewContainer);
            customViewContainer = null;
        }
        customView = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        showSystemBars();
        if (topBar != null) {
            topBar.setVisibility(searchVisible ? View.GONE : View.VISIBLE);
            bottomBar.setVisibility(
                    imeVisible || errorVisible || searchVisible ? View.GONE : View.VISIBLE);
        }
        if (root != null) {
            root.requestApplyInsets();
        }
    }

    private void showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = getWindow().getInsetsController();
            if (controller != null) {
                controller.show(WindowInsets.Type.systemBars());
                controller.setSystemBarsAppearance(
                        0,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                                | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }
}
