# RFID Android (coletor)

App Android dos coletores RFID Zebra. O operador faz login, lê etiquetas com o gatilho do coletor, localiza
equipamentos por patrimônio, faz inventários por local e (se tiver permissão) cadastra etiquetas. Tudo funciona
**offline**: o app grava primeiro no aparelho e envia ao servidor quando houver rede.

O servidor fica em outro repositório: **RFID-Server** (pasta `RFID_Servidor/rfid-server`), que tem o
README com a API, a segurança e a instalação do lado do servidor.

```
 Coletor (este app) ──HTTPS 8443──►  RFID Server
   banco local (Room)              chave do coletor + login do operador
   fila de envio                   certificado conferido pela impressão digital
```

| | |
|---|---|
| Pacote | `br.com.bkpbr.rfidandroid` |
| Versão | 1.3.0 (6) |
| Android | 8.0+ (minSdk 26, targetSdk 36) |
| Leitor | Zebra, SDK RFID API3 2.0.4 (arquivos `.aar` em `gradle/libs`) |
| Linguagem | Kotlin · Room · WorkManager · OkHttp |

---

## Sumário

1. [Telas](#telas)
2. [Login do operador](#login-do-operador)
3. [Offline e sincronização](#offline-e-sincronização)
4. [Leitor RFID](#leitor-rfid)
5. [Cadastro de etiqueta pelo coletor](#cadastro-de-etiqueta-pelo-coletor)
6. [Configuração do coletor](#configuração-do-coletor)
7. [Build, testes e release](#build-testes-e-release)
8. [Logs](#logs)
9. [Problemas comuns](#problemas-comuns)

---

## Telas

| Tela | O que faz |
|---|---|
| **Login** (tela inicial) | Email e senha do Sistema RFID. Botão "Configurações do coletor" para configurar antes do primeiro login: o app não tem IP de servidor padrão, e enquanto o IP e a chave não forem informados a tela avisa o que falta. |
| **Principal** | Abas **Leitura** e **Não cadastradas**. Contadores: Lidas · Cadastradas · Não cadastradas · Aguardando. Uma faixa laranja avisa quando há registros aguardando envio. Menu: Nova leitura, Configurações, Localizar patrimônio, Inventário por local, Sair. |
| **Localizar patrimônio** | Busca o equipamento pelo patrimônio e indica a proximidade pela força do sinal (RSSI), com bip e vibração. |
| **Inventário por local** | Escolhe ou digita o local (com sugestões dos locais já usados), inicia, lê com o gatilho e finaliza. |
| **Cadastrar etiqueta** | Ao tocar numa etiqueta não cadastrada: código interno (obrigatório), patrimônio, nº de série e tipo → marca → modelo em cascata. Avisa se o cadastro ficar incompleto. |
| **Configurações** | Endereço do servidor (IP ou IP:porta, conferido antes de salvar), chave do coletor, testar conexão e confirmar o certificado. |

---

## Login do operador

- **Obrigatório.** O operador entra com o email e a senha do usuário dele no Sistema RFID e usa **as permissões
  desse usuário**: Leituras para ler e inventariar, RFID · Criar para cadastrar.
- **Sessão de 12 h** (definida no servidor). Entrar exige rede; depois disso a sessão vale **mesmo offline**.
  Ao fim das 12 h o app volta para a tela de login. **Sair** encerra a sessão no servidor.
- **Aviso antes de acabar:** a tela principal mostra até que horas a sessão vale ("sessão até 18:30"). Nas telas de
  Leitura, Inventário e Localizar, o app avisa com 30 min e de novo com 5 min, e deixa **renovar** com a senha sem
  sair da tela (é um login novo: renovar exige rede). O que está na tela e o que ainda não foi enviado continuam:
  cada leitura guardada vai para o servidor com a sessão em que foi feita.
- O token da sessão fica nas preferências do app (fora do backup do Android).
- Cada leitura, inventário (quem abriu, quem leu cada etiqueta, quem finalizou) e cadastro guarda **quem fez**.
- Login bloqueado por excesso de senhas erradas (regra do servidor): o app mostra quanto tempo esperar.

---

## Offline e sincronização

Tudo é gravado primeiro no banco local (**Room**, `rfid-local.db`, versão 4; esquemas exportados em
`app/schemas` e migrações testadas contra eles). Nada é apagado antes da confirmação do servidor.

O **Sincronizador** envia em 3 etapas, nesta ordem:

1. **Cadastros** feitos no coletor;
2. **Leituras avulsas** — consulta a etiqueta e envia a leitura com a hora em que aconteceu, ou a registra como
   não cadastrada;
3. **Inventários** — abertura, etiquetas em lotes de 200 (agrupadas por sessão do operador) e finalização.

Quando sincroniza: na hora da leitura, a cada 1 min com o app aberto, ao salvar as Configurações e em segundo
plano pelo **WorkManager** quando a rede volta (tentativas a cada 30 s com espera crescente e checagem
periódica a cada 15 min).

Regras de erro:

- **Falha de conexão:** para tudo e tenta de novo depois (nada se perde).
- **Recusa de um item específico** (erro 4xx ou sessão inválida): marca só aquele item e segue com o resto da fila.
- Reenviar é seguro: o servidor não duplica (inventários têm id gerado no coletor).

Cada envio leva o operador (`X-Sessao-Operador`) e a **hora real da ação** (`X-Momento`). O servidor aceita se a
sessão valia naquele momento e se o envio chegou dentro do **prazo de sincronização** (72 h por padrão,
configurado no servidor). **Um coletor que fique mais tempo que isso sem rede perde o que estava na fila.**

---

## Leitor RFID

- `RfidManager` é um gerenciador único: conecta ao leitor Zebra em segundo plano.
- Só a tela visível recebe as leituras.
- A leitura só acontece com o **gatilho** do coletor.

---

## Cadastro de etiqueta pelo coletor

- Exige as duas coisas: o operador com permissão **RFID · Criar** **e** o coletor na lista
  `RFID_COLETORES_CADASTRO` do servidor.
- Funciona offline: o catálogo (tipos, marcas, modelos) fica guardado no aparelho.
- Se o servidor recusar um cadastro (ex.: etiqueta já cadastrada, marca de outro tipo), o item aparece na aba
  **Não cadastradas** para corrigir.
- Cadastro incompleto é aceito, mas a etiqueta só entra nas leituras e relatórios do servidor quando o cadastro
  estiver completo — por isso o app pede confirmação.

---

## Configuração do coletor

Feita na tela **Configurações** (acessível também pela tela de login):

1. **Endereço do servidor:** informe o IP (ou IP:porta). O app usa sempre `https://IP:8443`. Não há IP padrão:
   um coletor novo só fala com o servidor depois de configurado.
2. **Chave do coletor:** a mesma configurada para este coletor no servidor em `RFID_CHAVES_DISPOSITIVOS`
   (`nome:chave`). Vai no header `X-Chave-Dispositivo`.
3. **Salvar** (ou **Testar conexão**): no primeiro contato o app mostra o certificado do servidor para ser
   confirmado. Veja abaixo como fazer.

O tráfego sem criptografia (HTTP) está desligado no manifesto.

### Cadastrar (confirmar) o certificado do servidor

O servidor usa um certificado HTTPS próprio, e o app só conversa com ele depois que alguém confirma, uma vez por
coletor, que o certificado é mesmo o do servidor da empresa. Isso impede que outro computador na rede se passe pelo
servidor e receba as leituras, os cadastros e a senha dos operadores.

**1. Pegue a impressão digital no servidor** (64 caracteres hexadecimais em 32 pares, ex.: `63:0F:85:…:92:36`),
de uma destas formas:

- **Tela Status** do Sistema RFID (web), logado: campo da impressão digital SHA-256 do certificado HTTPS.
- **Log do servidor**, que mostra a digital a cada inicialização. No computador do servidor, na pasta de onde ele
  roda:
  ```powershell
  Select-String "SHA-256 do certificado HTTPS" .\data\logs\rfid-server.log | Select-Object -Last 1
  ```

Pegue a digital **no próprio servidor** (tela ou log), nunca de um e-mail ou mensagem que alguém mandou.

**2. Confirme no coletor:**

1. Em **Configurações**, informe o IP e a chave e toque em **Salvar**.
2. Aparece **"Confirmar o servidor"** com a impressão digital em 4 linhas de 8 pares.
3. Compare **todos** os pares com a do passo 1. Conferir só o começo e o fim não basta.
4. Se for igual, toque em **"É igual, confiar"**. Se tiver qualquer diferença, toque em **"Não confiar"** e avise
   o responsável pelo servidor.
5. A tela passa a mostrar *"Servidor: conectado com segurança (HTTPS) ✅"* e, embaixo, *"Certificado confirmado
   (SHA-256)"* com a digital guardada.

A partir daí o app **só aceita esse certificado**, mesmo que o IP do servidor mude.

**Quando aparece "Certificado do servidor mudou"**

O app mostra a digital nova e a anterior e recusa o servidor até alguém confirmar de novo. Isso é esperado quando o
certificado foi trocado de propósito: um servidor novo, um `.p12` apagado (o servidor gera outro) ou um backup de
outro servidor restaurado. Fora disso, **não confie**: pode ser outro computador se passando pelo servidor. Antes de
tocar em "É igual, confiar", compare a digital **nova** com a da tela Status do servidor, como no passo 1.

Para não precisar confirmar de novo em todos os coletores, guarde o certificado do servidor no backup
(`data/rfid-https.p12` e `data/rfid-https.p12.senha`; o backup automático do servidor já inclui os dois).

**Data, hora e fuso do aparelho:** confira nas configurações do Android antes de entregar o coletor (fuso da
região, por exemplo **America/Sao_Paulo**, com data e hora automáticas). O app mostra os horários (ex.: "sessão até
21:17") no fuso do aparelho; um MC3300x de fábrica pode vir em GMT e mostrar 3 horas a mais. O fuso só muda o que
aparece na tela: as leituras vão para o servidor com o fuso junto (`X-Momento`), então os horários gravados
ficam certos de qualquer forma.

---

## Build, testes e release

Requisitos: **JDK 17 ou 21**. O JDK embutido do Android Studio 25 é novo demais para o Gradle 8.13 — aponte o
Gradle para um JDK 17/21.

```bash
./gradlew :app:assembleDebug          # APK de desenvolvimento
./gradlew :app:testDebugUnitTest      # testes JVM (MockWebServer, inclusive HTTPS com okhttp-tls; DAOs falsos)
./gradlew :app:assembleRelease        # APK de produção, assinado
```

### APK de produção

Sai em `app/build/outputs/apk/release/RFID-Android-<versão>-release.apk`, **assinado** com a chave da empresa:

- R8 com minify e shrink; remove `Log.d`/`Log.v`; mantém `com.zebra.**`.
- **Guarde o `mapping.txt` de cada versão** (`app/build/outputs/mapping/release/`): sem ele não dá para ler os
  erros de produção. Copie para `releases/<versão>-codigo<código>/mapping.txt` (ex.: `releases/1.4.0-codigo22/`),
  porque o próximo build sobrescreve o original. A pasta `releases/` fica fora do git (cerca de 27 MB por versão):
  mantenha um backup dela junto com o da chave.

**Versão.** O `versionName` fica em `app/build.gradle.kts` (`versaoApp`) e é mudado à mão a cada entrega
(1.4.0 → 1.4.1 correção, 1.5.0 funcionalidade nova). O `versionCode` é o **número de commits do git** e sobe
sozinho, então o Android sempre aceita instalar por cima. Por isso o build precisa do histórico completo do git:
num clone raso (ex.: CI com `fetch-depth: 1`) o build para com mensagem, e dá para forçar com
`./gradlew assembleRelease -Prfid.codigoVersao=N`. A versão aparece na tela de login, nas Configurações e na
primeira linha do log.

**Assinatura.** A chave fica **fora do git**, em `keystore/rfid-android.jks`, com as senhas em
`keystore.properties` na raiz do projeto:

```properties
storeFile=keystore/rfid-android.jks
storePassword=...
keyAlias=rfid-android
keyPassword=...
```

Em vez do arquivo, dá para usar as variáveis `RFID_KEYSTORE_ARQUIVO`, `RFID_KEYSTORE_SENHA`, `RFID_KEYSTORE_ALIAS`
e `RFID_KEYSTORE_SENHA_CHAVE`. Sem nenhum dos dois, o build avisa e o APK de produção sai sem assinatura (o
Android não instala).

> **Guarde o `.jks` e o `keystore.properties` num lugar seguro fora do computador de build** (cofre de senhas,
> backup da empresa). O Android só instala uma atualização assinada com a **mesma** chave: se ela se perder, cada
> coletor precisa desinstalar o app (perdendo o que estiver guardado nele) e instalar de novo.

**Primeira instalação do APK assinado.** Os coletores que estão com o APK de desenvolvimento (assinado com a
chave de debug de quem fez o build) **não** aceitam o APK de produção por cima. Em cada um: confira na tela
principal que não há nada "aguardando envio", desinstale o app antigo e instale o novo. Depois disso, as
próximas versões instalam por cima normalmente.

### Atualização pelo servidor

Depois da primeira instalação do APK assinado, as versões seguintes chegam pelo servidor:

1. Gere o APK de produção (`./gradlew :app:assembleRelease`) e publique-o na tela **App dos coletores** do
   servidor (somente ADMIN).
2. Na tela de login (no máximo uma consulta a cada 30 min) ou em Configurações → **Procurar atualização do app**, o
   coletor oferece a versão nova, com o que mudou e o tamanho.
3. Na primeira vez, o Android pede para autorizar o app a instalar atualizações ("Permitir desta fonte").
4. O app baixa o APK, confere o SHA-256 informado pelo servidor e abre o instalador do Android; o operador confirma.
   O Android só aceita o APK assinado com a mesma chave do app instalado. A fila de leituras e as configurações
   continuam depois da atualização.

O servidor só publica o APK assinado com a chave da empresa e com código de versão maior que o publicado.

Observação: o **KSP2** está ligado no `gradle.properties` porque o KSP1 quebra o Room 2.8 ao ler o esquema.

Estrutura (`app/src/main/java/com/example/rfid_server`):

```
activities/ fragment/ view/ adapter/   telas
api/                                   cliente HTTP, certificado, chamadas ao servidor
dados/                                 banco local (Room), entidades e DAOs
sincronizacao/                         fila de envio e WorkManager
service/                               serviços de leitura/tag
RfidManager.kt                         conexão com o leitor Zebra
SessaoOperador.kt, AppConfig.kt        sessão do operador e configurações
```

---

## Logs

Em português, filtráveis no Logcat pelas tags:

`MAIN` · `LOGIN` · `SESSAO` · `LEITURAS` · `INVENTARIO` · `CADASTRO` · `SINCRONIZACAO` · `TAG_SERVICE` ·
`HTTP_CLIENT` · `CERTIFICADO` · `RfidManager` · `BANCO_LOCAL` · `ERROS_APP` · `ATUALIZACAO` · `APP`

**Erros enviados ao servidor.** Além do Logcat, o app guarda os travamentos e as falhas relevantes (envio recusado,
erro do servidor, leitor RFID que não conecta, atualização que não baixou) num arquivo próprio e envia ao servidor
quando há rede: aparecem na tela **Coletores → Erros** do servidor. Repetidos em sequência viram um registro com o
número de vezes; token, chave do coletor e senhas são mascarados antes de guardar. Ficam no máximo 200 guardados
no coletor enquanto não há rede.

---

## Problemas comuns

| Sintoma | O que fazer |
|---|---|
| "Chave do dispositivo inválida" / 401 em tudo | A chave do app difere da configurada no servidor (`RFID_CHAVES_DISPOSITIVOS`). |
| Erro dizendo para usar HTTPS (426) | Atualize o app/endereço: o servidor só aceita `https://…:8443`. |
| Alerta de certificado diferente | O certificado do servidor mudou. Confira a digital na tela Status do servidor antes de aceitar (ver [Cadastrar o certificado](#cadastrar-confirmar-o-certificado-do-servidor)). |
| Faixa laranja não some | Há itens aguardando envio: verifique a rede e o endereço. Itens recusados aparecem marcados e não travam a fila. |
| Leituras antigas recusadas | O coletor ficou offline mais que o prazo de sincronização do servidor (72 h por padrão). |
| "Coletor sem permissão para cadastrar" | Inclua o nome do coletor em `RFID_COLETORES_CADASTRO` no servidor (e o operador precisa de RFID · Criar). |
| Login pede para esperar | Bloqueio por senhas erradas: aguarde o tempo indicado. |
| Leitor não lê | Confira se o leitor Zebra está conectado/carregado; a leitura só acontece com o gatilho e só na tela visível. |
