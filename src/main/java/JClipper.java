/*
 * =============================================================================
 *  JClipper — Histórico de área de transferência com popup pesquisável
 * =============================================================================
 *
 *  Visão Geral
 *  -----------
 *  Este código implementa um utilitário desktop (Swing/FlatLaf) que:
 *   - Monitora continuamente a área de transferência do sistema (clipboard);
 *   - Armazena cada novo texto copiado em um histórico (com timestamp);
 *   - Mostra um popup leve e sempre no topo, próximo ao mouse, com busca
 *     instantânea em tempo real, permitindo selecionar um item para recopiá-lo.
 *
 *  Arquitetura
 *  -----------
 *  - IPC local (porta TCP em loopback): permite "alternar/mostrar/ocultar" o
 *    popup a partir de uma segunda invocação do executável (flags --toggle/--show).
 *    Somente uma instância principal mantém o servidor IPC ativo.
 *  - Monitor de clipboard (polling): executa num agendador dedicado, compara
 *    o valor anterior para evitar duplicatas causadas por polling.
 *  - UI (Swing): JDialog sem decorações, leve e sempre no topo; renderização
 *    customizada da lista (linha única, timestamp humano ao lado).
 *
 *  Threading
 *  ---------
 *  - EDT (Event Dispatch Thread): toda a manipulação de UI é feita via EDT.
 *  - Executor dedicado ao polling do clipboard (single-thread).
 *  - Thread para o servidor IPC.
 *
 *  Limitações conhecidas
 *  ---------------------
 *  - O monitor usa polling (POLL_MS) — há um atraso máximo de detecção igual ao
 *    período de polling.
 *
 *  Execução (exemplos)
 *  -------------------
 * Compile com mvn clean package
 *  - Execução normal (abre a instância principal se não houver outra):
 *      java -jar target/jclipper.jar
 *  - Alternar popup (se já houver instância, apenas alterna visibilidade):
 *      java -jar target/jclipper.jar --toggle
 *  - Forçar mostrar/ocultar via IPC (se servidor estiver rodando):
 *      java -jar target/jclipper.jar --show
 *
 *  Requisitos
 *  ----------
 *  - Java 25+ (ou superior, conforme APIs usadas).
 *  - FlatLaf (FlatDarkLaf e FlatClientProperties).
 * =============================================================================
 */

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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowEvent;
import java.awt.event.WindowFocusListener;
import java.awt.geom.RoundRectangle2D;
import java.util.List;

// ======= Config gerais =======

/**
 * Porta TCP local usada para IPC entre instâncias do aplicativo.
 */
private static final int SERVER_PORT = 51515; // IPC local

/**
 * Período (ms) do polling do clipboard. Valores menores detectam mais rápido, mas consomem mais CPU.
 */
private static final int POLL_MS = 300;       // Monitoração do clipboard

/**
 * Máximo de itens exibidos simultaneamente no popup.
 */
private static final int MAX_VISIBLE = 20;    // Itens no popup

// ======= Ajustes visuais (fácil de mexer) =======

/**
 * Largura do popup em pixels.
 */
private static final int WINDOW_WIDTH = 650;

/**
 * Altura do popup em pixels.
 */
private static final int WINDOW_HEIGHT = 540;

/**
 * Tamanho (pt) da fonte base da UI.
 */
private static final int FONT_BASE_PT = 14; // fonte padrão da UI

/**
 * Tamanho (pt) da fonte monoespaçada usada nas prévias dos itens.
 */
private static final int FONT_MONO_PT = 14; // fonte do preview (monoespaçada)

/**
 * Tamanho (pt) da fonte do cabeçalho do popup.
 */
private static final int HEADER_FONT_PT = 16; // fonte do cabeçalho

/**
 * Tamanho (pt) da fonte utilizada no tooltip HTML (bloco <pre>/monospace).
 * (mantido por compat, embora os itens não usem mais tooltip)
 */
private static final int TOOLTIP_FONT_PT = 13; // fonte do tooltip <pre>

/**
 * Altura (px) de cada célula (linha) da lista.
 */
private static final int LIST_CELL_HEIGHT = 24; // altura da linha da lista
private static final int WINDOW_ARC = 16; // leve arredondamento

// ======= Persistência & limites =======
/** Máximo de itens mantidos em memória/arquivo. */
private static final int MAX_HISTORY = 1000;
/** Caminho do arquivo de histórico (depende da plataforma). */
private static final Path HISTORY_FILE = HistoryIO.resolveHistoryFile();
// ================================================

