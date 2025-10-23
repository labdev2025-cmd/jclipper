<p align="center">
  <img src="src/main/resources/icons/logo/jclipper-logo.png" alt="JClipper Logo" width="320" height="320">
</p>

# JClipper

Um **gerenciador leve de histórico da área de transferência** (clipboard) para desktop, escrito em Java + Swing com tema **FlatLaf**.
Monitora o clipboard continuamente, guarda cada novo texto copiado e mostra um **popup pesquisável** (sempre no topo) próximo ao mouse para você localizar e recolar rapidamente qualquer item recente.

---

## ⚡ Guia Rápido (3 passos)

1. **Obtenha o `jclipper.jar`**

    * **Opção A – Compilar:**

      ```bash
      mvn -q -DskipTests clean package
      ```

      O *fat jar* sai em `target/jclipper.jar`.
    * **Opção B – Release pronta:**
      Baixe o JAR pré-compilado na página **Releases** do repositório (quando disponível).

2. **Inicie a instância principal (uma vez):**

   ```bash
   java -jar /caminho/para/jclipper.jar
   ```

   Isso ativa o servidor IPC local e o monitor do clipboard.

3. **Crie um atalho global do sistema para “alternar” o popup** (recomendado: **SUPER/Windows + V**):

    * O comando de alternância é sempre:

      ```bash
      java -jar /caminho/para/jclipper.jar --toggle
      ```
    * **Linux (exemplo exato que você usa):**

      ```bash
      sh -c '/minha_pasta/java_25/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
      ```

      *Uso assim se você tiver outra maneira?* Sim—veja alternativas mais abaixo (GNOME, KDE/Plasma, wrapper `.desktop`, etc.).
    * **Windows 11** e **macOS**: veja instruções completas nas seções específicas logo adiante (há várias maneiras, inclusive para tentar **SUPER/Win + V**).

> 💡 Sobre **SUPER/Win + V**: no Windows, essa combinação é **reservada** pelo próprio sistema (abre o histórico nativo). É possível contornar com **AutoHotkey** ou remapeando com **PowerToys**, ou optar por **Win+Shift+V** / **Ctrl+Alt+V**. No Linux/macOS, você pode usar **Super/Command + V** desde que não conflite com outros atalhos.

---

## Sumário

