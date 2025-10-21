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
 *  - Monitor de clipboard (pooling): executa num agendador dedicado, compara
 *    o valor anterior para evitar duplicatas causadas por pooling.
 *  - UI (Swing): JDialog sem decorações, leve e sempre no topo; renderização
 *    customizada da lista (linha única, tooltip monoespaçado preservando
 *    quebras/indentação).
 *
 *  Threading
 *  ---------
 *  - EDT (Event Dispatch Thread): toda a manipulação de UI é feita via EDT.
 *  - Executor dedicado ao pooling do clipboard (single-thread).
 *  - Thread para o servidor IPC.
 *
 *  Limitações conhecidas
 *  ---------------------
 *  - O monitor usa pooling (POLL_MS) — há um atraso máximo de detecção igual ao
 *    período de pooling.
 *  - O histórico guarda tudo em memória (sem persistência em disco).
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
 * Período (ms) do pooling do clipboard. Valores menores detectam mais rápido, mas consomem mais CPU.
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
 */
private static final int TOOLTIP_FONT_PT = 13; // fonte do tooltip <pre>

/**
 * Altura (px) de cada célula (linha) da lista.
 */
private static final int LIST_CELL_HEIGHT = 24; // altura da linha da lista
private static final int WINDOW_ARC = 16; // leve arredondamento
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
 * <p>Evita duplicatas causadas pelo pooling comparando com o último valor visto.</p>
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
     * para executar o pooling periódico.
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
     * Inicia o pooling do clipboard com o período definido em {@link #POLL_MS}.
     * A primeira execução ocorre imediatamente (delay 0).
     */
    void start() {
        exec.scheduleAtFixedRate(this::poll, 0, POLL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Rotina de pooling:
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
                    // Evita “falsos repetidos” por pooling – só registra quando o conteúdo mudar
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
 *   <li>Sem limite de tamanho artificial (pode crescer indefinidamente);</li>
 *   <li>Métodos {@code synchronized} para segurança em cenários multi-thread
 *       (pooling e UI podem acessar simultaneamente).</li>
 * </ul>
 */
static class ClipboardHistory {
    /**
     * Deque com entradas (mais novas na cabeça). Pode conter duplicatas de conteúdo em tempos diferentes.
     */
    private final ArrayDeque<Entry> entries = new ArrayDeque<>();

    /**
     * Adiciona um novo texto ao início do deque, carimbando o instante em milissegundos.
     *
     * @param text conteúdo textual do clipboard (original, sem transformações).
     */
    synchronized void add(String text) {
        entries.addFirst(new Entry(Instant.now().toEpochMilli(), text));
    }

    /**
     * Filtra as entradas por substring (case-insensitive) e retorna até {@code limit} itens,
     * mantendo a ordem do mais recente para o mais antigo.
     *
     * @param query termo de busca; se vazio/nulo, retorna simplesmente os mais recentes.
     * @param limit máximo de itens a retornar.
     * @return lista (possivelmente vazia) com as entradas encontradas.
     */
    synchronized List<Entry> latestMatching(String query, int limit) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return entries.stream()
                .filter(e -> q.isEmpty() || e.text.toLowerCase(Locale.ROOT).contains(q))
                .limit(limit)
                .collect(Collectors.toList());
    }

    /**
     * Representa um item do histórico.
     * <ul>
     *   <li>{@link #ts}: timestamp epoch em milissegundos do momento da captura;</li>
     *   <li>{@link #text}: conteúdo textual exatamente como estava no clipboard.</li>
     * </ul>
     */
    record Entry(long ts, String text) {

        @Override
        public String toString() {
            return text;
        }
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
 *   <li>Lista com linha única por item, tooltip em HTML monoespaçado preservando formatação;</li>
 *   <li>Busca reativa (DocumentListener) sobre o histórico.</li>
 * </ul>
 */
static class PopupUI {
    private final ClipboardHistory history;

    private final JDialog dialog;
    private final JTextField searchField;
    private final JList<ClipboardHistory.Entry> list;
    private final DefaultListModel<ClipboardHistory.Entry> listModel;

    // [ADD] elementos de UI para "nenhuma correspondencia"
    private final JLabel noMatchLabel;
    private final Color defaultSearchFg;

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

        // ===== Topo: Cabeçalho + Caixa de pesquisa =====
        JLabel header = new JLabel("JClipper - Ferramenta de área de transferência");
        header.putClientProperty(FlatClientProperties.STYLE, "font: 700 " + HEADER_FONT_PT + ";");
        header.setHorizontalAlignment(SwingConstants.LEFT);
        header.setBorder(new EmptyBorder(0, 0, 6, 0));

        searchField = new JTextField();
        searchField.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "Pesquisar");
        searchField.setFont(searchField.getFont().deriveFont((float) FONT_BASE_PT));

        // [ADD] cor original e label "nenhuma correspondencia"
        defaultSearchFg = searchField.getForeground();
        noMatchLabel = new JLabel("nenhuma correspondencia");
        noMatchLabel.setForeground(new Color(0xE53935));
        noMatchLabel.setVisible(false);

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(searchField, BorderLayout.CENTER);
        top.add(noMatchLabel, BorderLayout.SOUTH); // [ADD] mensagem sob a busca
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

        // Filtro em tempo real (qualquer modificação no campo de busca recarrega a lista)
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

    /**
     * Oculta o popup sem destruí-lo (mantém o estado para abrir novamente rápido).
     */
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

        // atualiza UI de "nenhuma correspondencia"
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
     *
     * @param p ponto (em coordenadas de tela) que serve de referência.
     * @return {@link Rectangle} com os limites do monitor correspondente.
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
     *
     * <p>Qualquer exceção ao definir o conteúdo do clipboard é ignorada para
     * manter a experiência fluida.</p>
     */
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
     * Utilidades para gerar visualizações:
     * <ul>
     *   <li><b>toSingleLine</b>: representação compacta (substitui quebras/tabs por símbolos, trunca com reticências);</li>
     *   <li><b>toHtmlTooltip</b>: HTML seguro (escape básico) com fonte monoespaçada e <code>white-space: pre</code>.</li>
     * </ul>
     */
    static class Preview {
        /**
         * Converte o texto para uma linha única, substituindo caracteres de controle
         * por símbolos visuais e limitando o tamanho.
         *
         * @param s        texto original (pode ser nulo).
         * @param maxChars máximo de caracteres da saída (inclui a reticência quando houver).
         * @return linha única pronta para exibição na célula.
         */
        static String toSingleLine(String s, int maxChars) {
            if (s == null) return "";
            String t = s.replace("\r", "");
            t = t.replace("\n", "⏎").replace("\t", "⇥");
            // Outros controles -> ponto meio (sem afetar texto original)
            t = t.replaceAll("\\p{Cntrl}", "•");
            if (t.length() > maxChars) t = t.substring(0, Math.max(0, maxChars - 1)) + "…";
            return t;
        }

        /**
         * Gera HTML simples para tooltip preservando indentação/novas linhas,
         * usando fonte monoespaçada.
         *
         * @param s texto original (pode ser nulo).
         * @return HTML com conteúdo escapado ou {@code null} se s for nulo.
         */
        static String toHtmlTooltip(String s) {
            if (s == null) return null;
            String esc = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            return "<html><div style='font-family:\"JetBrains Mono\",monospace; font-size:" + TOOLTIP_FONT_PT + "px; white-space:pre'>"
                    + esc + "</div></html>"; // prefere JetBrains Mono
        }
    }

    /**
     * Renderer que apresenta cada item do histórico em <em>linha única</em>
     * (com ícone opcional quando o texto original é multi-linha) e tooltip
     * “formatado” (HTML monoespaçado).
     */
    static class SingleLineRenderer extends JPanel implements ListCellRenderer<ClipboardHistory.Entry> {
        private final JLabel lbl;

        /**
         * Inicializa o painel/label com padding e fonte monoespaçada.
         */
        SingleLineRenderer() {
            setLayout(new BorderLayout());
            setBorder(new EmptyBorder(4, 10, 4, 10));
            lbl = new JLabel();
            // monoespaçado ajuda a enxergar indentação
            Font mono = tryFamily("JetBrains Mono", Font.PLAIN, FONT_MONO_PT);
            if (mono == null) mono = new Font(Font.MONOSPACED, Font.PLAIN, FONT_MONO_PT);
            lbl.setFont(mono); // usa JetBrains Mono se registrada
            lbl.setOpaque(false);
            add(lbl, BorderLayout.CENTER);
        }

        /**
         * Prepara a célula conforme estado de seleção/foco e conteúdo.
         * Define também o tooltip (HTML) com o texto original preservado.
         */
        @Override
        public Component getListCellRendererComponent(
                JList<? extends ClipboardHistory.Entry> jList,
                ClipboardHistory.Entry value,
                int index,
                boolean isSelected,
                boolean cellHasFocus) {

            String single = Preview.toSingleLine(value.text, 200);
            lbl.setText(single);
            // Ícone opcional para textos com múltiplas linhas reais
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