/**
 * Ponto de entrada da aplicação.
 *
 * <p>Comportamento:</p>
 * <ul>
 *   <li>Se iniciado com <code>--toggle</code> ou <code>--show</code>, tenta
 *       comunicar-se com uma instância existente via IPC.</li>
 *   <li>Se não existir instância, inicia a UI, o servidor IPC, o monitor de
 *       clipboard e (caso haja <code>--toggle</code>) já abre o popup.</li>
 * </ul>
 *
 * @param args argumentos de linha de comando; reconhece <code>--toggle</code> e <code>--show</code>.
 */
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

        // carrega histórico persistido (até MAX_HISTORY)
        HistoryIO.loadInto(history);

        ClipboardMonitor monitor = new ClipboardMonitor(history);
        PopupUI popup = new PopupUI(history);

        // salva no desligamento também (extra segurança)
        Runtime.getRuntime().addShutdownHook(new Thread(() -> HistoryIO.save(history), "history-save-shutdown"));

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

/**
 * Configura o tema (FlatDarkLaf) e parâmetros visuais globais (fonte padrão,
 * decorações de janela ao estilo IntelliJ).
 *
 * <p>Importante: todas as chamadas que afetam UI devem ocorrer no EDT.</p>
 */
private static void setupLookAndFeel() {
    FlatDarkLaf.setup();

    // Registrar JetBrains Mono a partir do classpath (src/main/resources)
    registerJetBrainsMono();

    // Definir JetBrains Mono como fonte-base da UI (com fallbacks seguros)
    Font base = tryFamily("JetBrains Mono", Font.PLAIN, FONT_BASE_PT);
    if (base == null) base = UIManager.getFont("Label.font");
    if (base == null) base = new Font("SansSerif", Font.PLAIN, FONT_BASE_PT);
    UIManager.put("defaultFont", new FontUIResource(base.deriveFont((float) FONT_BASE_PT)));

    // Estética estilo IntelliJ / FlatLaf
    System.setProperty("flatlaf.useWindowDecorations", "true");
    System.setProperty("flatlaf.menuBarEmbedded", "true");
}

// ---- IPC (cliente) ----

/**
 * Envia um comando simples via TCP/loopback para a instância principal (servidor IPC).
 *
 * @param message comando textual (ex.: "TOGGLE", "SHOW", "HIDE").
 * @return {@code true} se conseguiu conectar/enviar; {@code false} caso o servidor não esteja disponível.
 */
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

/**
 * Tenta iniciar o servidor IPC na {@link #SERVER_PORT}. Se a porta já estiver
 * ocupada (outra instância ativa), retorna {@code null}.
 *
 * @return {@link ServerSocket} ativo ou {@code null} se já houver instância.
 */
private static ServerSocket tryStartIpcServer() {
    try {
        return new ServerSocket(SERVER_PORT);
    } catch (IOException e) {
        return null;
    }
}

/**
 * Loop do servidor IPC: aceita conexões e reage a comandos textuais.
 *
 * <p>Comandos aceitos:</p>
 * <ul>
 *   <li><b>TOGGLE</b>: alterna visibilidade do popup na posição do mouse;</li>
 *   <li><b>SHOW</b>: mostra o popup;</li>
 *   <li><b>HIDE</b>: oculta o popup.</li>
 * </ul>
 *
 * <p>Nota: a manipulação de UI é agendada no EDT via {@link SwingUtilities#invokeLater(Runnable)}.</p>
 *
 * @param server socket do servidor já vinculado.
 * @param popup  referência à UI para executar as ações.
 */
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
            // Silencia falhas pontuais de IO do socket; o loop continua aceitando novas conexões.
        }
    }
}

// ===== Monitor de área de transferência =====

/**
 * Responsável por inspecionar a área de transferência periodicamente e
 * registrar novos conteúdos de texto no {@link ClipboardHistory}.
 *
 * <p>Evita duplicatas causadas pelo polling comparando com o último valor visto.</p>
 */
