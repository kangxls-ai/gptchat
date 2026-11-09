package myai.desktop;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

public final class MyAI extends JFrame {
    private static final Color INK = new Color(23, 29, 37);
    private static final Color MUTED = new Color(82, 96, 94);
    private static final Color GREEN = new Color(21, 93, 76);
    private static final Color PAPER = new Color(251, 252, 250);
    private static final Color SURFACE = new Color(237, 242, 239);
    private static final String[] MODE_LABELS = {"聊天", "生成图片", "Word", "PDF", "Excel"};
    private static final String[] MODES = {"chat", "image", "docx", "pdf", "xlsx"};

    private final Preferences preferences = Preferences.userNodeForPackage(MyAI.class);
    private final ExecutorService files = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private final Map<String, Conversation> conversations = new HashMap<>();
    private final Map<String, JsonObject> messages = new HashMap<>();
    private final DefaultListModel<Conversation> sidebar = new DefaultListModel<>();
    private final JList<Conversation> history = new JList<>(sidebar);
    private final JTextField search = new JTextField();
    private final JLabel searchStatus = new JLabel("对话历史");
    private final JLabel heading = new JLabel("MyAI");
    private final JLabel status = new JLabel("尚未连接");
    private final JTextArea composer = new JTextArea(3, 32);
    private final JComboBox<String> format = new JComboBox<>(MODE_LABELS);
    private final JButton send = new JButton("发送");
    private final JButton addFile = new JButton("添加附件");
    private final JButton attachmentLabel = new JButton();
    private final JPanel thread = new JPanel();
    private final JScrollPane threadScroll = new JScrollPane(thread);
    private final Timer searchTimer = new Timer(260, event -> searchServer());
    private final Timer retry = new Timer(5000, event -> openConnection());
    private List<Conversation> results;
    private GatewayClient socket;
    private GatewayClient.Pairing pairing;
    private boolean remember;
    private boolean ready;
    private boolean fullSearch;
    private boolean changingSidebar;
    private boolean moreHistory;
    private boolean preparingFile;
    private long searchId;
    private String current = preferences.get("conversation", UUID.randomUUID().toString());
    private JsonObject attachment;
    private JsonObject pending;
    private FileRequest fileRequest;

    private record Conversation(String id, String title, long order) {
        @Override public String toString() { return title; }
    }
    private record FileRequest(String task, String side, boolean preview) {}

