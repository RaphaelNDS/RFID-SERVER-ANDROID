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
| **Login** (tela inicial) | Email e senha do Sistema RFID. Botão "Configurações do coletor" para configurar antes do primeiro login. |
| **Principal** | Abas **Leitura** e **Não cadastradas**. Contadores: Lidas · Cadastradas · Não cadastradas · Aguardando. Uma faixa laranja avisa quando há registros aguardando envio. Menu: Nova leitura, Configurações, Localizar patrimônio, Inventário por local, Sair. |
| **Localizar patrimônio** | Busca o equipamento pelo patrimônio e indica a proximidade pela força do sinal (RSSI), com bip e vibração. |
| **Inventário por local** | Escolhe ou digita o local (com sugestões dos locais já usados), inicia, lê com o gatilho e finaliza. |
| **Cadastrar etiqueta** | Ao tocar numa etiqueta não cadastrada: código interno (obrigatório), patrimônio, nº de série e tipo → marca → modelo em cascata. Avisa se o cadastro ficar incompleto. |
| **Configurações** | Endereço do servidor (IP ou IP:porta), chave do coletor, testar conexão e confirmar o certificado. |

---

## Login do operador

- **Obrigatório.** O operador entra com o email e a senha do usuário dele no Sistema RFID e usa **as permissões
  desse usuário**: Leituras para ler e inventariar, RFID · Criar para cadastrar.
- **Sessão de 12 h** (definida no servidor). Entrar exige rede; depois disso a sessão vale **mesmo offline**.
  Ao fim das 12 h o app volta para a tela de login. **Sair** encerra a sessão no servidor.
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

1. **Endereço do servidor:** informe o IP (ou IP:porta). O app usa sempre `https://IP:8443`.
2. **Chave do coletor:** a mesma configurada para este coletor no servidor em `RFID_CHAVES_DISPOSITIVOS`
   (`nome:chave`). Vai no header `X-Chave-Dispositivo`.
3. **Testar conexão:** no primeiro teste, o app mostra a **impressão digital SHA-256** do certificado do servidor.
   Compare com a que aparece na tela **Status** do servidor e confirme.
4. A partir daí o app **só aceita aquele certificado**. Se o certificado do servidor mudar, aparece um alerta com
   a digital nova e a antiga — confira com o responsável antes de aceitar.

O tráfego sem criptografia (HTTP) está desligado no manifesto.

---

## Build, testes e release

Requisitos: **JDK 17 ou 21**. O JDK embutido do Android Studio 25 é novo demais para o Gradle 8.13 — aponte o
Gradle para um JDK 17/21.

```bash
./gradlew :app:assembleDebug          # APK de desenvolvimento
./gradlew :app:testDebugUnitTest      # 130 testes JVM (MockWebServer, inclusive HTTPS com okhttp-tls; DAOs falsos)
./gradlew :app:assembleRelease        # APK de produção (não assinado)
```

Release:

- R8 com minify e shrink; remove `Log.d`/`Log.v`; mantém `com.zebra.**`.
- Gera `app-release-unsigned.apk`, que precisa ser **assinado com a keystore da empresa** (não fica no repositório).
- **Guarde o `mapping.txt` de cada versão** — sem ele não dá para ler os erros de produção.

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
`HTTP_CLIENT` · `CERTIFICADO` · `RfidManager` · `BANCO_LOCAL`

---

## Problemas comuns

| Sintoma | O que fazer |
|---|---|
| "Chave do dispositivo inválida" / 401 em tudo | A chave do app difere da configurada no servidor (`RFID_CHAVES_DISPOSITIVOS`). |
| Erro dizendo para usar HTTPS (426) | Atualize o app/endereço: o servidor só aceita `https://…:8443`. |
| Alerta de certificado diferente | O certificado do servidor mudou. Confira a digital na tela Status do servidor antes de aceitar. |
| Faixa laranja não some | Há itens aguardando envio: verifique a rede e o endereço. Itens recusados aparecem marcados e não travam a fila. |
| Leituras antigas recusadas | O coletor ficou offline mais que o prazo de sincronização do servidor (72 h por padrão). |
| "Coletor sem permissão para cadastrar" | Inclua o nome do coletor em `RFID_COLETORES_CADASTRO` no servidor (e o operador precisa de RFID · Criar). |
| Login pede para esperar | Bloqueio por senhas erradas: aguarde o tempo indicado. |
| Leitor não lê | Confira se o leitor Zebra está conectado/carregado; a leitura só acontece com o gatilho e só na tela visível. |