static class ClipboardMonitor {
    /**
     * Referência ao clipboard do sistema.
     */
    private final Clipboard sysClip = Toolkit.getDefaultToolkit().getSystemClipboard();
    /**
     * Histórico onde os itens detectados são armazenados.
     */
    private final ClipboardHistory history;
    /**
     * Agendador single-thread com thread daemon nomeada "clipboard-poll"
     * para executar o polling periódico.
     */
    private final ScheduledExecutorService exec = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "clipboard-poll");
        t.setDaemon(true);
        return t;
    });
    /**
     * Último conteúdo de texto observado no clipboard (para evitar repetição).
     */
    private String lastSeen = null;

    /**
     * @param history repositório de histórico onde os textos serão adicionados.
     */
    ClipboardMonitor(ClipboardHistory history) {
        this.history = history;
    }

    /**
     * Inicia o polling do clipboard com o período definido em {@link #POLL_MS}.
     * A primeira execução ocorre imediatamente (delay 0).
     */
    void start() {
        exec.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Rotina de polling:
     * <ol>
     *   <li>Obtém o conteúdo atual do clipboard;</li>
     *   <li>Se for texto e diferente do último visto, adiciona ao histórico;</li>
     *   <li>Atualiza {@link #lastSeen}.</li>
     * </ol>
     *
     * <p>Exceções são intencionalmente ignoradas para que falhas de leitura
     * pontuais não derrubem o agendador.</p>
     */
    private void poll() {
        try {
            Transferable t = sysClip.getContents(null);
            if (t != null && t.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                String text = (String) t.getTransferData(DataFlavor.stringFlavor);
                if (text != null) {
                    // Evita “falsos repetidos” por polling – só registra quando o conteúdo mudar
                    if (!Objects.equals(text, lastSeen)) {
                        lastSeen = text;
                        history.add(text);
                    }
                }
            }
        } catch (Exception ignored) {
            // Qualquer falha de acesso ao clipboard é ignorada; a próxima iteração tentará novamente.
        }
    }
}

// ===== Armazenamento do histórico =====

/**
 * Estrutura de dados em memória para manter o histórico de textos copiados.
 *
 * <p>Características:</p>
 * <ul>
 *   <li>Utiliza {@link ArrayDeque} para inserção/iteração eficiente (mais novo primeiro);</li>
 *   <li>Limite definido por {@link #MAX_HISTORY};</li>
 *   <li>Métodos {@code synchronized} para segurança em cenários multi-thread
 *       (polling e UI podem acessar simultaneamente).</li>
 * </ul>
 */
static class ClipboardHistory {
    /**
     * Deque com entradas (mais novas na cabeça). Pode conter duplicatas de conteúdo em tempos diferentes.
     */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /**
     * Adiciona um novo texto ao início do deque, carimbando o instante em milissegundos.
     * Aplica limite e persiste de forma assíncrona.
     *
     * @param text conteúdo textual do clipboard (original, sem transformações).
     */
    synchronized void add(String text) {
        entries.addFirst(new Entry(Instant.now().toEpochMilli(), text));
        // aplica limite
        while (entries.size() > MAX_HISTORY) entries.removeLast();
        // persiste a cada inclusão (arquivo até 1000 linhas)
        HistoryIO.saveAsync(this);
    }

    /** Limpa todo o histórico e persiste. */
    synchronized void clear() {
        entries.clear();
        HistoryIO.saveAsync(this);
    }

    /** Carga inicial (lista em ordem "mais novo primeiro"). */
    synchronized void bulkLoad(List<Entry> itemsNewestFirst) {
        entries.clear();
        // garantir que o primeiro da lista acabe na cabeça (mais novo na cabeça)
        for (int i = itemsNewestFirst.size() - 1; i >= 0; i--) {
            entries.addFirst(itemsNewestFirst.get(i));
        }
    }

    /** Snapshot para UI/persistência. */
    synchronized List<Entry> snapshot() {
        return new ArrayList<>(entries);
    }

    /**
     * Filtra as entradas por substring (case-insensitive) e retorna até {@code limit} itens,
     * mantendo a ordem do mais recente para o mais antigo.
     */
    synchronized List<Entry> latestMatching(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> q.isEmpty() || e.text.toLowerCase(Locale.ROOT).contains(q))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /** Item do histórico. */
    record Entry(long ts, String text) {
        @Override
        public String toString() {
            return text;
        }
    }
}

// ===== Persistência simples do histórico =====
static class HistoryIO {
    private static final ExecutorService IO_EXEC =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "history-io");
                t.setDaemon(true);
                return t;
            });

    /** Resolve caminho do arquivo de histórico por SO. */
    static Path resolveHistoryFile() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path dir;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            dir = appData != null ? Paths.get(appData, "JClipper") :
                    Paths.get(System.getProperty("user.home"), "AppData", "Roaming", "JClipper");
        } else if (os.contains("mac")) {
            dir = Paths.get(System.getProperty("user.home"), "Library", "Application Support", "JClipper");
        } else {
            dir = Paths.get(System.getProperty("user.home"), ".local", "share", "JClipper");
        }
        return dir.resolve("history.txt");
    }

    /** Carrega arquivo para memória (até MAX_HISTORY). */
    static void loadInto(ClipboardHistory history) {
        Path f = HISTORY_FILE;
        if (!Files.exists(f)) return;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<ClipboardHistory.Entry> items = new ArrayList<>();
            // Arquivo salvo do mais novo para o mais velho; manter essa ordem
            for (String line : lines) {
                int tab = line.indexOf('\t');
                if (tab <= 0) continue;
                long ts = Long.parseLong(line.substring(0, tab));
                String b64 = line.substring(tab + 1);
                String text = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
                items.add(new ClipboardHistory.Entry(ts, text));
                if (items.size() >= MAX_HISTORY) break;
            }
            history.bulkLoad(items); // mantém "mais novo primeiro"
        } catch (Exception ignored) {
        }
    }

    /** Agenda gravação assíncrona. */
    static void saveAsync(ClipboardHistory history) {
        IO_EXEC.submit(() -> save(history));
    }

    /** Grava arquivo de forma segura (tmp + move atômico quando possível). */
    static void save(ClipboardHistory history) {
        try {
            Files.createDirectories(HISTORY_FILE.getParent());
            List<ClipboardHistory.Entry> items = history.snapshot(); // mais novo primeiro
            StringBuilder sb = new StringBuilder(items.size() * 64);
            for (ClipboardHistory.Entry e : items) {
                String b64 = Base64.getEncoder().encodeToString(e.text().getBytes(StandardCharsets.UTF_8));
                sb.append(e.ts()).append('\t').append(b64).append('\n');
            }
            Path tmp = HISTORY_FILE.resolveSibling(HISTORY_FILE.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(tmp, HISTORY_FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, HISTORY_FILE, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {
        }
    }
}

// ===== Conversor de timestamp "humano" em pt-BR =====
static class TimeFmt {
    private static final Locale PT = new Locale("pt", "BR");
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm", PT);
    private static final DateTimeFormatter DDMMDotHM = DateTimeFormatter.ofPattern("dd/MM HH:mm", PT);

    static String friendly(long ts) {
        ZoneId zone = ZoneId.systemDefault();
        ZonedDateTime t = Instant.ofEpochMilli(ts).atZone(zone);
        ZonedDateTime now = ZonedDateTime.now(zone);

        Duration d = Duration.between(t, now);
        long sec = Math.max(0, d.getSeconds());

        if (sec < 45) return "agora";
        if (sec < 90) return "há 1 minuto";
        long min = sec / 60;
        if (min < 45) return "há " + min + " minutos";
        if (min < 90) return "há 1 hora";

        // Mesmo dia?
        if (t.toLocalDate().equals(now.toLocalDate())) return "hoje " + t.format(HM);
        if (t.toLocalDate().plusDays(1).equals(now.toLocalDate())) return "ontem " + t.format(HM);

        // Fallback simples
        return t.format(DDMMDotHM);
    }
}

// ===== UI do Popup =====

/**
 * Implementa a janela popup (JDialog) que lista o histórico e permite busca/seleção.
 *
 * <p>Recursos de UX:</p>
 * <ul>
 *   <li>Abre próximo ao cursor do mouse;</li>
 *   <li>Fecha ao perder foco ou ao pressionar ESC;</li>
 *   <li>ENTER copia o item selecionado e fecha;</li>
 *   <li>Lista com linha única por item e timestamp amigável à direita;</li>
 *   <li>Busca reativa (DocumentListener) sobre o histórico;</li>
 *   <li>Botões "Limpar busca" e "Limpar histórico".</li>
 * </ul>
 */
static class PopupUI {
    private final ClipboardHistory history;

    private final JDialog dialog;
    private final JTextField searchField;
    private final JList<ClipboardHistory.Entry> list;
    private final DefaultListModel<ClipboardHistory.Entry> listModel;

    // elementos de UI para "nenhuma correspondência"
    private final JLabel noMatchLabel;
    private final Color defaultSearchFg;

    // botões extras
    private final JButton clearSearchBtn;
    private final JButton clearHistoryBtn;

    /**
     * Constrói toda a hierarquia de componentes do popup e configura
     * interações/atalhos.
     *
     * @param history fonte de dados (histórico) a ser exibida.
     */
    PopupUI(ClipboardHistory history) {
        this.history = history;

        dialog = new JDialog((Frame) null);
        dialog.setUndecorated(true);
        dialog.setAlwaysOnTop(true);
        dialog.setModalityType(Dialog.ModalityType.MODELESS);
        dialog.getRootPane().putClientProperty("JRootPane.titleBarBackground", UIManager.getColor("Panel.background"));
        dialog.setBackground(new Color(0, 0, 0, 0));
        dialog.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentShown(ComponentEvent e) {
                applyWindowShape();
            }

            @Override
            public void componentResized(ComponentEvent e) {
                applyWindowShape();
            }
        });

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(new EmptyBorder(14, 14, 14, 14));

        // ===== Topo: Cabeçalho + Caixa de pesquisa + botões =====
        JLabel header = new JLabel("JClipper - Ferramenta de área de transferência");
        header.putClientProperty(FlatClientProperties.STYLE, "font: 700 " + HEADER_FONT_PT + ";");
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));

        searchField = new JTextField();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Pesquisar");
        searchField.setFont(searchField.getFont().deriveFont((float) FONT_BASE_PT));

        defaultSearchFg = searchField.getForeground();
        noMatchLabel = new JLabel("nenhuma correspondência");
        noMatchLabel.setForeground(new Color(0xE53935));
        noMatchLabel.setVisible(false);

        // botão "limpar busca"
        clearSearchBtn = new JButton("×");
        clearSearchBtn.setFocusable(false);
        clearSearchBtn.setToolTipText("Limpar busca");
        clearSearchBtn.addActionListener(e -> {
            searchField.setText("");
            searchField.requestFocusInWindow();
        });
        clearSearchBtn.setVisible(false);

        // botão "limpar histórico"
        clearHistoryBtn = new JButton("Limpar histórico");
        clearHistoryBtn.setFocusable(false);
        clearHistoryBtn.addActionListener(e -> {
            history.clear();
            refreshList();
        });

        JPanel searchRow = new JPanel(new BorderLayout(6, 0));
        searchRow.setOpaque(false);
        searchRow.add(searchField, BorderLayout.CENTER);

        JPanel rightBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        rightBtns.setOpaque(false);
        rightBtns.add(clearSearchBtn);
        rightBtns.add(clearHistoryBtn);
        searchRow.add(rightBtns, BorderLayout.EAST);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(searchRow, BorderLayout.CENTER);
        top.add(noMatchLabel, BorderLayout.SOUTH);
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
        list.setCellRenderer(new SingleLineRenderer()); // preview em linha + timestamp (sem tooltip)

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        content.add(scroll, BorderLayout.CENTER);

        // ===== Interações de mouse e teclado =====
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

        // Fecha ao perder foco (ex.: clique fora do popup)
        dialog.addWindowFocusListener(new WindowFocusListener() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                // intencionalmente vazio
            }

            @Override
            public void windowLostFocus(WindowEvent e) {
                hidePopup();
            }
        });

        // Atalho global dentro do popup: ESC fecha
        dialog.getRootPane().registerKeyboardAction(e -> hidePopup(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0),
                JComponent.WHEN_IN_FOCUSED_WINDOW);

        // Filtro em tempo real (+ mostrar/ocultar botão "limpar busca")
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                onChange();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                onChange();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                onChange();
            }

            private void onChange() {
                refreshList();
                clearSearchBtn.setVisible(searchField.getText() != null && !searchField.getText().isBlank());
            }
        });

        dialog.setContentPane(content);
        dialog.setSize(WINDOW_WIDTH, WINDOW_HEIGHT); // janela maior
        applyWindowShape();
    }

    /**
     * Alterna a visibilidade do popup. Se já estiver visível, oculta; caso
     * contrário, posiciona próximo ao mouse e exibe.
     */
    void toggleAtMouse() {
        if (dialog.isVisible()) hidePopup();
        else showAtMouse();
    }

    /**
     * Atualiza a lista conforme a busca, posiciona a janela próximo ao ponteiro
     * e a torna visível. Após abrir, foca o campo de busca e seleciona o
     * primeiro item (se houver).
     */
    void showAtMouse() {
        refreshList();
        positionAtMouse();
        dialog.setVisible(true);
        SwingUtilities.invokeLater(() -> {
            searchField.requestFocusInWindow();
            if (!listModel.isEmpty()) list.setSelectedIndex(0);
        });
    }

    /** Oculta o popup sem destruí-lo (mantém estado). */
    void hidePopup() {
        dialog.setVisible(false);
    }

    /**
     * Recarrega os itens na lista conforme o texto atual do campo de busca,
     * limitando a {@link #MAX_VISIBLE} resultados.
     */
    private void refreshList() {
        String q = searchField.getText();
        List<ClipboardHistory.Entry> data = history.latestMatching(q, MAX_VISIBLE);
        listModel.clear();
        for (ClipboardHistory.Entry e : data) listModel.addElement(e);

        // atualiza UI de "nenhuma correspondência"
        applyNoMatchUI(q, listModel.getSize());
    }

    // controla texto/borda do campo de busca e label de aviso
    private void applyNoMatchUI(String query, int resultCount) {
        boolean hasQuery = query != null && !query.trim().isEmpty();
        boolean noMatch = hasQuery && resultCount == 0;

        if (noMatch) {
            searchField.setForeground(new Color(0xE53935));
            searchField.putClientProperty("JComponent.outline", "error"); // FlatLaf outline
            noMatchLabel.setVisible(true);
        } else {
            searchField.setForeground(defaultSearchFg);
            searchField.putClientProperty("JComponent.outline", null);
            noMatchLabel.setVisible(false);
        }
    }

    /**
     * Calcula posição próxima ao ponteiro do mouse, corrigindo para garantir
     * que o popup não "saia" da área visível do monitor corrente.
     */
    private void positionAtMouse() {
        Point mouse = MouseInfo.getPointerInfo().getLocation();
        Rectangle screen = getScreenBoundsAt(mouse);
        int x = mouse.x + 12;
        int y = mouse.y + 12;

        // Ajusta para não sair da tela (com pequena margem)
        Dimension sz = dialog.getSize();
        if (x + sz.width > screen.x + screen.width) x = (screen.x + screen.width) - sz.width - 8;
        if (y + sz.height > screen.y + screen.height) y = (screen.y + screen.height) - sz.height - 8;
        if (x < screen.x) x = screen.x + 8;
        if (y < screen.y) y = screen.y + 8;

        dialog.setLocation(x, y);
    }

    /**
     * Descobre os limites do monitor que contém o ponto dado. Se não encontrar
     * explicitamente (caso raro), retorna o monitor padrão.
     */
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

    /**
     * Copia o texto do item selecionado (o <em>original</em>, com quebras e tabs)
     * para a área de transferência do sistema e fecha o popup.
     */
    private void copySelectedAndClose() {
        ClipboardHistory.Entry e = list.getSelectedValue();
        if (e == null) return;
        StringSelection sel = new StringSelection(e.text()); // copia o ORIGINAL (com quebras/tabs)
        try {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
        } catch (Exception ignored) {
        }
        hidePopup();
    }

    /**
     * Utilidades para gerar visualizações:
     * <ul>
     *   <li><b>toSingleLine</b>: representação compacta (substitui quebras/tabs por símbolos, trunca com reticências);</li>
     *   <li><b>toHtmlTooltip</b>: HTML escapado com fonte monoespaçada (mantido por compatibilidade).</li>
     * </ul>
     */
    static class Preview {
        /** Converte o texto para uma linha única, substituindo controles e limitando tamanho. */
        static String toSingleLine(String s, int maxChars) {
            if (s == null) return "";
            String t = s.replace("\r", "");
            t = t.replace("\n", "⏎").replace("\t", "⇥");
            // Outros controles -> ponto meio (sem afetar texto original)
            t = t.replaceAll("\\p{Cntrl}", "•");
            if (t.length() > maxChars) t = t.substring(0, Math.max(0, maxChars - 1)) + "…";
            return t;
        }

        /** (não mais usada nos itens) Gera HTML simples para tooltip monoespaçado. */
        static String toHtmlTooltip(String s) {
            if (s == null) return null;
            String esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return "<html><div style='font-family:\"JetBrains Mono\",monospace; font-size:" + TOOLTIP_FONT_PT + "px; white-space:pre'>"
                    + esc + "</div></html>";
        }
    }

    /**
     * Renderer que apresenta cada item do histórico em <em>linha única</em>
     * e o timestamp amigável à direita. Sem tooltip de conteúdo.
     */
    static class SingleLineRenderer extends JPanel implements ListCellRenderer<ClipboardHistory.Entry> {
        private final JLabel lblText;
        private final JLabel lblTime;

        SingleLineRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(4, 10, 4, 10));

            lblText = new JLabel();
            Font mono = tryFamily("JetBrains Mono", Font.PLAIN, FONT_MONO_PT);
            if (mono == null) mono = new Font(Font.MONOSPACED, Font.PLAIN, FONT_MONO_PT);
            lblText.setFont(mono);
            lblText.setOpaque(false);

            lblTime = new JLabel();
            lblTime.setFont(lblTime.getFont().deriveFont(Font.PLAIN, Math.max(11f, FONT_MONO_PT - 3f)));
            lblTime.setForeground(UIManager.getColor("Label.disabledForeground"));

            add(lblText, BorderLayout.CENTER);
            add(lblTime, BorderLayout.EAST);
        }

        @Override
        public Component getListCellRendererComponent(
                JList<? extends ClipboardHistory.Entry> jList,
                ClipboardHistory.Entry value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {

            String single = Preview.toSingleLine(value.text(), 200);
            lblText.setText(single);
            // Ícone opcional para textos com múltiplas linhas reais
            lblText.setIcon((value.text() != null && value.text().contains("\n")) ? UIManager.getIcon("FileView.textIcon") : null);

            // Sem tooltip de conteúdo
            setToolTipText(null);

            // Timestamp amigável
            lblTime.setText(TimeFmt.friendly(value.ts()));

            if (isSelected) {
                setBackground(jList.getSelectionBackground());
                setForeground(jList.getSelectionForeground());
                lblText.setForeground(jList.getSelectionForeground());
                lblTime.setForeground(jList.getSelectionForeground());
            } else {
                setBackground(jList.getBackground());
                setForeground(jList.getForeground());
                lblText.setForeground(jList.getForeground());
                lblTime.setForeground(UIManager.getColor("Label.disabledForeground"));
            }
            setOpaque(true);
            return this;
        }
    }

    private void applyWindowShape() {
        int w = dialog.getWidth();
        int h = dialog.getHeight();
        if (w <= 0 || h <= 0) return;
        Shape shape = new RoundRectangle2D.Double(0, 0, w, h, WINDOW_ARC, WINDOW_ARC);
        dialog.setShape(shape);
    }
}

