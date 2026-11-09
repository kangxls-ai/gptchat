package local.gateway.mobile;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK = 10;
    private static final int SAVE = 11;
    private static final int LIMIT = 5 * 1024 * 1024;
    private static final int INK = 0xff171d25;
    private static final int MUTED = 0xff52605e;
    private static final int GREEN = 0xff155d4c;
    private static final int PAPER = 0xfffbfcfa;
    private static final int SURFACE = 0xffedf2ef;
    private static final String[] MODES = {"chat", "image", "docx", "pdf", "xlsx"};
    private static final String[] MODE_NAMES = {"聊天", "生成图片", "Word", "PDF", "Excel"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService files = Executors.newSingleThreadExecutor();
    private final Map<String, JSONObject> conversations = new HashMap<>();
    private final Map<String, JSONObject> messages = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(16 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private SharedPreferences settings;
    private AtomicFile pendingFile;
    private JSONObject pending;
    private JSONObject attached;
    private boolean attachmentBusy;
    private String current;
    private String wantedDownload;
    private File exportFile;
    private boolean foreground;
    private boolean ready;
    private boolean older;
    private int mode;
    private Connection connection;
    private TextView title;
    private TextView status;
    private TextView send;
    private TextView format;
    private TextView attachmentLabel;
    private EditText input;
    private ScrollView scroller;
    private LinearLayout thread;
    private LinearLayout chat;
    private FrameLayout historyOverlay;
    private View historyScrim;
    private LinearLayout historyPanel;
    private EditText searchInput;
    private TextView searchClear;
    private TextView historyHint;
    private ListView historyList;
    private HistoryAdapter historyAdapter;
    private boolean historyOpen;
    private boolean fullHistorySearch;
    private long searchRequest;
    private List<JSONObject> remoteMatches;
    private final Runnable searchDebounce = this::searchOnServer;

    private final Runnable reconnect = () -> {
        if (foreground && connection == null && settings.contains("address") && settings.contains("password")) {
            connect(settings.getString("address", ""), settings.getString("password", ""));
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        settings = getSharedPreferences("gateway", MODE_PRIVATE);
        pendingFile = new AtomicFile(new File(getFilesDir(), "pending-v3.json"));
        try { pending = new JSONObject(new String(pendingFile.readFully(), StandardCharsets.UTF_8)); }
        catch (Exception absent) {}
        current = settings.getString("conversation", UUID.randomUUID().toString());
        if (state != null && state.containsKey("export")) exportFile = new File(state.getString("export"));
        setContentView(build());
        render(false);
    }

    private View build() {
        FrameLayout layers = new FrameLayout(this);
        LinearLayout root = column();
        chat = root;
        root.setBackgroundColor(PAPER);
        layers.setBackgroundColor(PAPER);
        layers.setFitsSystemWindows(true);
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            layers.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets margins = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                view.setPadding(margins.left, margins.top, margins.right, margins.bottom);
                return WindowInsets.CONSUMED;
            });
        }

        LinearLayout top = row();
        top.setPadding(dp(9), dp(5), dp(9), dp(5));
        top.addView(icon("☰", "对话历史", this::showHistory), new LinearLayout.LayoutParams(dp(48), dp(48)));
        LinearLayout identity = column();
        identity.setPadding(dp(5), 0, dp(4), 0);
        title = label("MyAI", 19, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status = label("尚未连接 · 点齿轮设置", 12, MUTED);
        status.setSingleLine(true);
        identity.addView(title);
        identity.addView(status);
        top.addView(identity, new LinearLayout.LayoutParams(0, -2, 1));
        top.addView(icon("＋", "新建对话", this::newChat), new LinearLayout.LayoutParams(dp(48), dp(48)));
        top.addView(icon("⚙", "连接设置", this::settingsDialog), new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        thread = column();
        thread.setPadding(dp(17), dp(10), dp(17), dp(14));
        scroller.addView(thread);
        root.addView(scroller, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = column();
        composer.setPadding(dp(14), dp(6), dp(14), dp(10));
        attachmentLabel = action("", GREEN, this::clearAttachment);
        attachmentLabel.setVisibility(View.GONE);
        composer.addView(attachmentLabel);
        LinearLayout box = column();
        box.setPadding(dp(9), dp(4), dp(9), dp(4));
        box.setBackground(round(SURFACE, 22));
        input = new EditText(this);
        input.setHint("发消息给 MyAI");
        input.setTextColor(INK);
        input.setHintTextColor(MUTED);
        input.setTextSize(16);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setMaxLines(5);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(4000)});
        input.setBackgroundColor(Color.TRANSPARENT);
        input.setPadding(dp(10), dp(9), dp(10), dp(3));
        box.addView(input, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout actions = row();
        actions.addView(icon("＋", "添加图片或文件", this::pick), new LinearLayout.LayoutParams(dp(48), dp(48)));
        format = action("聊天 ▾", GREEN, this::formats);
        actions.addView(format, new LinearLayout.LayoutParams(-2, dp(48)));
        actions.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        send = icon("↑", "发送", this::submit);
        send.setTextColor(Color.WHITE);
        send.setBackground(round(GREEN, 24));
        actions.addView(send, new LinearLayout.LayoutParams(dp(46), dp(46)));
        box.addView(actions);
        composer.addView(box, new LinearLayout.LayoutParams(-1, -2));
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        refreshSend();
        layers.addView(root, new FrameLayout.LayoutParams(-1, -1));
        buildHistory(layers);
        return layers;
    }

    @Override protected void onStart() {
        super.onStart();
        foreground = true;
        main.post(reconnect);
    }

    @Override protected void onStop() {
        foreground = false;
        main.removeCallbacks(reconnect);
        main.removeCallbacks(searchDebounce);
        disconnect();
        super.onStop();
    }

    @Override protected void onDestroy() {
        files.shutdown();
        super.onDestroy();
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (exportFile != null) state.putString("export", exportFile.getAbsolutePath());
    }

    @Override public void onBackPressed() {
        if (historyOpen) hideHistory();
        else super.onBackPressed();
    }

    private void buildHistory(FrameLayout layers) {
        historyOverlay = new FrameLayout(this);
        historyOverlay.setVisibility(View.GONE);
        layers.addView(historyOverlay, new FrameLayout.LayoutParams(-1, -1));

        historyScrim = new View(this);
        historyScrim.setBackgroundColor(0x570b261f);
        historyScrim.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        historyScrim.setOnClickListener(view -> hideHistory());
        historyOverlay.addView(historyScrim, new FrameLayout.LayoutParams(-1, -1));

        historyPanel = column();
        historyPanel.setBackgroundColor(PAPER);
        historyPanel.setElevation(dp(12));
        historyPanel.setClickable(true);
        historyPanel.setFocusableInTouchMode(true);
        int width = Math.min(dp(392), Math.round(getResources().getDisplayMetrics().widthPixels * 0.86f));
        historyOverlay.addView(historyPanel, new FrameLayout.LayoutParams(width, -1, Gravity.LEFT));

        LinearLayout head = row();
        head.setPadding(dp(17), dp(10), dp(9), dp(6));
        ImageView mark = new ImageView(this);
        mark.setImageResource(getApplicationInfo().icon);
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        head.addView(mark, new LinearLayout.LayoutParams(dp(36), dp(36)));
        TextView brand = label("MyAI", 23, INK);
        brand.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.setPadding(dp(9), 0, 0, 0);
        head.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        head.addView(icon("×", "关闭历史", this::hideHistory), new LinearLayout.LayoutParams(dp(48), dp(48)));
        historyPanel.addView(head);

        TextView start = action("＋   新建对话", GREEN, () -> { hideHistory(); newChat(); });
        start.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        start.setBackground(round(0xffe2eee7, 14));
        LinearLayout.LayoutParams startFrame = new LinearLayout.LayoutParams(-1, dp(46));
        startFrame.setMargins(dp(16), dp(7), dp(16), dp(13));
        historyPanel.addView(start, startFrame);

        LinearLayout searchBox = row();
        searchBox.setBackground(round(SURFACE, 14));
        TextView searchMark = label("⌕", 22, MUTED);
        searchMark.setGravity(Gravity.CENTER);
        searchMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        searchBox.addView(searchMark, new LinearLayout.LayoutParams(dp(35), dp(46)));
        searchInput = new EditText(this);
        searchInput.setSingleLine(true);
        searchInput.setTextSize(15);
        searchInput.setTextColor(INK);
        searchInput.setHintTextColor(MUTED);
        searchInput.setHint("搜索历史对话");
        searchInput.setContentDescription("搜索历史对话关键词");
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        searchInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(80)});
        searchInput.setBackgroundColor(Color.TRANSPARENT);
        searchInput.setPadding(0, 0, 0, 0);
        searchBox.addView(searchInput, new LinearLayout.LayoutParams(0, dp(46), 1));
        searchClear = icon("×", "清除搜索", () -> searchInput.setText(""));
        searchClear.setVisibility(View.INVISIBLE);
        searchBox.addView(searchClear, new LinearLayout.LayoutParams(dp(44), dp(46)));
        LinearLayout.LayoutParams searchFrame = new LinearLayout.LayoutParams(-1, -2);
        searchFrame.setMargins(dp(16), 0, dp(16), dp(10));
        historyPanel.addView(searchBox, searchFrame);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                refreshSearch(true);
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        searchInput.setOnEditorActionListener((view, action, key) -> {
            if (action != android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) return false;
            main.removeCallbacks(searchDebounce);
            searchOnServer();
            hideKeyboard(searchInput);
            return true;
        });

        historyHint = label("历史对话", 12, MUTED);
        historyHint.setPadding(dp(17), dp(6), dp(17), dp(6));
        historyPanel.addView(historyHint);
        historyList = new ListView(this);
        historyList.setPadding(dp(16), 0, dp(16), dp(10));
        historyList.setClipToPadding(false);
        historyList.setDivider(new android.graphics.drawable.ColorDrawable(0x24365a4b));
        historyList.setDividerHeight(1);
        historyAdapter = new HistoryAdapter();
        historyList.setAdapter(historyAdapter);
        historyList.setOnItemClickListener((list, row, position, id) -> {
            String conversation = historyAdapter.getItem(position).optString("id");
            hideHistory();
            open(conversation);
        });
        historyPanel.addView(historyList, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void showHistory() {
        if (historyOpen) return;
        historyOpen = true;
        hideKeyboard(input);
        chat.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        historyOverlay.setVisibility(View.VISIBLE);
        historyPanel.animate().cancel();
        historyScrim.animate().cancel();
        historyPanel.setTranslationX(-historyPanel.getLayoutParams().width);
        historyScrim.setAlpha(0);
        historyPanel.animate().translationX(0).setDuration(235).start();
        historyScrim.animate().alpha(1).setDuration(205).start();
        historyPanel.requestFocus();
        searchInput.setText("");
        refreshSearch(true);
        historyList.setSelection(0);
    }

    private void hideHistory() {
        if (!historyOpen) return;
        historyOpen = false;
        main.removeCallbacks(searchDebounce);
        searchRequest++;
        hideKeyboard(searchInput);
        historyPanel.animate().cancel();
        historyScrim.animate().cancel();
        historyPanel.animate().translationX(-historyPanel.getLayoutParams().width).setDuration(185).withEndAction(() -> {
            if (!historyOpen) {
                historyOverlay.setVisibility(View.GONE);
                chat.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            }
        }).start();
        historyScrim.animate().alpha(0).setDuration(185).start();
    }

    private void hideKeyboard(View view) {
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
        view.clearFocus();
    }

    private void refreshSearch(boolean newQuery) {
        if (!historyOpen || historyAdapter == null) return;
        String query = searchInput.getText().toString().trim();
        if (newQuery) {
            searchRequest++;
            remoteMatches = null;
            searchClear.setVisibility(query.isEmpty() ? View.INVISIBLE : View.VISIBLE);
            main.removeCallbacks(searchDebounce);
            if (!query.isEmpty() && ready && fullHistorySearch) main.postDelayed(searchDebounce, 260);
        }
        List<JSONObject> entries;
        if (!query.isEmpty() && remoteMatches != null) {
            entries = remoteMatches;
        } else {
            String[] words = normalize(query).split("\\s+");
            entries = new ArrayList<>();
            for (JSONObject item : conversations.values()) {
                String title = normalize(item.optString("title"));
                boolean match = true;
                for (String word : words) if (!title.contains(word)) match = false;
                if (match) entries.add(item);
            }
            entries.sort(Comparator.comparingLong((JSONObject item) -> item.optLong("order")).reversed());
        }
        historyAdapter.items = entries;
        historyAdapter.notifyDataSetChanged();
        if (newQuery) historyList.setSelection(0);
        if (query.isEmpty()) {
            historyHint.setText(entries.isEmpty() ? "暂无历史对话" : "历史对话");
        } else if (ready && fullHistorySearch && remoteMatches == null) {
            historyHint.setText("正在搜索对话内容…");
        } else {
            String qualifier = ready && fullHistorySearch ? "" : "（当前仅搜索标题）";
            historyHint.setText((entries.isEmpty() ? "未找到匹配对话" : entries.size() + " 个匹配对话") + qualifier);
        }
    }

    private void searchOnServer() {
        if (!historyOpen || !ready || !fullHistorySearch) return;
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) return;
        try {
            send(object("search").put("query", query).put("requestId", ++searchRequest));
        } catch (JSONException ignored) {}
    }

    private static String normalize(String value) {
        return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
                .trim().toLowerCase(java.util.Locale.ROOT);
    }

    private final class HistoryAdapter extends BaseAdapter {
        private List<JSONObject> items = new ArrayList<>();
        @Override public int getCount() { return items.size(); }
        @Override public JSONObject getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View reusable, android.view.ViewGroup parent) {
            TextView text = reusable instanceof TextView ? (TextView) reusable : label("", 15, INK);
            JSONObject item = getItem(position);
            boolean selected = current.equals(item.optString("id"));
            text.setText(item.optString("title", "对话"));
            text.setTextColor(selected ? GREEN : INK);
            text.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            text.setSingleLine(true);
            text.setEllipsize(TextUtils.TruncateAt.END);
            text.setGravity(Gravity.CENTER_VERTICAL);
            text.setPadding(dp(7), 0, dp(7), 0);
            text.setMinHeight(dp(48));
            text.setBackgroundColor(selected ? 0xffe8f0eb : Color.TRANSPARENT);
            return text;
        }
    }

    private void newChat() { open(UUID.randomUUID().toString()); }

    private void open(String id) {
        if (!current.equals(id)) {
            current = id;
            messages.clear();
            older = false;
            settings.edit().putString("conversation", current).apply();
            input.setText("");
            clearAttachment();
            setMode(0);
        }
        render(false);
        requestHistory(0);
    }

    private void formats() {
        new AlertDialog.Builder(this).setTitle("回复格式").setSingleChoiceItems(MODE_NAMES, mode, (dialog, which) -> {
            setMode(which);
            dialog.dismiss();
        }).setNegativeButton("取消", null).show();
    }

    private void setMode(int value) {
        mode = value;
        format.setText(MODE_NAMES[value] + " ▾");
    }

    private void settingsDialog() {
        LinearLayout fields = column();
        fields.setPadding(dp(22), dp(10), dp(22), dp(4));
        EditText address = new EditText(this);
        address.setHint("服务器 IP 或域名:端口");
        address.setSingleLine(true);
        address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        address.setText(settings.getString("address", ""));
        fields.addView(address);
        EditText password = new EditText(this);
        password.setHint("配对密码");
        password.setSingleLine(true);
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        password.setText(settings.getString("password", ""));
        fields.addView(password);
        TextView hint = label("连接成功后自动保存，之后打开 App 会自动重连。", 13, MUTED);
        hint.setPadding(0, dp(8), 0, 0);
        fields.addView(hint);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("连接设置").setView(fields)
                .setNegativeButton("取消", null).setPositiveButton("保存并连接", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (connect(address.getText().toString(), password.getText().toString())) dialog.dismiss();
        }));
        dialog.show();
    }

    private boolean connect(String endpoint, String value) {
        String password = value.trim();
        String[] parts = password.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("[0-9a-fA-F]{64}")
                || !parts[1].matches("[A-Za-z0-9_-]{43}")) {
            notifyUser("请粘贴完整的配对密码。");
            return false;
        }
        try {
            String authority = endpoint.trim();
            URI uri = URI.create(authority.startsWith("wss://") ? authority : "wss://" + authority);
            if (!"wss".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() < 1
                    || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null
                    || uri.getFragment() != null || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) {
                throw new IllegalArgumentException("Invalid address");
            }
            main.removeCallbacks(reconnect);
            disconnect();
            Connection next = new Connection(uri, parts[0], parts[1], password);
            connection = next;
            status.setText("正在建立加密连接…");
            next.connect();
            main.postDelayed(() -> {
                if (connection == next && !ready) {
                    disconnect();
                    status.setText("连接超时 · 点齿轮检查设置");
                    retry(next);
                }
            }, 12000);
            return true;
        } catch (Exception invalid) {
            notifyUser("请填写有效的服务器地址，例如 example.net:4000。");
            return false;
        }
    }

    private void disconnect() {
        Connection previous = connection;
        connection = null;
        ready = false;
        fullHistorySearch = false;
        loading.clear();
        wantedDownload = null;
        refreshSend();
        refreshSearch(true);
        if (previous != null) previous.close();
    }

    private void retry(Connection source) {
        if (foreground && source.getURI().getAuthority().equals(settings.getString("address", ""))
                && source.password.equals(settings.getString("password", ""))) {
            main.postDelayed(reconnect, 5000);
        }
    }

    private void submit() {
        if (!ready || pending != null || attachmentBusy) return;
        String prompt = input.getText().toString().trim();
        if (prompt.isEmpty() && attached == null) return;
        try {
            JSONObject packet = object("submit").put("id", UUID.randomUUID().toString())
                    .put("conversation", current).put("mode", MODES[mode]).put("prompt", prompt);
            if (attached != null) packet.put("attachment", attached);
            writePending(packet);
            pending = packet;
            input.setText("");
            clearAttachment();
            setMode(0);
            sendPending();
            refreshSend();
        } catch (Exception error) {
            notifyUser("无法保存待发送的指令。");
        }
    }

    private void writePending(JSONObject packet) throws IOException {
        FileOutputStream stream = pendingFile.startWrite();
        try {
            stream.write(packet.toString().getBytes(StandardCharsets.UTF_8));
            pendingFile.finishWrite(stream);
        } catch (IOException error) {
            pendingFile.failWrite(stream);
            throw error;
        }
    }

    private void sendPending() {
        if (pending != null) send(pending);
    }

    private void clearPending(String id, boolean restore) {
        if (pending == null || !(id.equals(pending.optString("id")) || restore && id.isEmpty())) return;
        if (restore && current.equals(pending.optString("conversation"))) {
            if (input.getText().length() == 0) input.setText(pending.optString("prompt"));
            attached = pending.optJSONObject("attachment");
            displayAttachment();
        }
        pendingFile.delete();
        pending = null;
        refreshSend();
    }

    private void requestHistory(long before) {
        if (!ready) return;
        try {
            JSONObject packet = object("history").put("conversation", current);
            if (before > 0) packet.put("before", before);
            send(packet);
        } catch (JSONException ignored) {}
    }

    private void receive(Connection source, String message) {
        if (connection != source) return;
        try {
            JSONObject event = new JSONObject(message);
            switch (event.getString("type")) {
                case "ready":
                    String previous = settings.getString("password", "");
                    if (!previous.isEmpty() && !previous.equals(source.password)) {
                        clearPending("", true);
                        conversations.clear();
                        messages.clear();
                        images.evictAll();
                        current = UUID.randomUUID().toString();
                        older = false;
                    }
                    settings.edit().putString("address", source.getURI().getAuthority())
                            .putString("password", source.password).putString("conversation", current).apply();
                    ready = true;
                    fullHistorySearch = event.optBoolean("historySearch");
                    status.setText("● 已连接");
                    requestHistory(0);
                    sendPending();
                    refreshSend();
                    refreshSearch(true);
                    render(false);
                    break;
                case "conversations":
                    JSONArray entries = event.getJSONArray("items");
                    for (int i = 0; i < entries.length(); i++) {
                        JSONObject item = entries.getJSONObject(i);
                        conversations.put(item.getString("id"), item);
                    }
                    updateTitle();
                    refreshSearch(false);
                    break;
                case "search":
                    if (!historyOpen || event.optLong("requestId") != searchRequest) break;
                    JSONArray matches = event.getJSONArray("items");
                    remoteMatches = new ArrayList<>();
                    for (int i = 0; i < matches.length(); i++) remoteMatches.add(matches.getJSONObject(i));
                    refreshSearch(false);
                    break;
                case "history":
                    if (!current.equals(event.getString("conversation"))) break;
                    JSONArray items = event.getJSONArray("items");
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject task = items.getJSONObject(i);
                        messages.put(task.getString("id"), task);
                    }
                    older = event.optBoolean("more");
                    render(event.optLong("before") == 0);
                    break;
                case "task":
                    JSONObject task = event.getJSONObject("task");
                    clearPending(task.getString("id"), false);
                    observe(task);
                    break;
                case "attachment":
                    receiveAttachment(event);
                    break;
                case "error":
                    String id = event.optString("id");
                    if (!id.isEmpty() || wantedDownload == null && loading.isEmpty()) clearPending(id, true);
                    wantedDownload = null;
                    loading.clear();
                    notifyUser(event.optString("message", "服务返回错误。"));
                    break;
                default:
                    break;
            }
        } catch (JSONException invalid) {
            notifyUser("服务返回了无法读取的数据。");
        }
    }

    private void observe(JSONObject task) throws JSONException {
        String id = task.optString("conversation");
        if (id.isEmpty()) {
            status.setText("服务端需要升级到 V0.3");
            return;
        }
        JSONObject summary = conversations.get(id);
        if (summary == null) {
            String heading = task.optString("prompt").replaceAll("\\s+", " ").trim();
            if (heading.isEmpty() && task.optJSONObject("input") != null) {
                heading = task.optJSONObject("input").optString("name");
            }
            if (heading.length() > 35) heading = heading.substring(0, 35) + "…";
            summary = new JSONObject().put("id", id).put("title", heading.isEmpty() ? "对话" : heading);
            conversations.put(id, summary);
        }
        summary.put("order", Math.max(summary.optLong("order"), task.optLong("order")));
        refreshSearch(true);
        if (current.equals(id)) {
            messages.put(task.getString("id"), task);
            render(true);
            JSONObject output = task.optJSONObject("output");
            String taskId = task.getString("id");
            if (output != null && output.optString("mime").startsWith("image/")
                    && images.get(taskId + "-output") == null) {
                requestAttachment(taskId, "output", false);
            }
        }
    }

    private void render(boolean bottom) {
        updateTitle();
        thread.removeAllViews();
        if (messages.isEmpty()) {
            TextView eyebrow = label("PERSONAL AI", 12, GREEN);
            eyebrow.setLetterSpacing(0.16f);
            eyebrow.setPadding(dp(6), dp(30), 0, dp(10));
            thread.addView(eyebrow);
            TextView welcome = label("今天想做什么？", 31, INK);
            welcome.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            welcome.setPadding(dp(6), 0, 0, dp(8));
            thread.addView(welcome);
            TextView help = label("发一段文字、选一张图片，或添加一份文件。\n需要生成文件时，在输入框下方选择回复格式。", 15, MUTED);
            help.setPadding(dp(6), 0, dp(8), 0);
            help.setLineSpacing(dp(4), 1);
            thread.addView(help);
            return;
        }
        List<JSONObject> list = new ArrayList<>(messages.values());
        list.sort(Comparator.comparingLong(task -> task.optLong("order")));
        if (older) thread.addView(action("加载更早的消息", GREEN, () -> requestHistory(list.get(0).optLong("order"))));
        for (JSONObject task : list) {
            bubble(task.optString("prompt"), task, "input", true);
            String state = task.optString("state");
            String answer = task.optString("result");
            if ("queued".equals(state)) answer = "已接收，等待执行…";
            else if ("running".equals(state)) answer = "正在处理…";
            else if ("failed".equals(state)) answer = "执行失败\n" + answer;
            bubble(answer, task, "output", false);
        }
        if (bottom) scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
    }

    private void updateTitle() {
        JSONObject item = conversations.get(current);
        title.setText(item == null ? "MyAI" : item.optString("title", "MyAI"));
    }

    private void bubble(String text, JSONObject task, String side, boolean user) {
        JSONObject attachment = task.optJSONObject(side);
        if (text.isEmpty() && attachment == null) return;
        LinearLayout group = column();
        group.setPadding(dp(13), dp(9), dp(13), dp(9));
        if (user) group.setBackground(round(0xffe3eee7, 18));
        LinearLayout.LayoutParams frame = new LinearLayout.LayoutParams(-2, -2);
        frame.gravity = user ? Gravity.END : Gravity.START;
        frame.setMargins(user ? dp(34) : 0, dp(4), user ? 0 : dp(18), dp(8));
        thread.addView(group, frame);
        if (!user) {
            TextView who = label("MyAI", 12, GREEN);
            who.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            group.addView(who);
        }
        if (!text.isEmpty()) {
            TextView body = label(text, 16, INK);
            body.setTextIsSelectable(true);
            body.setLineSpacing(dp(3), 1);
            group.addView(body);
        }
        if (attachment == null) return;
        String id = task.optString("id");
        String key = id + "-" + side;
        String mime = attachment.optString("mime");
        Bitmap image = mime.startsWith("image/") ? images.get(key) : null;
        if (image != null) {
            ImageView preview = new ImageView(this);
            preview.setAdjustViewBounds(true);
            preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
            preview.setImageBitmap(image);
            preview.setContentDescription(attachment.optString("name", "图片"));
            int width = Math.min(dp(240), getResources().getDisplayMetrics().widthPixels - dp(118));
            group.addView(preview, new LinearLayout.LayoutParams(Math.max(dp(100), width), -2));
            preview.setOnClickListener(view -> showImage(image));
        }
        String name = attachment.optString("name", "附件");
        group.addView(action((mime.startsWith("image/") ? "图片 · " : "文件 · ") + name + "  ↓", GREEN,
                () -> requestAttachment(id, side, true)));
        if (mime.startsWith("image/") && image == null) {
            // Lazy load on tap so long chat histories do not queue dozens of large downloads.
            TextView preview = action("查看图片", GREEN, () -> requestAttachment(id, side, false));
            group.addView(preview);
        }
    }

    private void showImage(Bitmap image) {
        ImageView full = new ImageView(this);
        full.setAdjustViewBounds(true);
        full.setMaxHeight(getResources().getDisplayMetrics().heightPixels * 2 / 3);
        full.setScaleType(ImageView.ScaleType.FIT_CENTER);
        full.setImageBitmap(image);
        new AlertDialog.Builder(this).setView(full).setPositiveButton("关闭", null).show();
    }

    private void requestAttachment(String id, String side, boolean download) {
        if (!ready) { notifyUser("连接后可以查看附件。"); return; }
        String key = id + "-" + side;
        if (download) wantedDownload = key;
        if (!loading.add(key)) return;
        try { send(object("attachment").put("id", id).put("side", side)); }
        catch (JSONException invalid) { loading.remove(key); }
    }

    private void receiveAttachment(JSONObject event) throws JSONException {
        String key = UUID.fromString(event.getString("id")).toString() + "-" + event.getString("side");
        if (!loading.remove(key)) return;
        boolean download = key.equals(wantedDownload);
        if (download) wantedDownload = null;
        JSONObject info = event.getJSONObject("file");
        String data = event.getString("data");
        String mime = info.optString("mime", "application/octet-stream");
        String filename = info.optString("name", "附件");
        files.execute(() -> {
            try {
                if (data.length() > 6_990_508) throw new IOException("File is too large");
                byte[] bytes = Base64.decode(data, Base64.DEFAULT);
                if (bytes.length > LIMIT) throw new IOException("File is too large");
                Bitmap image = mime.startsWith("image/") ? BitmapFactory.decodeByteArray(bytes, 0, bytes.length) : null;
                File file = download ? File.createTempFile("gateway-download-", ".bin", getCacheDir()) : null;
                if (file != null) java.nio.file.Files.write(file.toPath(), bytes);
                main.post(() -> {
                    if (isDestroyed()) return;
                    if (image != null) {
                        images.put(key, image);
                        render(false);
                    }
                    if (file != null) {
                        if (foreground) export(file, filename, mime);
                        else file.delete();
                    }
                });
            } catch (Exception error) {
                main.post(() -> notifyUser("无法读取服务端附件。"));
            }
        });
    }

    private void pick() {
        if (attachmentBusy) return;
        new AlertDialog.Builder(this).setTitle("添加附件").setItems(new String[]{"图片", "文件"}, (dialog, index) -> {
            Intent select = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
            select.setType(index == 0 ? "image/*" : "*/*");
            startActivityForResult(select, PICK);
        }).setNegativeButton("取消", null).show();
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == SAVE) {
            File file = exportFile;
            exportFile = null;
            if (file == null) return;
            if (result != RESULT_OK || data == null || data.getData() == null) { file.delete(); return; }
            Uri target = data.getData();
            files.execute(() -> {
                try (var input = new java.io.FileInputStream(file);
                     var output = getContentResolver().openOutputStream(target, "w")) {
                    if (output == null) throw new IOException("Missing output");
                    byte[] chunk = new byte[8192];
                    int count;
                    while ((count = input.read(chunk)) >= 0) output.write(chunk, 0, count);
                    output.flush();
                    main.post(() -> notifyUser("文件已保存。"));
                } catch (Exception error) {
                    main.post(() -> notifyUser("保存失败，请重试。"));
                } finally { file.delete(); }
            });
        } else if (request == PICK && result == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            String conversation = current;
            attachmentBusy = true;
            refreshSend();
            attachmentLabel.setText("正在准备附件…");
            attachmentLabel.setVisibility(View.VISIBLE);
            files.execute(() -> {
                JSONObject item = null;
                String failure = "";
                try { item = encode(uri); }
                catch (Exception error) { failure = error.getMessage(); }
                JSONObject loaded = item;
                String problem = failure;
                main.post(() -> {
                    attachmentBusy = false;
                    if (isDestroyed()) return;
                    if (loaded != null && conversation.equals(current)) attached = loaded;
                    if (loaded == null) notifyUser(problem == null || problem.isEmpty() ? "无法打开附件。" : problem);
                    displayAttachment();
                    refreshSend();
                });
            });
        }
    }

    private JSONObject encode(Uri uri) throws Exception {
        String mime = getContentResolver().getType(uri);
        String filename = "附件";
        try (var cursor = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) filename = cursor.getString(0);
        }
        if (mime != null && mime.startsWith("video/") || filename.matches("(?i).*\\.(mp4|mov|webm|mkv|avi|m4v|3gp)$")) {
            throw new IOException("这一版支持图片和文件。");
        }
        byte[] bytes;
        if (mime != null && mime.startsWith("image/")
                || filename.matches("(?i).*\\.(jpg|jpeg|png|webp|heic|heif|gif|bmp)$")) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            try (var stream = getContentResolver().openInputStream(uri)) { BitmapFactory.decodeStream(stream, null, options); }
            options.inJustDecodeBounds = false;
            options.inSampleSize = 1;
            while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > 1800) options.inSampleSize *= 2;
            Bitmap bitmap;
            try (var stream = getContentResolver().openInputStream(uri)) { bitmap = BitmapFactory.decodeStream(stream, null, options); }
            if (bitmap == null) throw new IOException("无法读取这张图片。");
            try {
                var encoded = new ByteArrayOutputStream();
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 87, encoded)) throw new IOException("无法压缩图片。");
                bytes = encoded.toByteArray();
            } finally { bitmap.recycle(); }
            int dot = filename.lastIndexOf('.');
            filename = (dot > 0 ? filename.substring(0, dot) : filename) + ".jpg";
        } else {
            try (var stream = getContentResolver().openInputStream(uri); var output = new ByteArrayOutputStream()) {
                if (stream == null) throw new IOException("无法打开附件。");
                byte[] chunk = new byte[8192];
                int count;
                while ((count = stream.read(chunk)) >= 0) {
                    if (output.size() + count > LIMIT) throw new IOException("每个附件最大 5 MB。");
                    output.write(chunk, 0, count);
                }
                bytes = output.toByteArray();
            }
        }
        if (bytes.length == 0 || bytes.length > LIMIT) throw new IOException("附件为空或超过 5 MB。");
        if (filename.length() > 100) {
            int dot = filename.lastIndexOf('.');
            filename = filename.substring(0, 80) + (dot >= 80 ? filename.substring(dot) : "");
        }
        return new JSONObject().put("name", filename).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    private void export(File file, String name, String mime) {
        if (exportFile != null) exportFile.delete();
        exportFile = file;
        Intent target = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
        target.setType(mime);
        target.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(target, SAVE);
    }

    private void clearAttachment() { attached = null; displayAttachment(); }
    private void displayAttachment() {
        attachmentLabel.setVisibility(attached == null ? View.GONE : View.VISIBLE);
        if (attached != null) attachmentLabel.setText("附件 · " + attached.optString("name") + "  ×");
    }

    private void refreshSend() {
        if (send == null) return;
        boolean enabled = ready && pending == null && !attachmentBusy;
        send.setEnabled(enabled);
        send.setAlpha(enabled ? 1f : 0.42f);
    }

    private static JSONObject object(String type) throws JSONException { return new JSONObject().put("type", type); }

    private void send(JSONObject packet) {
        Connection target = connection;
        if (target == null || !target.isOpen()) return;
        try { target.send(packet.toString()); }
        catch (RuntimeException disconnected) { target.close(); }
    }

    private void notifyUser(String text) { if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }
    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        return layout;
    }
    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }
    private TextView action(String text, int color, Runnable click) {
        TextView view = label(text, 14, color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setMinHeight(dp(44));
        view.setPadding(dp(10), 0, dp(10), 0);
        view.setOnClickListener(ignored -> click.run());
        return view;
    }
    private TextView icon(String text, String description, Runnable click) {
        TextView view = action(text, INK, click);
        view.setTextSize(23);
        view.setGravity(Gravity.CENTER);
        view.setPadding(0, 0, 0, 0);
        view.setContentDescription(description);
        return view;
    }
    private GradientDrawable round(int color, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        return shape;
    }

    private final class Connection extends WebSocketClient {
        private final String token;
        private final String password;

        Connection(URI uri, String fingerprint, String token, String password) throws Exception {
            super(uri);
            this.token = token;
            this.password = password;
            byte[] expected = new byte[32];
            for (int index = 0; index < expected.length; index++) {
                expected[index] = (byte) Integer.parseInt(fingerprint.substring(index * 2, index * 2 + 2), 16);
            }
            X509TrustManager pin = new X509TrustManager() {
                @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException { throw new CertificateException("Unexpected client certificate"); }
                @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    try {
                        if (chain == null || chain.length == 0 || !MessageDigest.isEqual(expected,
                                MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded()))) {
                            throw new CertificateException("Gateway fingerprint mismatch");
                        }
                        chain[0].checkValidity();
                    } catch (java.security.GeneralSecurityException failure) {
                        throw new CertificateException("Untrusted gateway", failure);
                    }
                }
            };
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(null, new TrustManager[]{pin}, null);
            setSocketFactory(tls.getSocketFactory());
        }

        @Override protected void onSetSSLParameters(SSLParameters parameters) {
            parameters.setEndpointIdentificationAlgorithm(null);
        }
        @Override public void onOpen(ServerHandshake handshake) {
            try { send(object("auth").put("token", token).put("version", 3).toString()); }
            catch (JSONException error) { close(); }
        }
        @Override public void onMessage(String message) { main.post(() -> receive(this, message)); }
        @Override public void onError(Exception error) {}
        @Override public void onClose(int code, String reason, boolean remote) {
            main.post(() -> {
                if (connection != this) return;
                disconnect();
                if (code == 1008) status.setText("配对失败 · 点齿轮检查密码");
                else {
                    status.setText("已断开 · 正在等待重连");
                    retry(this);
                }
            });
        }
    }
}