    public static void main(String[] args) {
        if (args.length == 2 && "--write-icons".equals(args[0])) {
            try { writeIcons(Path.of(args[1])); }
            catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            return;
        }
        System.setProperty("apple.awt.application.name", "MyAI");
        System.setProperty("apple.laf.useScreenMenuBar", "true");
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (ReflectiveOperationException | UnsupportedLookAndFeelException ignored) {}
            MyAI app = new MyAI();
            if (args.length == 2 && "--smoke-test".equals(args[0])) {
                try { Files.writeString(Path.of(args[1]), "MyAI desktop UI initialized"); }
                catch (Exception failure) { System.exit(1); }
                app.dispose();
                System.exit(0);
            }
            app.setVisible(true);
            app.loadPairing();
        });
    }

    private MyAI() {
        super("MyAI");
        setIconImage(mark());
        if (Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(Taskbar.Feature.ICON_IMAGE)) {
            Taskbar.getTaskbar().setIconImage(mark());
        }
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(730, 480));
        setSize(1050, 720);
        setLocationByPlatform(true);
        searchTimer.setRepeats(false);
        retry.setRepeats(false);
        setContentPane(build());
        renderThread(false);
        refreshSend();
        addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                retry.stop();
                searchTimer.stop();
                closeSocket();
                files.shutdownNow();
            }
        });
    }

    private JComponent build() {
        var left = new JPanel(new BorderLayout(0, 10));
        left.setBackground(SURFACE);
        left.setBorder(new EmptyBorder(19, 15, 14, 15));
        var leftTop = new JPanel();
        leftTop.setOpaque(false);
        leftTop.setLayout(new BoxLayout(leftTop, BoxLayout.Y_AXIS));
        JLabel brand = new JLabel("MyAI", new ImageIcon(mark().getScaledInstance(34, 34, Image.SCALE_SMOOTH)), JLabel.LEFT);
        brand.setFont(brand.getFont().deriveFont(Font.BOLD, 24));
        brand.setForeground(INK);
        leftTop.add(brand);
        leftTop.add(Box.createVerticalStrut(14));
        JButton create = new JButton("＋ 新建对话");
        create.addActionListener(event -> openChat(UUID.randomUUID().toString()));
        create.setAlignmentX(Component.LEFT_ALIGNMENT);
        leftTop.add(create);
        leftTop.add(Box.createVerticalStrut(13));
        search.putClientProperty("JTextField.placeholderText", "搜索历史对话");
        search.setToolTipText("搜索标题、消息和附件名");
        search.getAccessibleContext().setAccessibleName("搜索历史对话");
        search.setMaximumSize(new Dimension(Integer.MAX_VALUE, search.getPreferredSize().height + 8));
        search.setAlignmentX(Component.LEFT_ALIGNMENT);
        search.getDocument().addDocumentListener(watch(this::filterChanged));
        leftTop.add(search);
        leftTop.add(Box.createVerticalStrut(7));
        searchStatus.setForeground(MUTED);
        leftTop.add(searchStatus);
        left.add(leftTop, BorderLayout.NORTH);

        history.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        history.setFixedCellHeight(39);
        history.setBackground(PAPER);
        history.setCellRenderer((list, item, index, selected, focus) -> {
            var row = new JLabel();
            row.putClientProperty("html.disable", true);
            row.setText(item.title());
            row.setOpaque(true);
            row.setBackground(selected ? new Color(216, 234, 225) : PAPER);
            row.setForeground(selected ? GREEN : INK);
            row.setToolTipText(item.title());
            row.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(223, 231, 226)),
                    new EmptyBorder(0, 9, 0, 5)));
            return row;
        });
        history.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !changingSidebar && history.getSelectedValue() != null) {
                openChat(history.getSelectedValue().id());
            }
        });
        JScrollPane historyScroll = new JScrollPane(history);
        historyScroll.setBorder(BorderFactory.createEmptyBorder());
        left.add(historyScroll);

        var main = new JPanel(new BorderLayout(0, 8));
        main.setBackground(PAPER);
        main.setBorder(new EmptyBorder(15, 22, 17, 22));
        var top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        var info = new JPanel();
        info.setOpaque(false);
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        heading.setFont(heading.getFont().deriveFont(Font.BOLD, 22));
        heading.setForeground(INK);
        status.setForeground(MUTED);
        info.add(heading);
        info.add(status);
        top.add(info);
        var settings = new JButton("⚙ 设置");
        settings.addActionListener(event -> settings());
        top.add(settings, BorderLayout.EAST);
        main.add(top, BorderLayout.NORTH);

        thread.setBackground(PAPER);
        thread.setLayout(new BoxLayout(thread, BoxLayout.Y_AXIS));
        threadScroll.setBorder(BorderFactory.createEmptyBorder());
        threadScroll.getVerticalScrollBar().setUnitIncrement(18);
        main.add(threadScroll);
        main.add(buildComposer(), BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, main);
        split.setDividerLocation(260);
        split.setDividerSize(5);
        split.setBorder(BorderFactory.createEmptyBorder());
        return split;
    }

    private JPanel buildComposer() {
        JPanel area = new JPanel(new BorderLayout(0, 5));
        area.setBackground(SURFACE);
        area.setBorder(new EmptyBorder(9, 11, 9, 11));
        composer.setLineWrap(true);
        composer.setWrapStyleWord(true);
        composer.setFont(composer.getFont().deriveFont(15f));
        composer.setToolTipText("输入消息。Ctrl+Enter 或 Command+Enter 发送。");
        composer.getAccessibleContext().setAccessibleName("消息输入");
        composer.getDocument().addDocumentListener(watch(this::refreshSend));
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), "send");
        composer.getActionMap().put("send", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { submit(); }
        });
        JScrollPane inputScroll = new JScrollPane(composer);
        inputScroll.setBorder(BorderFactory.createLineBorder(new Color(215, 225, 218)));
        area.add(inputScroll);
        attachmentLabel.setVisible(false);
        attachmentLabel.addActionListener(event -> clearAttachment());
        area.add(attachmentLabel, BorderLayout.NORTH);
        JPanel actions = new JPanel(new BorderLayout());
        actions.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        left.setOpaque(false);
        addFile.addActionListener(event -> pickFile());
        left.add(addFile);
        format.setToolTipText("选择本轮回复格式");
        left.add(format);
        actions.add(left);
        send.addActionListener(event -> submit());
        actions.add(send, BorderLayout.EAST);
        area.add(actions, BorderLayout.SOUTH);
        return area;
    }

    private void loadPairing() {
        String endpoint = preferences.get("address", "");
        String password = preferences.get("pairing", "");
        if (endpoint.isBlank() || password.isBlank()) return;
        try {
            pairing = GatewayClient.Pairing.parse(endpoint, password);
            remember = true;
            openConnection();
        } catch (IllegalArgumentException invalid) { status.setText("请在设置中检查地址和配对密码"); }
    }

    private void settings() {
        JTextField endpoint = new JTextField(pairing == null
                ? preferences.get("address", "") : pairing.endpoint().getAuthority(), 32);
        JPasswordField password = new JPasswordField(pairing == null
                ? preferences.get("pairing", "") : pairing.password(), 32);
        JCheckBox saved = new JCheckBox("在本机记住配对密码", pairing == null || remember);
        var panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        for (Component field : List.of(new JLabel("服务器地址，例如 example.net:4000"), endpoint,
                Box.createVerticalStrut(7), new JLabel("配对密码"), password, saved)) {
            panel.add(field);
        }
        int option = JOptionPane.showConfirmDialog(this, panel, "连接设置",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (option != JOptionPane.OK_OPTION) return;
        try {
            GatewayClient.Pairing next = GatewayClient.Pairing.parse(endpoint.getText(), new String(password.getPassword()));
            if (pairing != null && !pairing.password().equals(next.password())) {
                pending = null;
                conversations.clear();
                messages.clear();
                moreHistory = false;
                current = UUID.randomUUID().toString();
                renderThread(false);
            }
            pairing = next;
            remember = saved.isSelected();
            if (!remember) preferences.remove("pairing");
            openConnection();
        } catch (IllegalArgumentException invalid) { problem(invalid.getMessage()); }
    }

    private void openConnection() {
        retry.stop();
        closeSocket();
        if (pairing == null) return;
        status.setText("正在建立加密连接…");
        try {
            GatewayClient[] reference = new GatewayClient[1];
            GatewayClient connection = new GatewayClient(pairing,
                    event -> SwingUtilities.invokeLater(() -> receive(reference[0], event)),
                    (code, reason) -> SwingUtilities.invokeLater(() -> disconnected(reference[0], code)));
            reference[0] = connection;
            socket = connection;
            connection.connect();
            Timer timeout = new Timer(15000, event -> {
                if (socket == connection && !ready) {
                    closeSocket();
                    status.setText("连接超时 · 正在等待重试");
                    retry.restart();
                }
            });
            timeout.setRepeats(false);
            timeout.start();
        } catch (Exception invalid) { status.setText("无法初始化加密连接"); }
    }

    private void closeSocket() {
        GatewayClient previous = socket;
        socket = null;
        ready = false;
        fullSearch = false;
        fileRequest = null;
        refreshSend();
        if (previous != null) previous.close();
    }

    private void disconnected(GatewayClient source, int code) {
        if (socket != source) return;
        closeSocket();
        filterChanged();
        status.setText(code == 1008 ? "配对失败 · 请检查设置" : "连接已断开 · 正在等待重试");
        if (code != 1008 && isDisplayable()) retry.restart();
    }

    private void receive(GatewayClient source, JsonObject event) {
        if (socket != source) return;
        try {
            switch (str(event, "type")) {
                case "ready" -> {
                    ready = true;
                    fullSearch = event.has("historySearch") && event.get("historySearch").getAsBoolean();
                    preferences.put("address", pairing.endpoint().getAuthority());
                    if (remember) preferences.put("pairing", pairing.password());
                    status.setText("● 已连接");
                    requestHistory(0);
                    if (pending != null) packet(pending);
                    refreshSend();
                    filterChanged();
                }
                case "conversations" -> {
                    conversations.clear();
                    for (Conversation item : summaries(event.getAsJsonArray("items"))) conversations.put(item.id(), item);
                    refreshSidebar();
                    updateHeading();
                }
                case "search" -> {
                    if (event.get("requestId").getAsLong() == searchId) {
                        results = summaries(event.getAsJsonArray("items"));
                        refreshSidebar();
                    }
                }
                case "history" -> {
                    if (!current.equals(str(event, "conversation"))) return;
                    for (JsonElement item : event.getAsJsonArray("items")) {
                        JsonObject task = item.getAsJsonObject();
                        messages.put(str(task, "id"), task);
                    }
                    moreHistory = event.get("more").getAsBoolean();
                    renderThread(event.get("before").getAsLong() == 0);
                }
                case "task" -> receiveTask(event.getAsJsonObject("task"));
                case "attachment" -> receiveFile(event);
                case "error" -> {
                    String id = str(event, "id");
                    if (pending != null && (id.equals(str(pending, "id")) || id.isEmpty() && fileRequest == null)) {
                        if (current.equals(str(pending, "conversation"))) {
                            if (composer.getText().isEmpty()) composer.setText(str(pending, "prompt"));
                            attachment = pending.getAsJsonObject("attachment");
                            showAttachment();
                        }
                        pending = null;
                        refreshSend();
                    }
                    fileRequest = null;
                    problem(str(event, "message"));
                }
                default -> {}
            }
        } catch (RuntimeException invalid) { status.setText("服务返回了无法识别的数据"); }
    }

    private void receiveTask(JsonObject task) {
        if (pending != null && str(pending, "id").equals(str(task, "id"))) {
            pending = null;
            refreshSend();
        }
        String id = str(task, "conversation");
        if (id.isEmpty()) {
            status.setText("请升级服务端到 V0.4");
            return;
        }
        Conversation previous = conversations.get(id);
        String title = previous == null ? titleFor(task) : previous.title();
        long order = task.get("order").getAsLong();
        conversations.put(id, new Conversation(id, title, previous == null ? order : Math.max(order, previous.order())));
        filterChanged();
        if (current.equals(id)) {
            messages.put(str(task, "id"), task);
            renderThread(true);
        }
    }

    private void filterChanged() {
        searchId++;
        results = null;
        searchTimer.stop();
        if (ready && fullSearch && !query().isEmpty()) searchTimer.restart();
        refreshSidebar();
    }

    private void searchServer() {
        if (!ready || !fullSearch || query().isEmpty()) return;
        var request = GatewayClient.packet("search");
        request.addProperty("query", query());
        request.addProperty("requestId", ++searchId);
        packet(request);
    }

    private void refreshSidebar() {
        String query = query();
        String[] words = normalize(query).split("\\s+");
        List<Conversation> visible = results != null ? results : conversations.values().stream()
                .filter(item -> java.util.Arrays.stream(words).allMatch(normalize(item.title())::contains))
                .sorted(Comparator.comparingLong(Conversation::order).reversed()).toList();
        changingSidebar = true;
        try {
            sidebar.clear();
            for (Conversation item : visible) {
                sidebar.addElement(item);
                if (current.equals(item.id())) history.setSelectedIndex(sidebar.size() - 1);
            }
        } finally { changingSidebar = false; }
        if (query.isEmpty()) searchStatus.setText(visible.isEmpty() ? "暂无历史对话" : "对话历史");
        else if (ready && fullSearch && results == null) searchStatus.setText("正在搜索对话内容…");
        else searchStatus.setText(visible.size() + " 个匹配" + (ready && fullSearch ? "" : "（仅标题）"));
    }

    private String query() {
        String value = search.getText().strip();
        return value.length() <= 80 ? value : value.substring(0, 80);
    }

    private void openChat(String id) {
        if (id.equals(current)) return;
        current = id;
        preferences.put("conversation", current);
        messages.clear();
        moreHistory = false;
        composer.setText("");
        clearAttachment();
        format.setSelectedIndex(0);
        refreshSidebar();
        renderThread(false);
        requestHistory(0);
        composer.requestFocusInWindow();
    }

    private void requestHistory(long before) {
        var request = GatewayClient.packet("history");
        request.addProperty("conversation", current);
        if (before > 0) request.addProperty("before", before);
        packet(request);
    }

    private void renderThread(boolean bottom) {
        updateHeading();
        thread.removeAll();
        List<JsonObject> ordered = messages.values().stream()
                .sorted(Comparator.comparingLong(item -> item.get("order").getAsLong())).toList();
        if (ordered.isEmpty()) {
            var welcome = new JPanel(new BorderLayout(0, 7));
            welcome.setOpaque(false);
            welcome.setBorder(new EmptyBorder(26, 8, 18, 8));
            JLabel question = new JLabel("今天想做什么？");
            question.setFont(question.getFont().deriveFont(Font.BOLD, 31));
            question.setForeground(INK);
            welcome.add(question, BorderLayout.NORTH);
            var hint = new JLabel("发送消息、图片或文件，或从下方选择要生成的回复格式。");
            hint.setForeground(MUTED);
            welcome.add(hint);
            welcome.setMaximumSize(new Dimension(Integer.MAX_VALUE, welcome.getPreferredSize().height));
            thread.add(welcome);
        } else {
            if (moreHistory) {
                JButton earlier = new JButton("加载更早的消息");
                earlier.setAlignmentX(Component.LEFT_ALIGNMENT);
                earlier.addActionListener(event -> requestHistory(ordered.getFirst().get("order").getAsLong()));
                thread.add(earlier);
            }
            for (JsonObject task : ordered) {
                message(str(task, "prompt"), task, "input", true);
                String response = switch (str(task, "state")) {
                    case "queued" -> "已接收，等待执行…";
                    case "running" -> "正在处理…";
                    case "failed" -> "执行失败\n" + str(task, "result");
                    default -> str(task, "result");
                };
                message(response, task, "output", false);
            }
        }
        thread.revalidate();
        thread.repaint();
        if (bottom) SwingUtilities.invokeLater(() -> {
            JScrollBar bar = threadScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void message(String text, JsonObject task, String side, boolean user) {
        JsonObject media = task.getAsJsonObject(side);
        if (text.isEmpty() && media == null) return;
        var card = new JPanel(new BorderLayout(0, 5));
        card.setBackground(user ? SURFACE : PAPER);
        card.setBorder(new EmptyBorder(10, 13, 10, 13));
        JLabel author = new JLabel(user ? "你" : "MyAI");
        author.setForeground(GREEN);
        author.setFont(author.getFont().deriveFont(Font.BOLD));
        card.add(author, BorderLayout.NORTH);
        if (!text.isEmpty()) {
            var content = new JTextArea(text);
            content.setEditable(false);
            content.setOpaque(false);
            content.setForeground(INK);
            content.setLineWrap(true);
            content.setWrapStyleWord(true);
            content.setFont(composer.getFont());
            content.setSize(Math.max(320, threadScroll.getViewport().getWidth() - 48), Short.MAX_VALUE);
            card.add(content);
        }
        if (media != null) {
            var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            buttons.setOpaque(false);
            if (str(media, "mime").startsWith("image/")) {
                var preview = new JButton("预览图片");
                preview.addActionListener(event -> askForFile(task, side, true));
                buttons.add(preview);
            }
            String name = str(media, "name");
            var download = new JButton("保存附件");
            download.addActionListener(event -> askForFile(task, side, false));
            buttons.add(download);
            JLabel file = new JLabel();
            file.putClientProperty("html.disable", true);
            file.setText(name);
            file.setForeground(MUTED);
            buttons.add(file);
            card.add(buttons, BorderLayout.SOUTH);
        }
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, card.getPreferredSize().height));
        thread.add(card);
        thread.add(Box.createVerticalStrut(8));
    }

    private void updateHeading() {
        Conversation item = conversations.get(current);
        heading.putClientProperty("html.disable", true);
        heading.setText(item == null ? "MyAI" : item.title());
    }

    private void submit() {
        if (!send.isEnabled()) return;
        String text = composer.getText().strip();
        if (text.length() > 4000) { problem("消息文字最多 4000 个字符。"); return; }
        var request = GatewayClient.packet("submit");
        request.addProperty("id", UUID.randomUUID().toString());
        request.addProperty("conversation", current);
        request.addProperty("mode", MODES[format.getSelectedIndex()]);
        request.addProperty("prompt", text);
        if (attachment != null) request.add("attachment", attachment);
        pending = request;
        composer.setText("");
        clearAttachment();
        format.setSelectedIndex(0);
        refreshSend();
        packet(request);
    }

    private void pickFile() {
        var picker = new JFileChooser();
        if (picker.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = picker.getSelectedFile().toPath();
        String chatId = current;
        preparingFile = true;
        addFile.setEnabled(false);
        refreshSend();
        files.execute(() -> {
            try {
                JsonObject loaded = DesktopFiles.upload(path);
                SwingUtilities.invokeLater(() -> {
                    if (current.equals(chatId)) attachment = loaded;
                    showAttachment();
                    finishPick();
                });
            } catch (Exception failure) {
                SwingUtilities.invokeLater(() -> {
                    finishPick();
                    problem(failure.getMessage());
                });
            }
        });
    }

    private void finishPick() {
        preparingFile = false;
        addFile.setEnabled(true);
        refreshSend();
    }

    private void clearAttachment() { attachment = null; showAttachment(); }

    private void showAttachment() {
        attachmentLabel.setVisible(attachment != null);
        attachmentLabel.setText(attachment == null ? "" : "附件 · " + str(attachment, "name") + " ×");
        refreshSend();
    }

    private void askForFile(JsonObject task, String side, boolean preview) {
        if (!ready) { problem("请连接后再读取附件。"); return; }
        if (fileRequest != null) return;
        fileRequest = new FileRequest(str(task, "id"), side, preview);
        var request = GatewayClient.packet("attachment");
        request.addProperty("id", fileRequest.task());
        request.addProperty("side", side);
        packet(request);
    }

    private void receiveFile(JsonObject event) {
        FileRequest request = fileRequest;
        if (request == null || !request.task().equals(str(event, "id")) || !request.side().equals(str(event, "side"))) return;
        fileRequest = null;
        String name = DesktopFiles.safeName(str(event.getAsJsonObject("file"), "name"));
        String data = str(event, "data");
        files.execute(() -> {
            try {
                byte[] bytes = DesktopFiles.download(data);
                if (request.preview()) {
                    ImageIcon image = DesktopFiles.preview(bytes);
                    SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, new JLabel(image),
                            "图片预览", JOptionPane.PLAIN_MESSAGE));
                } else SwingUtilities.invokeLater(() -> saveFile(name, bytes));
            } catch (Exception failure) { SwingUtilities.invokeLater(() -> problem(failure.getMessage())); }
        });
    }

    private void saveFile(String name, byte[] bytes) {
        JFileChooser picker = new JFileChooser();
        picker.setSelectedFile(new java.io.File(name));
        if (picker.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path destination = picker.getSelectedFile().toPath();
        boolean replace = Files.exists(destination);
        if (replace && JOptionPane.showConfirmDialog(this, "替换现有文件？", "保存附件",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        files.execute(() -> {
            try {
                if (replace) Files.write(destination, bytes, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                else Files.write(destination, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                SwingUtilities.invokeLater(() -> status.setText("● 已保存附件"));
            } catch (Exception error) { SwingUtilities.invokeLater(() -> problem("保存附件失败。")); }
        });
    }

    private void refreshSend() {
        send.setEnabled(ready && pending == null && !preparingFile
                && (!composer.getText().isBlank() || attachment != null));
    }

    private void packet(JsonObject request) {
        if (!ready || socket == null) return;
        try { socket.sendPacket(request); }
        catch (RuntimeException disconnected) { socket.close(); }
    }

    private void problem(String message) {
        JOptionPane.showMessageDialog(this, message == null || message.isBlank() ? "操作失败，请重试。" : message,
                "MyAI", JOptionPane.WARNING_MESSAGE);
    }

    private static List<Conversation> summaries(JsonArray items) {
        List<Conversation> result = new ArrayList<>();
        for (JsonElement item : items) {
            JsonObject data = item.getAsJsonObject();
            result.add(new Conversation(str(data, "id"), str(data, "title"), data.get("order").getAsLong()));
        }
        return result;
    }

    private static String titleFor(JsonObject task) {
        String name = str(task, "prompt").replaceAll("\\s+", " ").strip();
        if (name.isEmpty() && task.has("input")) name = str(task.getAsJsonObject("input"), "name");
        return name.length() <= 35 ? name : name.substring(0, 35) + "…";
    }

    private static String str(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static String normalize(String input) {
        return java.text.Normalizer.normalize(input, java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static DocumentListener watch(Runnable update) {
        return new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { update.run(); }
            @Override public void removeUpdate(DocumentEvent e) { update.run(); }
            @Override public void changedUpdate(DocumentEvent e) { update.run(); }
        };
    }

    private static BufferedImage mark() {
        var image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(11, 59, 51));
            g.fillRoundRect(1, 1, 126, 126, 33, 33);
            g.setColor(new Color(242, 255, 247));
            g.setStroke(new BasicStroke(5, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            var bubble = new Path2D.Double();
            bubble.moveTo(80, 38); bubble.lineTo(48, 38);
            bubble.quadTo(31, 38, 31, 54); bubble.lineTo(31, 68);
            bubble.quadTo(31, 84, 47, 84); bubble.lineTo(49, 84);
            bubble.lineTo(45, 94); bubble.lineTo(64, 84); bubble.lineTo(76, 84);
            bubble.quadTo(93, 84, 93, 67); bubble.lineTo(93, 62);
            g.draw(bubble);
            g.setColor(new Color(201, 247, 165));
            sparkle(g, 71, 59, 17);
            sparkle(g, 94, 39, 9);
        } finally { g.dispose(); }
        return image;
    }

    private static void writeIcons(Path directory) throws java.io.IOException {
        Files.createDirectories(directory);
        var image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(mark(), 0, 0, 256, 256, null);
        } finally { graphics.dispose(); }
        var bytes = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", bytes);
        byte[] png = bytes.toByteArray();
        var ico = java.nio.ByteBuffer.allocate(22 + png.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        ico.putShort((short) 0).putShort((short) 1).putShort((short) 1);
        ico.putInt(0).putShort((short) 1).putShort((short) 32).putInt(png.length).putInt(22).put(png);
        Files.write(directory.resolve("MyAI.ico"), ico.array());
        var icns = java.nio.ByteBuffer.allocate(16 + png.length);
        icns.putInt(0x69636e73).putInt(16 + png.length).putInt(0x69633038).putInt(8 + png.length).put(png);
        Files.write(directory.resolve("MyAI.icns"), icns.array());
    }

    private static void sparkle(Graphics2D g, int x, int y, int r) {
        var shape = new Path2D.Double();
        shape.moveTo(x, y - r);
        shape.quadTo(x + 2, y - 2, x + r, y);
        shape.quadTo(x + 2, y + 2, x, y + r);
        shape.quadTo(x - 2, y + 2, x - r, y);
        shape.quadTo(x - 2, y - 2, x, y - r);
        g.fill(shape);
    }
}
