# JClipper

Um **gerenciador leve de histórico da área de transferência** (clipboard) para desktop, escrito em Java + Swing com tema **FlatLaf**.
Monitora o clipboard continuamente, guarda cada novo texto copiado e mostra um **popup pesquisável** (sempre no topo) próximo ao mouse para você localizar e recolar rapidamente qualquer item recente.

> **Observação:** este README foi escrito a partir da classe fornecida e do `pom.xml`. Ele explica como **compilar, executar, empacotar e usar** o aplicativo, além de detalhar arquitetura, configuração e solução de problemas.

---

## Sumário

* [Recursos](#recursos)
* [Requisitos](#requisitos)
* [Instalação e Build (Maven)](#instalação-e-build-maven)
* [Execução](#execução)
* [Uso do Popup](#uso-do-popup)
* [IPC: controlar o popup por linha de comando](#ipc-controlar-o-popup-por-linha-de-comando)
* [Configuração rápida (constantes fáceis de ajustar)](#configuração-rápida-constantes-fáceis-de-ajustar)
* [Arquitetura e funcionamento](#arquitetura-e-funcionamento)
* [Estrutura do código (visão por classe)](#estrutura-do-código-visão-por-classe)
* [Dicas de produtividade](#dicas-de-produtividade)
* [Kubuntu (KDE Plasma): Atalho personalizado](#kubuntu-kde-plasma-atalho-personalizado)
* [Solução de problemas](#solução-de-problemas)
* [Segurança e privacidade](#segurança-e-privacidade)
* [Licença](#licença)
* [Anexo: `pom.xml` (resumo)](#anexo-pomxml-resumo)

---

## Recursos

* ✅ **Histórico automático** do clipboard (texto) em memória, com captura contínua.
* ✅ **Popup pesquisável** e leve, posicionado próximo ao cursor.
* ✅ **Filtro em tempo real** enquanto você digita.
* ✅ **Copia no ENTER** o item selecionado (texto original, preservando quebras e tabs).
* ✅ **Fechamento rápido** (ESC ou clique fora).
* ✅ **Tema moderno (FlatDarkLaf)** e UI com fonte monoespaçada para melhor leitura das prévias.
* ✅ **IPC local (porta 51515)** para alternar/abrir/fechar o popup a partir de novas invocações do app ou scripts.

---

## Requisitos

* **JDK 25** (conforme `maven.compiler.source/target` no `pom.xml`).

  > Dica: se quiser usar um LTS como Java 21, ajuste essas propriedades no `pom.xml` para `21` e recompile.
* **Maven 3.9+**
* Ambiente gráfico (desktop) com acesso ao clipboard do sistema.

Dependência principal (já empacotada no *fat jar* via Shade):

* `com.formdev:flatlaf:${flatlaf.version}` (no POM, `3.6.2`).

---

## Instalação e Build (Maven)

1. **Clone/baixe** o projeto e garanta o layout padrão Maven:

```
src/
  main/
    java/
      JClipper.java      (classe principal; mainClass = JClipper)
pom.xml
```

2. **Compile e gere o JAR executável (sombrado)**:

```bash
mvn -q -e -DskipTests package
```

Ao final, o Maven Shade Plugin criará:

```
target/jclipper.jar
```

Esse JAR inclui todas as dependências e define `Main-Class: JClipper` no manifesto.

> Se preferir um nome com versão, ajuste `<finalName>` no `pom.xml`.

---

## Execução

### Primeira execução (instância principal)

```bash
java -jar target/jclipper.jar
```

* Sobe a UI, o **servidor IPC** (porta local `51515`) e o **monitor** do clipboard.
* A janela **não** abre automaticamente; é um utilitário residente.
  Você pode abrir/alternar o popup via **IPC** (veja abaixo) ou invocando com `--toggle`.

### Alternar popup a partir de uma segunda invocação

Se o app já estiver rodando (servidor IPC ativo):

```bash
java -jar target/jclipper.jar --toggle
```

* Envia o comando `TOGGLE` via IPC para a instância principal.
* Se **não** houver instância rodando, essa chamada inicializará o app e **abrirá** o popup.

### Outros comandos de execução

* **Mostrar** (sem alternar): `java -jar target/jclipper.jar --show`
  (equivale a enviar `SHOW` via IPC)
* **Ocultar** (por IPC — ver exemplos com `nc`/PowerShell): `HIDE`

---

## Uso do Popup

* **Abertura**: por `--toggle`/`--show` (ou script/atalho que envie IPC).
  O popup aparece **próximo ao cursor** e foca a caixa de pesquisa.
* **Pesquisar**: digite no campo superior; a lista filtra **em tempo real**.
* **Selecionar e copiar**: use as setas ↑/↓ e pressione **ENTER**.
  O texto **original** (com quebras e tabs) é colocado no clipboard.
* **Fechar**:

    * Pressione **ESC**;
    * ou clique fora da janela (perda de foco).
* **Visualização**:

    * A lista mostra cada item em **uma linha**, substituindo visualmente:

        * `\n` por `⏎` e `\t` por `⇥` (sem alterar o texto real);
    * **Tooltip** (passe o mouse) mostra o conteúdo **formatado monoespaçado** preservando linhas/indentação.

---

## IPC: controlar o popup por linha de comando

O app escuta **localhost:51515**. Comandos aceitos:

* `TOGGLE` – alterna visibilidade do popup;
* `SHOW` – mostra o popup;
* `HIDE` – oculta o popup.

### Exemplos

**Linux/macOS** (com `nc`/`netcat`):

```bash
printf "TOGGLE\n" | nc 127.0.0.1 51515
printf "SHOW\n"   | nc 127.0.0.1 51515
printf "HIDE\n"   | nc 127.0.0.1 51515
```

**Windows PowerShell**:

```powershell
$client = New-Object System.Net.Sockets.TcpClient("127.0.0.1", 51515)
$stream = $client.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$writer.WriteLine("TOGGLE"); $writer.Flush()
$client.Close()
```

> Dica: crie um **atalho de teclado do sistema** que execute `java -jar ... --toggle` para abrir/fechar o popup em qualquer lugar.

---

## Configuração rápida (constantes fáceis de ajustar)

Na classe principal (topo do arquivo):

```java
// ======= Config gerais =======
private static final int SERVER_PORT = 51515; // porta IPC local
private static final int POLL_MS = 300;       // período do monitor do clipboard (ms)
private static final int MAX_VISIBLE = 20;    // itens máximos exibidos no popup

// ======= Ajustes visuais =======
private static final int WINDOW_WIDTH = 840;
private static final int WINDOW_HEIGHT = 560;

private static final int FONT_BASE_PT = 14;     // fonte padrão da UI
private static final int FONT_MONO_PT = 14;     // fonte monoespaçada (lista)
private static final int HEADER_FONT_PT = 16;   // fonte do cabeçalho
private static final int TOOLTIP_FONT_PT = 13;  // fonte do tooltip <pre>
private static final int LIST_CELL_HEIGHT = 24; // altura da linha da lista
```

* **Responsividade**: reduza `POLL_MS` para capturar mais rápido (consome mais CPU).
* **Listagem**: aumente `MAX_VISIBLE` para ver mais itens de uma vez.
* **Estética**: ajuste tamanhos de janela e fontes à sua preferência.

---

## Arquitetura e funcionamento

**Visão geral:**

* **UI (Swing + FlatDarkLaf)**:

    * `JDialog` **sempre no topo**, **sem decorações**, posicionado próximo ao cursor.
    * Campo de **pesquisa** + `JList` com renderização própria (linha única + tooltip HTML monoespaçado).

* **Monitor de clipboard (pooling)**:

    * Agendado por `ScheduledExecutorService` (single-thread daemon).
    * Lê o clipboard do sistema a cada `POLL_MS`.
    * **Evita duplicatas** de pooling comparando com o último valor visto.

* **Histórico em memória**:

    * `ArrayDeque<Entry>` (mais novo primeiro), sem limite artificial.
    * Filtro case-insensitive por **substring** (stream com `limit(MAX_VISIBLE)`).

* **IPC local**:

    * Servidor `ServerSocket` em `127.0.0.1:SERVER_PORT`.
    * Comandos de texto (`TOGGLE`, `SHOW`, `HIDE`) aplicados na UI via `SwingUtilities.invokeLater(...)`.

**Threading:**

* **EDT**: toda manipulação de componentes Swing.
* **Thread do IPC**: aceita conexões e repassa comandos para o EDT.
* **Thread do monitor**: pooling periódico (daemon).

---

## Estrutura do código (visão por classe)

* **`JClipper` (classe principal / `main`)**

    * Sobe L&F, tenta iniciar servidor IPC (previne múltiplas instâncias),
    * Inicia `ClipboardMonitor` e cria `PopupUI`,
    * Processa `--toggle`/`--show`.

* **`ClipboardMonitor`**

    * Lê `Toolkit.getDefaultToolkit().getSystemClipboard()`,
    * Quando muda o texto, chama `ClipboardHistory.add(...)`.

* **`ClipboardHistory`**

    * Armazena entradas (`ts`, `text`) em memória,
    * `latestMatching(query, limit)` retorna os mais recentes filtrados.

* **`PopupUI`**

    * Monta o `JDialog` com cabeçalho, campo de busca e lista,
    * Listeners de teclado/mouse (ENTER copia, ESC fecha, clique fora fecha),
    * `SingleLineRenderer` mostra preview compacto (+ tooltip completo),
    * Métodos utilitários de posicionamento (no monitor do cursor).

---

## Dicas de produtividade

* **Atalho global do SO**: mapeie um hotkey (ex.: `Ctrl+Shift+V`) para executar
  `java -jar <caminho>/jclipper.jar --toggle`.
* **Shell alias**:

    * Bash/Zsh: `alias clipper='java -jar ~/apps/jclipper.jar --toggle'`
    * PowerShell: `Set-Alias clipper "java -jar C:\apps\jclipper.jar --toggle"`
* **Pré-visualização fiel**: use o **tooltip** para ver o texto original com quebras e indentação.

---

## Kubuntu (KDE Plasma): Atalho personalizado

No Kubuntu (KDE Plasma), um atalho global pode chamar o JClipper usando um **Command/URL**.
Em algumas configurações, **só funciona corretamente** ao envolver o comando em `sh -c`.
Exemplo **real** que funcionou:

```bash
sh -c '/usr/bin/java -jar /home/daniel/desenvolvimento/temporarios/jclipper.jar --toggle'
```

### Passo a passo

1. **Configurações do Sistema** → **Atalhos** (ou **Teclado** → **Atalhos**).
2. Vá em **Atalhos personalizados** (*Custom Shortcuts*).
3. **Adicionar** → **Comando/URL**.
4. No campo **Comando/URL**, cole:

   ```bash
   sh -c '/usr/bin/java -jar /caminho/para/jclipper.jar --toggle'
   ```
5. Defina o **atalho de teclado** (por exemplo, `Ctrl+Shift+V`) e **aplique**.

### Dicas

* Use **caminhos absolutos** para o `java` e para o `.jar`. Descubra o caminho do Java com:

  ```bash
  which java
  ```

  Se for `/usr/bin/java`, mantenha exatamente assim no comando.
* Se o caminho do JAR tiver espaços, **mantenha as aspas**:

  ```bash
  sh -c '/usr/bin/java -jar "/home/usuario/minha pasta/jclipper.jar" --toggle'
  ```
* O wrapper `sh -c '...'` garante que o Plasma execute o comando completo com argumentos (algumas versões do KDE falham se apontar direto para o binário sem shell).

---

## Solução de problemas

* **Nada acontece ao usar `--toggle`**

    * Verifique se a **instância principal está rodando**.
      Sem servidor IPC ativo, uma chamada com `--toggle` deve **inicializar** a app e abrir o popup.
    * Portas bloqueadas: confirme que **127.0.0.1:51515** está livre.

* **Conflito de porta 51515**

    * Altere `SERVER_PORT` no código e recompile **ou** encerre o processo que usa a porta.

* **Popup fora da tela ou em monitor diferente**

    * O posicionamento usa a localização do **mouse**.
      Traga o ponteiro para o monitor desejado antes de abrir.

* **Tema/Fonte estranhos**

    * O app define `defaultFont` via `UIManager` e propriedades do FlatLaf.
    * Ajuste as constantes de fonte ou experimente outro L&F.

* **Uso de CPU**

    * Reduza a frequência de pooling (aumente `POLL_MS`) se necessário.

* **Compatibilidade Java**

    * O `pom.xml` define **Java 25**. Se seu ambiente é 17/21, alinhe `maven.compiler.source/target` e recompile.

---

## Segurança e privacidade

* **Sem rede externa**: apenas escuta **loopback (127.0.0.1)** para IPC.
* **Somente memória**: o histórico **não é persistido** em disco.
* **Escopo**: lê apenas o **clipboard do sistema**; não executa ações em segundo plano além do pooling.

> Para maior privacidade, encerre a aplicação quando não estiver usando (encerra histórico e IPC).

---

## Licença

Defina uma licença para o projeto (ex.: MIT, Apache-2.0, GPL).
Exemplo de arquivo: `LICENSE` na raiz do repositório.

---

## Anexo: `pom.xml` (resumo)

* `maven-compiler-plugin`: `source/target = 25`.
* `flatlaf` gerenciado em `dependencyManagement` e usado como dependência.
* `maven-shade-plugin`:

    * Empacota tudo em **`target/jclipper.jar`**
    * Define `Main-Class: JClipper`
    * Remove `module-info.class` e assinaturas em `META-INF` para evitar conflitos.

---

**Pronto!** Compile, rode e vincule um atalho de teclado do seu SO para alternar o popup quando precisar colar algo recente com velocidade ⚡.
