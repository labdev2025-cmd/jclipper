import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.util.List;

// ======= Config gerais =======
private static final int SERVER_PORT = 51515; // IPC local
private static final int POLL_MS = 300;       // Monitoração do clipboard
private static final int MAX_VISIBLE = 20;    // Itens no popup

// ======= Ajustes visuais (fácil de mexer) =======
private static final int WINDOW_WIDTH = 840;
private static final int WINDOW_HEIGHT = 560;

private static final int FONT_BASE_PT = 14; // fonte padrão da UI
private static final int FONT_MONO_PT = 14; // fonte do preview (monoespaçada)
private static final int HEADER_FONT_PT = 16; // fonte do cabeçalho
private static final int TOOLTIP_FONT_PT = 13; // fonte do tooltip <pre>
private static final int LIST_CELL_HEIGHT = 24; // altura da linha da lista
// ================================================

void main(String[] args) {
    boolean wantsToggle = Arrays.asList(args).contains("--toggle") || Arrays.asList(args).contains("--show");

    // 1) Se for --toggle, tenta falar com a instância já rodando
    if (wantsToggle) {
        if (sendIpc("TOGGLE")) return; // conseguiu falar com a instância -> sair
        // Se não conseguiu, vamos subir a app e abrir o popup
    }

    // 2) Sobe a app (EDT)
    SwingUtilities.invokeLater(() -> {
        setupLookAndFeel();
        // Tenta criar o servidor IPC (se já existe outra instância, sai)
        ServerSocket server = tryStartIpcServer();
        if (server == null) {
            // Outra instância está rodando. Tentamos toggle e saímos.
            sendIpc("TOGGLE");
            return;
        }

        ClipboardHistory history = new ClipboardHistory();
        ClipboardMonitor monitor = new ClipboardMonitor(history);
        PopupUI popup = new PopupUI(history);

        // Thread para IPC: receber "TOGGLE" e abrir/fechar o popup
        Thread ipcThread = new Thread(() -> runIpcServer(server, popup), "ipc-server");
        ipcThread.setDaemon(true);
        ipcThread.start();

        // Inicia monitoramento de clipboard
        monitor.start();

        // Se foi chamado com --toggle e não havia instância, mostramos o popup já na inicialização
        if (wantsToggle) {
            popup.toggleAtMouse();
        }
    });
}

private static void setupLookAndFeel() {
    FlatDarkLaf.setup();
    // Fonte padrão (um pouco menor)
    Font base = UIManager.getFont("Label.font");
    if (base == null) base = new Font("SansSerif", Font.PLAIN, FONT_BASE_PT);
    FontUIResource def = new FontUIResource(base.deriveFont((float) FONT_BASE_PT));
    UIManager.put("defaultFont", def);

    // Estética estilo IntelliJ
    System.setProperty("flatlaf.useWindowDecorations", "true");
    System.setProperty("flatlaf.menuBarEmbedded", "true");
}

// ---- IPC (cliente) ----
private static boolean sendIpc(String message) {
    try (Socket sock = new Socket("127.0.0.1", SERVER_PORT)) {
        OutputStream os = sock.getOutputStream();
        os.write((message + "\n").getBytes(StandardCharsets.UTF_8));
        os.flush();
        return true;
    } catch (IOException e) {
        return false;
    }
}

// ---- IPC (servidor) ----
private static ServerSocket tryStartIpcServer() {
    try {
        return new ServerSocket(SERVER_PORT);
    } catch (IOException e) {
        return null;
    }
}

private static void runIpcServer(ServerSocket server, PopupUI popup) {
    while (true) {
        try (Socket s = server.accept();
             BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8))) {
            String line = br.readLine();
            if (line == null) continue;
            if (line.trim().equalsIgnoreCase("TOGGLE")) {
                SwingUtilities.invokeLater(popup::toggleAtMouse);
            } else if (line.trim().equalsIgnoreCase("SHOW")) {
                SwingUtilities.invokeLater(popup::showAtMouse);
            } else if (line.trim().equalsIgnoreCase("HIDE")) {
                SwingUtilities.invokeLater(popup::hidePopup);
            }
        } catch (IOException ignored) {
        }
    }
}