* [Recursos](#recursos)
* [Requisitos](#requisitos)
* [Instalação e Build (Maven)](#instalação-e-build-maven)
* [Execução](#execução)
* [Criar Atalho Global (Linux/Windows/macOS)](#criar-atalho-global-linuxwindowsmacos)

    * [Linux (GNOME, KDE/Plasma, `.desktop`, alternativas)](#linux)
    * [Windows 11 (atalho clássico, AutoHotkey, PowerToys)](#windows-11)
    * [macOS (Shortcuts/Atalhos, Automator)](#macos)
* [Uso do Popup](#uso-do-popup)
* [IPC: controlar o popup por linha de comando](#ipc-controlar-o-popup-por-linha-de-comando)
* [Configuração rápida (constantes fáceis de ajustar)](#configuração-rápida-constantes-fáceis-de-ajustar)
* [Arquitetura e funcionamento](#arquitetura-e-funcionamento)
* [Estrutura do código (visão por classe)](#estrutura-do-código-visão-por-classe)
* [Dicas de produtividade](#dicas-de-produtividade)
* [Solução de problemas](#solução-de-problemas)
* [Segurança e privacidade](#segurança-e-privacidade)
* [Licença](#licença)
* [Anexo: `pom.xml` (resumo)](#anexo-pomxml-resumo)

---

## Recursos

* ✅ **Histórico automático** do clipboard (texto), com captura contínua.
* ✅ **Popup pesquisável** e leve, posicionado próximo ao cursor.
* ✅ **Filtro em tempo real** enquanto você digita.
* ✅ **ENTER** copia o item selecionado (texto **original**, preservando quebras e tabs).
* ✅ **Fechamento rápido** (ESC, clique fora).
* ✅ **Tema moderno (FlatDarkLaf)** e lista com **fonte monoespaçada**.
* ✅ **IPC local (porta 51515)** para alternar/abrir/fechar o popup a partir de novas invocações do app ou scripts.
* ✅ **Persistência opcional pronta no código**: histórico salvo em arquivo (veja [Segurança e privacidade](#segurança-e-privacidade)).

---

## Requisitos

* **JDK 25** (conforme `maven.compiler.source/target` no `pom.xml`).

  > Dica: se quiser usar um LTS como **Java 21**, ajuste `<maven.compiler.source>` e `<maven.compiler.target>` para `21` no `pom.xml` e recompile.
* **Maven 3.9+**
* Ambiente gráfico (desktop) com acesso ao clipboard do sistema.

Dependências empacotadas no *fat jar* (via Shade):

* `com.formdev:flatlaf:3.6.2`
* `com.formdev:flatlaf-extras:3.6.2`

---

## Instalação e Build (Maven)

Estrutura recomendada:

```
src/
  main/
    java/
      JClipper.java
pom.xml
```

Build:

```bash
mvn -q -DskipTests clean package
```

Saída:

```
target/jclipper.jar
```

Esse JAR inclui dependências e `Main-Class: JClipper` no manifesto.

---

## Execução

### Primeira execução (instância principal)

```bash
java -jar /caminho/para/jclipper.jar
```

Isso sobe a UI, inicia o **servidor IPC** (localhost:51515) e o **monitor** do clipboard.

### Alternar popup (a partir de uma segunda invocação)

Se o app já estiver rodando:

```bash
java -jar /caminho/para/jclipper.jar --toggle
```

Se **não** houver instância rodando, este comando **inicia** a app e **abre** o popup.

---

## Criar Atalho Global (Linux/Windows/macOS)

A ideia é disparar **sempre o mesmo comando**:

```bash
java -jar "/caminho/para/jclipper.jar" --toggle
```

### Linux

#### GNOME (Configurações → Teclado → Atalhos → Atalhos personalizados)

1. **Nome:** `JClipper Toggle`
2. **Comando:**
   Se você precisa invocar um Java específico e/ou tem espaços no caminho, **envolva com `sh -c`** (funciona muito bem):

   ```bash
   sh -c '/minha_pasta/java_25/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
   ```

   Alternativas:

    * Se o Java “certo” já está no PATH:

      ```bash
      sh -c 'java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
      ```
    * Usando `$JAVA_HOME`:

      ```bash
      sh -c '"$JAVA_HOME/bin/java" -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
      ```
3. **Atalho:** pressione **Super+V** (ou **Super+Shift+V** para evitar conflitos).

> Por que `sh -c`? Alguns desktops precisam do shell para interpretar corretamente aspas, espaços e PATH.

#### KDE Plasma (Kubuntu)

1. **Configurações do Sistema** → **Atalhos** → **Atalhos personalizados**.
2. **Adicionar** → **Comando/URL**.
3. **Comando/URL** (use caminhos absolutos):

   ```bash
   sh -c '/usr/bin/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
   ```
4. Defina o atalho (recomendado **Meta/Super + V**) e aplique.

#### Arquivo `.desktop` (opcional)

Crie `~/.local/share/applications/jclipper-toggle.desktop`:

```ini
[Desktop Entry]
Type=Application
Name=JClipper Toggle
Exec=sh -c '/usr/bin/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
Terminal=false
```

Depois associe um atalho pelo gerenciador de janelas do seu ambiente.

---

### Windows 11

> ⚠️ **SUPER/Win + V** é reservado ao histórico nativo do Windows.
> Opções:
>
> * Usar **Ctrl+Alt+V** (atalho clássico de atalhos do Windows);
> * Usar **Win+Shift+V** com **AutoHotkey** ou **PowerToys Keyboard Manager**;
> * Desativar o histórico nativo do Windows (não recomendado para a maioria) e remapear **Win+V** via AHK/PowerToys.

#### Opção A — Atalho clássico (sem apps extras)

1. Clique direito → **Novo** → **Atalho**.
2. **Destino (Target):**

   ```text
   "C:\caminho\para\java.exe" -jar "C:\caminho\para\jclipper.jar" --toggle
   ```
3. **Propriedades** → **Tecla de atalho (Shortcut key):** `Ctrl+Alt+V`.
   (O Windows limita a combinação a Ctrl+Alt+letra; não aceita Win/Super aqui.)

Dica: Crie um `.bat` para simplificar:

```bat
@echo off
"C:\caminho\para\java.exe" -jar "C:\caminho\para\jclipper.jar" --toggle
```

Aponte o atalho para esse `.bat`.

#### Opção B — AutoHotkey (para usar **Win+Shift+V** ou tentar **Win+V**)

1. Instale **AutoHotkey v2**.
2. Crie um script `jclipper.ahk` com o conteúdo:

```ahk
#Requires AutoHotkey v2
#SingleInstance Force

; Win+Shift+V
#+v::{
    Run '"C:\caminho\para\java.exe" -jar "C:\caminho\para\jclipper.jar" --toggle', , "Hide"
}

; (Opcional) Tentar Win+V — pode conflitar com o Windows:
; #v::{
;     Run '"C:\caminho\para\java.exe" -jar "C:\caminho\para\jclipper.jar" --toggle', , "Hide"
; }
```

3. Execute o script (adicione à inicialização do Windows se quiser).

#### Opção C — PowerToys Keyboard Manager

Remapeie uma combinação (por ex. **Win+Shift+V**) para disparar um atalho que chame seu `.bat`.
Não remapeia diretamente para executar programas, então a abordagem mais prática é: **tecla → atalho** e o **atalho** aponta para o `.bat`.

---

### macOS

#### Opção A — Shortcuts (Atalhos) *com atalho global nativo*

1. Abra **Atalhos** (Shortcuts) → **Todos os Atalhos** → **+** → **Novo Atalho**.
2. Adicione a ação **Executar Script do Shell** (*Run Shell Script*).
3. Script (use zsh e caminhos absolutos):

   ```bash
   /usr/bin/java -jar "/Users/seuusuario/caminho/jclipper.jar" --toggle
   ```
4. Clique no ícone de **informações** do atalho e **adicione um Atalho de Teclado** (ex.: **⌘⌥V**).
5. (Opcional) Marque **Fixar na Barra de Menus** para acesso rápido.

#### Opção B — Automator (Ação Rápida / Quick Action)

1. Abra **Automator** → **Nova Ação Rápida**.
2. “O fluxo de trabalho recebe” → **não recebe entrada** (“nenhuma entrada”).
3. Adicione **Executar Shell Script** com:

   ```bash
   /usr/bin/java -jar "/Users/seuusuario/caminho/jclipper.jar" --toggle
   ```
4. Salve como “JClipper Toggle”.
5. Vá em **Ajustes do Sistema → Teclado → Atalhos → Serviços** (ou **Ações Rápidas**) e atribua um atalho (ex.: **⌘⌥V**).

> Dica: Evite **⌘V** puro (colagem padrão do macOS). **⌘⌥V** ou **⌃⌥⌘V** são boas escolhas.

---

## Uso do Popup

* **Abrir**: pelo atalho global (ou com `--toggle` / `--show`).
* **Pesquisar**: digite; a lista filtra **em tempo real**.
* **Selecionar e copiar**: setas **↑/↓** e **ENTER** (o texto **original**, com quebras/tabs, vai para o clipboard).
* **Fechar**: **ESC** ou clique fora da janela.
* **Visualização**:

    * Cada item aparece **em uma linha**, substituindo visualmente `\n`→`⏎` e `\t`→`⇥` (o texto real não é alterado).

---

## IPC: controlar o popup por linha de comando

O app escuta **127.0.0.1:51515**. Comandos aceitos:

* `TOGGLE` – alterna visibilidade do popup
* `SHOW`   – mostra o popup
* `HIDE`   – oculta o popup

### Exemplos

**Linux/macOS (`nc`/`netcat`):**

```bash
printf "TOGGLE\n" | nc 127.0.0.1 51515
printf "SHOW\n"   | nc 127.0.0.1 51515
printf "HIDE\n"   | nc 127.0.0.1 51515
```

**Windows PowerShell:**

```powershell
$client = New-Object System.Net.Sockets.TcpClient("127.0.0.1", 51515)
$stream = $client.GetStream()
$writer = New-Object System.IO.StreamWriter($stream)
$writer.WriteLine("TOGGLE"); $writer.Flush()
$client.Close()
```

---

## Configuração rápida (constantes fáceis de ajustar)

Na classe principal:

```java
// ======= Config gerais =======
private static final int SERVER_PORT = 51515; // IPC local
private static final int POLL_MS = 300;       // período do monitor do clipboard (ms)
private static final int MAX_VISIBLE = 20;    // itens exibidos no popup

// ======= Ajustes visuais =======
private static final int WINDOW_WIDTH = 650;
private static final int WINDOW_HEIGHT = 540;

private static final int FONT_BASE_PT   = 14;  // fonte padrão da UI
private static final int FONT_MONO_PT   = 14;  // fonte monoespaçada (lista)
private static final int HEADER_FONT_PT = 16;  // fonte do cabeçalho
private static final int TOOLTIP_FONT_PT = 13; // (mantida p/ compat)
private static final int LIST_CELL_HEIGHT = 24;
private static final int WINDOW_ARC = 16;
```

* **Responsividade:** reduza `POLL_MS` para captar mais rápido (↑ CPU).
* **Listagem:** aumente `MAX_VISIBLE` para ver mais resultados.
* **Estética:** ajuste dimensões e fontes conforme seu gosto.

---

## Arquitetura e funcionamento

**UI (Swing + FlatLaf)**
`JDialog` sem decorações, **sempre no topo**, próximo ao cursor. Campo de busca + `JList` com **renderer** em linha única.

**Monitor do clipboard (polling)**
`ScheduledExecutorService` (single-thread daemon) lendo o clipboard a cada `POLL_MS`. Evita duplicatas comparando com **último valor visto**.

**Histórico**
`ArrayDeque<Entry>` (mais novo primeiro). Filtro **case-insensitive** por substring, limitado a `MAX_VISIBLE`.

**IPC local**
`ServerSocket` em `127.0.0.1:SERVER_PORT` aceita `TOGGLE/SHOW/HIDE` e executa ações na UI via `SwingUtilities.invokeLater(...)`.

**Threading**

* **EDT** para tudo da UI;
* **Thread IPC** para rede local;
* **Thread do monitor** para polling.

---

## Estrutura do código (visão por classe)

* **`JClipper` (main)**
  Sobe L&F, tenta iniciar servidor IPC (evita múltiplas instâncias), inicia `ClipboardMonitor`, cria `PopupUI`, processa `--toggle`/`--show`.

* **`ClipboardMonitor`**
  Lê `Toolkit.getDefaultToolkit().getSystemClipboard()`; quando o texto muda, chama `ClipboardHistory.add(...)`.

* **`ClipboardHistory`**
  Armazena (`ts`, `text`) em memória; `latestMatching(query, limit)` retorna os mais recentes filtrados.

* **`PopupUI`**
  Constrói a janela, trata **ENTER/ESC**, clique fora, filtragem reativa, e copia **o texto original** ao selecionar.

* **`HistoryIO`**
  Resolve o caminho do arquivo do histórico conforme o SO; salva/carrega de forma assíncrona e segura (tmp + move).

* **`TimeFmt`**
  Converte timestamps para rótulos “amigáveis” em pt-BR (ex.: “agora”, “há 1 minuto”, “hoje 14:03”, etc.).

---

## Dicas de produtividade

* **Atalho global do SO**: mapeie um hotkey (ex.: **Super+V** ou **Win+Shift+V**) para:

  ```bash
  java -jar "/caminho/jclipper.jar" --toggle
  ```
* **Aliases**:

  Bash/Zsh:

  ```bash
  alias jclip='java -jar "/caminho/jclipper.jar" --toggle'
  ```

  PowerShell:

  ```powershell
  Set-Alias jclip 'C:\caminho\jclipper-toggle.bat'
  ```

---

## Solução de problemas

* **`--toggle` não faz nada**

    * Verifique se a **instância principal** está rodando (rode `java -jar ...` sem flags uma vez).
    * Confirme que **127.0.0.1:51515** está livre.

* **Conflito na porta 51515**

    * Mude `SERVER_PORT` no código e recompile, ou encerre o processo que ocupa a porta.

* **Popup em outro monitor**

    * O posicionamento usa o **mouse**. Leve o cursor ao monitor desejado antes de abrir.

* **Tema/Fonte “estranhos”**

    * Ajuste as constantes de fonte ou troque o L&F se preferir.

* **Uso de CPU**

    * Aumente `POLL_MS` (menos pooling).

* **Windows: Win+V não aciona o JClipper**

    * É atalho do próprio Windows. Use **AutoHotkey**/**PowerToys** (ver seção) ou altere para **Win+Shift+V** / **Ctrl+Alt+V**.

---

## Segurança e privacidade

* **Rede:** escuta apenas **loopback (127.0.0.1)** para IPC. Nenhuma conexão externa.

* **Persistência:** por padrão, o histórico é **persistido** em arquivo texto (uma linha por item em **Base64** + timestamp em ms).
  Caminhos:

    * **Windows:** `%APPDATA%\JClipper\history.txt`
    * **macOS:** `~/Library/Application Support/JClipper/history.txt`
    * **Linux:** `~/.local/share/JClipper/history.txt`

  Você pode **limpar** pelo botão “Limpar histórico” no popup, ou apagar manualmente o arquivo.
  Se preferir **não persistir**, remova/ajuste as chamadas de `HistoryIO.saveAsync(...)` no código e recompile.

* **Escopo:** lê apenas o **clipboard do sistema**. Não coleta nem envia dados.

---

## Licença

Este projeto é licenciado sob a **Licença MIT** (arquivo `LICENSE`).
Você pode usar, copiar, modificar e distribuir livremente, mantendo o aviso de copyright.

---

## Anexo: `pom.xml` (resumo)

* `maven-compiler-plugin`: `source/target = 25` (ajuste para 21 se preferir LTS).
* `dependencyManagement`: FlatLaf/Extras em `3.6.2`.
* `maven-shade-plugin`:

    * Empacota tudo em **`target/jclipper.jar`**
    * Define `Main-Class: JClipper`
    * Exclui `module-info.class` e assinaturas em `META-INF` para evitar conflitos.

---

### Apêndice — Outras maneiras de chamar o Java no Linux

Além do `sh -c '/minha_pasta/java_25/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'`, você pode:

1. **Usar `/usr/bin/java`** se a versão correta já estiver instalada globalmente:

```bash
sh -c '/usr/bin/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
```

2. **Referenciar `$JAVA_HOME`** (útil com SDKMAN/ASDF):

```bash
sh -c '"$JAVA_HOME/bin/java" -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle'
```

3. **Criar um script wrapper** `~/bin/jclipper-toggle` (não esqueça `chmod +x`):

```bash
#!/usr/bin/env bash
exec /minha_pasta/java_25/java -jar "/home/usuario/minha_pasta/jclipper.jar" --toggle
```

Depois, aponte o atalho para `sh -c '~/bin/jclipper-toggle'`.