// ===== Utilitários para carregar/registrar fontes do classpath =====

/**
 * Carrega uma fonte .ttf do classpath (src/main/resources). Retorna null se falhar.
 */
private static Font loadFontFromResource(String cpPath) {
    try (InputStream is = ClassLoader.getSystemResourceAsStream(cpPath)) {
        if (is == null) {
            System.err.println("Fonte não encontrada no classpath: " + cpPath);
            return null;
        }
        return Font.createFont(Font.TRUETYPE_FONT, is);
    } catch (Exception e) {
        System.err.println("Falha ao carregar fonte: " + cpPath + " -> " + e);
        return null;
    }
}

/**
 * Registra no runtime as variações básicas da JetBrains Mono embutidas no JAR.
 */
private static void registerJetBrainsMono() {
    String base = "fonts/jetbrainsmono/ttf/";
    String[] files = new String[]{
            "JetBrainsMono-Regular.ttf",
            "JetBrainsMono-Bold.ttf",
            "JetBrainsMono-Italic.ttf",
            "JetBrainsMono-BoldItalic.ttf"
            // pode adicionar mais pesos se copiou todos
    };
    GraphicsEnvironment ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
    for (String f : files) {
        Font font = loadFontFromResource(base + f);
        if (font != null) {
            try {
                ge.registerFont(font);
            } catch (Exception e) {
                System.err.println("Não foi possível registrar a fonte: " + f + " -> " + e);
            }
        }
    }
}

/**
 * Retorna uma fonte pela família se ela realmente estiver disponível.
 */
private static Font tryFamily(String family, int style, int sizePt) {
    Font f = new Font(family, style, sizePt);
    if (!f.getFamily().equalsIgnoreCase(family)) return null; // quando não existe, vira "Dialog"
    return f;
}