// ===== Monitor de área de transferência =====
static class ClipboardMonitor {
    private final Clipboard sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
    private final ClipboardHistory history;
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clipboard-poll");
        t.setDaemon(true);
        return t;
    });
    private String lastSeen = null;

    ClipboardMonitor(ClipboardHistory history) {
        this.history = history;
    }

    void start() {
        exec.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        try {
            Transferable t = sysClip.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (text != null) {
                    // Evita “falsos repetidos” por pooling – só registra quando o conteúdo mudar
                    if (!Objects.equals(text, lastSeen)) {
                        lastSeen = text;
                        history.add(text);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}

// ===== Armazenamento do histórico =====
static class ClipboardHistory {
    // Guarda tudo (inclui repetidos). Sem limite artificial.
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    synchronized void add(String text) {
        entries.addFirst(new Entry(Instant.now().toEpochMilli(), text));
    }

    synchronized List<Entry> latestMatching(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> q.isEmpty() || e.text.toLowerCase(Locale.ROOT).contains(q))
                .limit(limit)
                .collect(Collectors.toList());
    }

    static class Entry {
        final long ts;
        final String text;

        Entry(long ts, String text) {
            this.ts = ts;
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}

// ===== UI do Popup =====
static class PopupUI {
    private final ClipboardHistory history;

    private final JDialog dialog;
    private final JTextField searchField;
    private final JList<ClipboardHistory.Entry> list;
    private final DefaultListModel<ClipboardHistory.Entry> listModel;

    PopupUI(ClipboardHistory history) {
        this.history = history;

        dialog = new JDialog((Frame) null);
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);
        dialog.setModalityType(Dialog.ModalityType.MODELESS);
        dialog.getRootPane().putClientProperty("JRootPane.titleBarBackground", UIManager.getColor("Panel.background"));

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));

        // ===== Topo: Cabeçalho + Caixa de pesquisa =====
        JLabel header = new JLabel("JClipper - Ferramenta de área de transferência");
        header.putClientProperty(FlatClientProperties.STYLE, "font: 700 " + HEADER_FONT_PT + ";");
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));

        searchField = new JTextField();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Pesquisar");
        searchField.setFont(searchField.getFont().deriveFont((float) FONT_BASE_PT));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.CENTER);
        content.add(top, BorderLayout.NORTH);

        // ===== Lista (linha única por item + scrollbar horizontal) =====
        listModel = new DefaultListModel<>();
        list = new JList<>(listModel) {
            @Override
            public boolean getScrollableTracksViewportWidth() {
                return false;
            }
        };
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(LIST_CELL_HEIGHT); // linha única
        list.setCellRenderer(new SingleLineRenderer()); // preview em linha + tooltip preservando formatação

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        content.add(scroll, BorderLayout.CENTER);

        // Interações
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() >= 1) copySelectedAndClose();
            }
        });
        list.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_ENTER) copySelectedAndClose();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) hidePopup();
            }
        });

        // Fechar ao perder foco (clique fora)
        dialog.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                hidePopup();
            }
        });

        // ESC fecha
        dialog.getRootPane().registerKeyboardAction(e -> hidePopup(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Filtro em tempo real
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refreshList();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refreshList();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refreshList();
            }
        });

        dialog.setContentPane(content);
        dialog.setSize(WINDOW_WIDTH, WINDOW_HEIGHT); // janela maior
    }

    void toggleAtMouse() {
        if (dialog.isVisible()) hidePopup();
        else showAtMouse();
    }

    void showAtMouse() {
        refreshList();
        positionAtMouse();
        dialog.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            searchField.requestFocusInWindow();
            if (!listModel.isEmpty()) list.setSelectedIndex(0);
        });
    }

    void hidePopup() {
        dialog.setVisible(false);
    }

    private void refreshList() {
        String q = searchField.getText();
        List<ClipboardHistory.Entry> data = history.latestMatching(q, MAX_VISIBLE);
        listModel.clear();
        for (ClipboardHistory.Entry e : data) listModel.addElement(e);
    }

    private void positionAtMouse() {
        Point mouse = MouseInfo.getPointerInfo().getLocation();
        Rectangle screen = getScreenBoundsAt(mouse);
        int x = mouse.x + 12;
        int y = mouse.y + 12;

        // Ajusta para não sair da tela
        Dimension sz = dialog.getSize();
        if (x + sz.width > screen.x + screen.width) x = (screen.x + screen.width) - sz.width - 8;
        if (y + sz.height > screen.y + screen.height) y = (screen.y + screen.height) - sz.height - 8;
        if (x < screen.x) x = screen.x + 8;
        if (y < screen.y) y = screen.y + 8;

        dialog.setLocation(x, y);
    }

    private Rectangle getScreenBoundsAt(Point p) {
        GraphicsDevice gd = null;
        for (GraphicsDevice d : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            GraphicsConfiguration gc = d.getDefaultConfiguration();
            Rectangle r = gc.getBounds();
            if (r.contains(p)) {
                gd = d;
                break;
            }
        }
        if (gd == null) gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        return gd.getDefaultConfiguration().getBounds();
    }

    private void copySelectedAndClose() {
        ClipboardHistory.Entry e = list.getSelectedValue();
        if (e == null) return;
        StringSelection sel = new StringSelection(e.text); // copia o ORIGINAL (com quebras/tabs)
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        } catch (Exception ignored) {
        }
        hidePopup();
    }

    /**
     * Util para previews e tooltip preservando formatação
     */
    static class Preview {
        static String toSingleLine(String s, int maxChars) {
            if (s == null) return "";
            String t = s.replace("\r", "");
            t = t.replace("\n", "⏎").replace("\t", "⇥");
            // Outros controles -> ponto meio (sem afetar texto original)
            t = t.replaceAll("\\p{Cntrl}", "•");
            if (t.length() > maxChars) t = t.substring(0, Math.max(0, maxChars - 1)) + "…";
            return t;
        }

        static String toHtmlTooltip(String s) {
            if (s == null) return null;
            String esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return "<html><div style='font-family:monospace; font-size:" + TOOLTIP_FONT_PT + "px; white-space:pre'>"
                    + esc + "</div></html>";
        }
    }

    /**
     * Renderer de item em linha única, com tooltip “formatado”
     */
    static class SingleLineRenderer extends JPanel implements ListCellRenderer<ClipboardHistory.Entry> {
        private final JLabel lbl;

        SingleLineRenderer() {
            setLayout(new BorderLayout());
            setBorder(new EmptyBorder(4, 10, 4, 10));
            lbl = new JLabel();
            // monoespaçado ajuda a enxergar indentação
            lbl.setFont(new Font(Font.MONOSPACED, Font.PLAIN, FONT_MONO_PT));
            lbl.setOpaque(false);
            add(lbl, BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ClipboardHistory.Entry> jList,
                ClipboardHistory.Entry value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {

            String single = Preview.toSingleLine(value.text, 200);
            lbl.setText(single);
            // ícone opcional para texto com múltiplas linhas reais
            lbl.setIcon((value.text != null && value.text.contains("\n")) ? UIManager.getIcon("FileView.textIcon") : null);
            setToolTipText(Preview.toHtmlTooltip(value.text));

            if (isSelected) {
                setBackground(jList.getSelectionBackground());
                setForeground(jList.getSelectionForeground());
                lbl.setForeground(jList.getSelectionForeground());
            } else {
                setBackground(jList.getBackground());
                setForeground(jList.getForeground());
                lbl.setForeground(jList.getForeground());
            }
            setOpaque(true);
            return this;
        }
    }
}
