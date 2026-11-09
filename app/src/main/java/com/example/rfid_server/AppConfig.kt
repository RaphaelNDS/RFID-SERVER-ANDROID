package com.example.rfid_server

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.example.rfid_server.dados.EsperadoGuardado
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

object AppConfig {

    // Fica fora do backup do Android (res/xml/backup_rules.xml e data_extraction_rules.xml)
    // porque guarda a chave do coletor. Se mudar este nome, mude também nesses dois arquivos.
    private const val PREFS = "rfid_prefs"
    private const val TAG = "CONFIG"
    private const val KEY_SERVER_IP = "server_ip"
    private const val KEY_CHAVE_DISPOSITIVO = "chave_dispositivo"
    private const val KEY_LOCAIS = "locais_inventario"
    private const val KEY_CATALOGO = "catalogo_json"
    // Sem IP padrão: antes ficava aqui o IP da máquina de desenvolvimento (182.17.10.x), e um coletor novo
    // tentava falar com ele. Agora o coletor só conversa com o servidor depois de configurado.

    fun saveServerIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_SERVER_IP, ip) }
    }

    fun getServerIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, "").orEmpty().trim()
    }

    // ================= SEGREDOS CIFRADOS =================
    // A chave do coletor e o token da sessão ficam cifrados no arquivo de preferências (ver SegredoGuardado).

    /** Nos testes é trocada por uma falsa: o Keystore do Android não existe no computador. */
    @Volatile
    internal var cifra: Cifra = CifraKeystore

    // Texto guardado → valor decifrado. A chave é lida a cada requisição e o token a cada leitura de etiqueta:
    // decifrar no Keystore toda vez seria lento. Poucas entradas (uma chave e um token por login)
    private val decifrados = ConcurrentHashMap<String, String>()
    // Textos que o Keystore não conseguiu decifrar (chave perdida): sem isso cada leitura de etiqueta e cada requisição
    // refazia a tentativa no Keystore e enchia o log com o mesmo erro. Some ao gravar um valor novo (outro texto)
    private val indecifraveis: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private fun guardarSegredo(valor: String, nome: String): String =
        SegredoGuardado.paraGuardar(valor, cifra, nome).also { if (valor.isNotEmpty()) decifrados[it] = valor }

    private fun lerSegredo(context: Context, chavePrefs: String, nome: String): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val guardado = prefs.getString(chavePrefs, null) ?: return null
        decifrados[guardado]?.let { return it }
        if (guardado in indecifraveis) return null

        val lido = SegredoGuardado.ler(guardado, cifra, nome)
        val valor = lido.valor ?: run {
            indecifraveis += guardado
            return null
        }
        decifrados[guardado] = valor

        // Guardado sem cifra por uma versão anterior do app: regrava cifrado (uma vez só)
        if (lido.regravar) {
            val novo = guardarSegredo(valor, nome)
            if (novo != guardado) regravarSeIgual(context, chavePrefs, guardado, novo, nome)
        }
        return valor
    }

    /**
     * Troca o texto puro antigo pelo cifrado só se ninguém mudou o valor nesse meio-tempo (uma chave nova salva nas
     * Configurações, um login ou uma saída em outra thread): senão o valor antigo voltaria por cima do novo.
     * Quem grava a chave e a sessão também passa por esta trava (ver [gravarSegredos]).
     */
    private fun regravarSeIgual(context: Context, chavePrefs: String, guardado: String, novo: String, nome: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(travaSegredos) {
            if (prefs.getString(chavePrefs, null) != guardado) {
                Log.i(TAG, "$nome mudou enquanto era cifrado: o valor novo fica como está (sem regravar o antigo)")
                return
            }
            prefs.edit { putString(chavePrefs, novo) }
        }
        Log.i(TAG, "$nome guardado sem cifra por versão anterior do app: regravado cifrado")
    }

    private val travaSegredos = Any()

    /** Grava a chave ou a sessão sob a mesma trava da regravação (ver [regravarSeIgual]). */
    private fun gravarSegredos(context: Context, bloco: SharedPreferences.Editor.() -> Unit) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        synchronized(travaSegredos) { prefs.edit(action = bloco) }
    }

    // Chave cadastrada no servidor (rfid.dispositivos.chaves) para este coletor
    fun saveChaveDispositivo(context: Context, chave: String) {
        val guardada = guardarSegredo(chave, "a chave do coletor")
        gravarSegredos(context) { putString(KEY_CHAVE_DISPOSITIVO, guardada) }
    }

    fun getChaveDispositivo(context: Context): String =
        lerSegredo(context, KEY_CHAVE_DISPOSITIVO, "a chave do coletor").orEmpty()

    // Locais de inventário conhecidos (vindos do servidor ou digitados aqui): sugestões mesmo sem rede
    fun saveLocais(context: Context, locais: Collection<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putStringSet(KEY_LOCAIS, locais.toSet()) }
    }

    fun getLocais(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LOCAIS, emptySet()).orEmpty().sortedBy { it.lowercase() }
    }

    // Catálogo de tipo/marca/modelo (JSON de /api/android/catalogo): formulário de cadastro offline
    fun saveCatalogoJson(context: Context, json: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_CATALOGO, json) }
    }

    /** null se o catálogo nunca foi baixado neste coletor. */
    fun getCatalogoJson(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CATALOGO, null)
    }

    // Sessão do operador logado (token + permissões). Use SessaoAtual, que já trata a expiração
    fun saveSessao(context: Context, sessao: SessaoOperador) {
        val token = guardarSegredo(sessao.token, "o token da sessão")
        gravarSegredos(context) {
            putString(KEY_SESSAO_TOKEN, token)
            putLong(KEY_SESSAO_EXPIRA, sessao.expiraEm)
            putString(KEY_SESSAO_NOME, sessao.nome)
            putString(KEY_SESSAO_EMAIL, sessao.email)
            putBoolean(KEY_SESSAO_LER, sessao.podeLer)
            putBoolean(KEY_SESSAO_CADASTRAR, sessao.podeCadastrar)
            // Início pelo relógio e pelo tempo desde a ligação: atrasar o relógio não estende a sessão (SessaoOperador.validaEm)
            putLong(KEY_SESSAO_ENTROU_EM, sessao.entrouEm ?: System.currentTimeMillis())
            putLong(KEY_SESSAO_ENTROU_DECORRIDO, sessao.entrouDecorrido ?: SystemClock.elapsedRealtime())
            putString(KEY_ULTIMO_EMAIL, sessao.email)
            // Entrou de novo: o motivo do fim da sessão anterior já não interessa
            removerMotivoFim()
        }
    }

    fun getSessao(context: Context): SessaoOperador? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Token que não dá para decifrar: sem sessão (o operador entra de novo)
        val token = lerSegredo(context, KEY_SESSAO_TOKEN, "o token da sessão") ?: return null
        return SessaoOperador(
            token = token,
            expiraEm = prefs.getLong(KEY_SESSAO_EXPIRA, 0),
            nome = prefs.getString(KEY_SESSAO_NOME, "").orEmpty(),
            email = prefs.getString(KEY_SESSAO_EMAIL, "").orEmpty(),
            podeLer = prefs.getBoolean(KEY_SESSAO_LER, false),
            podeCadastrar = prefs.getBoolean(KEY_SESSAO_CADASTRAR, false),
            entrouEm = prefs.getLong(KEY_SESSAO_ENTROU_EM, -1L).takeIf { it >= 0 },
            entrouDecorrido = prefs.getLong(KEY_SESSAO_ENTROU_DECORRIDO, -1L).takeIf { it >= 0 }
        )
    }

    fun limparSessao(context: Context) {
        gravarSegredos(context) {
            remove(KEY_SESSAO_TOKEN).remove(KEY_SESSAO_EXPIRA).remove(KEY_SESSAO_NOME)
                .remove(KEY_SESSAO_EMAIL).remove(KEY_SESSAO_LER).remove(KEY_SESSAO_CADASTRAR)
                .remove(KEY_SESSAO_ENTROU_EM).remove(KEY_SESSAO_ENTROU_DECORRIDO)
        }
    }

    /**
     * O servidor recusou de vez a sessão [token] (ver FimDaSessaoAtual): apaga a sessão, só se a guardada ainda for
     * essa (um login novo feito nesse meio-tempo fica), e guarda o [motivoServidor] (ex.: "DESATIVADO"), o email do
     * operador e a hora, para a tela de login mostrar uma vez de quem era a sessão. Devolve true se apagou.
     */
    fun encerrarSessaoRecusada(
        context: Context,
        token: String,
        motivoServidor: String,
        agora: Long = System.currentTimeMillis()
    ): Boolean {
        synchronized(travaSegredos) {
            if (lerSegredo(context, KEY_SESSAO_TOKEN, "o token da sessão") != token) return false
            val email = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SESSAO_EMAIL, "").orEmpty()
            gravarSegredos(context) {
                remove(KEY_SESSAO_TOKEN).remove(KEY_SESSAO_EXPIRA).remove(KEY_SESSAO_NOME)
                    .remove(KEY_SESSAO_EMAIL).remove(KEY_SESSAO_LER).remove(KEY_SESSAO_CADASTRAR)
                .remove(KEY_SESSAO_ENTROU_EM).remove(KEY_SESSAO_ENTROU_DECORRIDO)
                putString(KEY_SESSAO_MOTIVO_FIM, motivoServidor)
                putString(KEY_SESSAO_MOTIVO_FIM_EMAIL, email)
                putLong(KEY_SESSAO_MOTIVO_FIM_EM, agora)
            }
        }
        return true
    }

    /** Fim de sessão guardado por [encerrarSessaoRecusada]: o motivo do servidor, de quem era a sessão e quando. */
    data class FimSessao(val motivoServidor: String, val email: String, val em: Long)

    /** Depois disto o motivo do fim da sessão não é mais mostrado no login (a sessão dura 12 h). */
    const val VALIDADE_MOTIVO_FIM_MS = 12L * 60 * 60 * 1000

    /**
     * Fim de sessão guardado por [encerrarSessaoRecusada], ou null. Apaga depois de ler: a tela de login mostra uma vez
     * só. Com mais de [VALIDADE_MOTIVO_FIM_MS] (ou sem a hora, guardado por uma versão anterior) é descartado sem
     * mostrar: quem abre o login bem depois, talvez outro operador, não precisa do aviso da sessão de outra pessoa.
     */
    fun tirarMotivoFimSessao(context: Context, agora: Long = System.currentTimeMillis()): FimSessao? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val motivo = prefs.getString(KEY_SESSAO_MOTIVO_FIM, null)
        val email = prefs.getString(KEY_SESSAO_MOTIVO_FIM_EMAIL, "").orEmpty()
        val em = prefs.getLong(KEY_SESSAO_MOTIVO_FIM_EM, 0L)
        prefs.edit { removerMotivoFim() }
        if (motivo == null) return null
        if (em <= 0L || agora - em > VALIDADE_MOTIVO_FIM_MS) {
            Log.i(TAG, "Motivo do fim de uma sessão anterior ($motivo) descartado sem mostrar: guardado há mais de 12 h")
            return null
        }
        return FimSessao(motivo, email, em)
    }

    private fun SharedPreferences.Editor.removerMotivoFim(): SharedPreferences.Editor =
        remove(KEY_SESSAO_MOTIVO_FIM).remove(KEY_SESSAO_MOTIVO_FIM_EMAIL).remove(KEY_SESSAO_MOTIVO_FIM_EM)

    // Esperado de cada local (JSON de /api/android/inventarios/esperado): o inventário mostra o que falta
    // mesmo sem rede, com a última lista baixada. Fica em arquivo, um por local (EsperadoGuardado).
    fun saveEsperadoJson(context: Context, local: String, json: String, etag: String? = null) {
        esperadoGuardado(context).salvar(local, json, etag)
    }

    /** ETag da lista guardada (servidor 1.4.4 em diante): vai no If-None-Match, e 304 = a guardada ainda vale. */
    fun getEsperadoEtag(context: Context, local: String): String? = esperadoGuardado(context).lerEtag(local)

    fun saveEsperadoEtag(context: Context, local: String, etag: String) {
        esperadoGuardado(context).salvarEtag(local, etag)
    }

    /** null se o esperado deste local nunca foi baixado neste coletor. */
    fun getEsperadoJson(context: Context, local: String): String? = esperadoGuardado(context).ler(local)

    @Volatile
    private var esperadoMigrado = false

    /**
     * Até a versão 1.5.0 o esperado ficava no SharedPreferences (chaves "esperado_<local>"): na primeira vez,
     * passa para os arquivos e tira de lá, o que deixa o arquivo de configurações pequeno de novo.
     */
    @Synchronized
    private fun esperadoGuardado(context: Context): EsperadoGuardado {
        val guardado = EsperadoGuardado(File(context.filesDir, PASTA_ESPERADO))
        if (esperadoMigrado) return guardado

        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val antigas = prefs.all.filterKeys { it.startsWith(KEY_ESPERADO_PREFIXO) }
            .mapNotNull { (chave, valor) -> (valor as? String)?.let { chave.removePrefix(KEY_ESPERADO_PREFIXO) to it } }
            .toMap()
        if (antigas.isNotEmpty()) {
            val trazidas = guardado.importar(antigas)
            prefs.edit { antigas.keys.forEach { remove(KEY_ESPERADO_PREFIXO + it) } }
            Log.i("AppConfig", "Lista do esperado de $trazidas local(is) passada das configurações para arquivos")
        }
        esperadoMigrado = true
        return guardado
    }

    // Bip e vibração a cada etiqueta nova na leitura e no inventário (ligado por padrão)
    fun setAvisoLeitura(context: Context, ligado: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putBoolean(KEY_AVISO_LEITURA, ligado) }
    }

    fun isAvisoLeitura(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AVISO_LEITURA, true)
    }

    /** Email do último operador que entrou, para já vir preenchido na tela de login. */
    fun getUltimoEmail(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ULTIMO_EMAIL, "").orEmpty()
    }

    /** Endereço HTTPS do servidor, ou "" se o IP ainda não foi configurado neste coletor. */
    fun getBaseUrl(context: Context): String =
        getServerIp(context).takeIf { it.isNotBlank() }?.let(::montarEndereco).orEmpty()

    /** IP do servidor e chave do coletor informados nas Configurações (sem isso não dá nem para fazer login). */
    fun servidorConfigurado(context: Context): Boolean = getServerIp(context).isNotBlank()

    fun chaveConfigurada(context: Context): Boolean = getChaveDispositivo(context).isNotBlank()

    /**
     * A chave pode ir num header HTTP? Só caracteres ASCII visíveis e espaço: com acento ou espaço especial (colado de
     * outro lugar) o OkHttp lança exceção ao montar a requisição.
     */
    fun chaveValida(chave: String): Boolean = chave.all { it in ' '..'~' }

    /** O que foi digitado no campo do servidor vira um endereço utilizável? (IP, nome, IP:porta ou http://...) */
    fun enderecoValido(entrada: String): Boolean {
        if (entrada.isBlank() || entrada.trim().any { it.isWhitespace() }) return false
        val url = montarEndereco(entrada).toHttpUrlOrNull() ?: return false
        return url.host.isNotBlank() && url.encodedPath == "/"
    }

    /** Porta do HTTPS do servidor quando o endereço não informa outra (rfid.https.porta no servidor). */
    const val PORTA_HTTPS_PADRAO = 8443

    /**
     * Endereço do servidor sempre em HTTPS. Aceita "192.168.0.10", "192.168.0.10:9443" ou um endereço
     * colado com http:// (o esquema é trocado: o servidor não aceita mais a API por HTTP).
     */
    fun montarEndereco(entrada: String): String {
        val semEsquema = entrada.trim().removeSuffix("/").substringAfter("://")
        val temPorta = semEsquema.substringAfterLast(':', "").let { it.isNotEmpty() && it.all(Char::isDigit) }
        return "https://" + if (temPorta) semEsquema else "$semEsquema:$PORTA_HTTPS_PADRAO"
    }

    // Impressão digital SHA-256 do certificado do servidor, confirmada pelo responsável nas Configurações.
    // O app só aceita o servidor que apresentar exatamente este certificado.
    fun saveImpressaoDigital(context: Context, impressao: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit { putString(KEY_IMPRESSAO_DIGITAL, impressao.uppercase()) }
    }

    fun getImpressaoDigital(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IMPRESSAO_DIGITAL, null)
    }

    // PIN das Configurações (PinColetor): só o hash e o sal, mais o controle de tentativas erradas
    fun savePin(context: Context, hash: String, sal: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            putString(KEY_PIN_HASH, hash)
            putString(KEY_PIN_SAL, sal)
            remove(KEY_PIN_ERROS).remove(KEY_PIN_BLOQUEADO_ATE)
                .remove(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO).remove(KEY_PIN_BLOQUEIO_DURACAO)
        }
    }

    /** (hash, sal) do PIN, ou null se o PIN ainda não foi criado neste coletor. */
    fun getPin(context: Context): Pair<String, String>? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hash = prefs.getString(KEY_PIN_HASH, null) ?: return null
        val sal = prefs.getString(KEY_PIN_SAL, null) ?: return null
        return hash to sal
    }

    fun temPin(context: Context): Boolean = getPin(context) != null

    fun apagarPin(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit {
            remove(KEY_PIN_HASH).remove(KEY_PIN_SAL).remove(KEY_PIN_ERROS).remove(KEY_PIN_BLOQUEADO_ATE)
                .remove(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO).remove(KEY_PIN_BLOQUEIO_DURACAO)
        }
    }

    /** Erros e bloqueio do PIN. Gravado pela 1.6.1 ou antes: só erros e bloqueado_ate (os outros ficam desconhecidos). */
    fun getTentativasPin(context: Context): PinColetor.EstadoTentativas {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return PinColetor.EstadoTentativas(
            erros = prefs.getInt(KEY_PIN_ERROS, 0),
            bloqueadoAte = prefs.getLong(KEY_PIN_BLOQUEADO_ATE, 0),
            inicioDecorrido = prefs.getLong(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO, -1),
            duracao = prefs.getLong(KEY_PIN_BLOQUEIO_DURACAO, 0)
        )
    }

    /** commit() e não apply(): o erro precisa estar gravado mesmo se o app for fechado logo em seguida. */
    @SuppressLint("ApplySharedPref")
    fun saveTentativasPin(context: Context, estado: PinColetor.EstadoTentativas) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            putInt(KEY_PIN_ERROS, estado.erros)
            putLong(KEY_PIN_BLOQUEADO_ATE, estado.bloqueadoAte)
            putLong(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO, estado.inicioDecorrido)
            putLong(KEY_PIN_BLOQUEIO_DURACAO, estado.duracao)
        }
    }

    /** PIN certo: zera os erros e o bloqueio. */
    fun limparTentativasPin(context: Context) = saveTentativasPin(context, PinColetor.EstadoTentativas())

    /**
     * Apaga o bloqueio do PIN que já acabou (ver PinColetor.semBloqueioVencido), para ele não voltar no próximo
     * reinício do aparelho. Barato (só lê as preferências): chamado ao abrir as telas de leitura e ao pedir o PIN.
     */
    fun limparBloqueioPinVencido(context: Context) {
        val limpo = PinColetor.semBloqueioVencido(
            getTentativasPin(context), System.currentTimeMillis(), android.os.SystemClock.elapsedRealtime()
        ) ?: return
        saveTentativasPin(context, limpo)
        Log.i(TAG, "Bloqueio do PIN das Configurações já tinha acabado: apagado (os erros seguem contados)")
    }

    private const val KEY_PIN_HASH = "pin_hash"
    private const val KEY_PIN_SAL = "pin_sal"
    private const val KEY_PIN_ERROS = "pin_erros"
    private const val KEY_PIN_BLOQUEADO_ATE = "pin_bloqueado_ate"
    private const val KEY_PIN_BLOQUEIO_INICIO_DECORRIDO = "pin_bloqueio_inicio_decorrido"
    private const val KEY_PIN_BLOQUEIO_DURACAO = "pin_bloqueio_duracao"
    private const val KEY_IMPRESSAO_DIGITAL = "certificado_sha256"
    private const val KEY_SESSAO_TOKEN = "sessao_token"
    private const val KEY_SESSAO_EXPIRA = "sessao_expira_em"
    private const val KEY_SESSAO_NOME = "sessao_nome"
    private const val KEY_SESSAO_EMAIL = "sessao_email"
    private const val KEY_SESSAO_LER = "sessao_pode_ler"
    private const val KEY_SESSAO_CADASTRAR = "sessao_pode_cadastrar"
    private const val KEY_SESSAO_ENTROU_EM = "sessao_entrou_em"
    private const val KEY_SESSAO_ENTROU_DECORRIDO = "sessao_entrou_decorrido"
    private const val KEY_SESSAO_MOTIVO_FIM = "sessao_motivo_fim"
    private const val KEY_SESSAO_MOTIVO_FIM_EMAIL = "sessao_motivo_fim_email"
    private const val KEY_SESSAO_MOTIVO_FIM_EM = "sessao_motivo_fim_em"
    private const val KEY_ULTIMO_EMAIL = "ultimo_email"
    private const val KEY_AVISO_LEITURA = "aviso_leitura"
    /** Só para trazer o que a versão 1.5.0 e anteriores guardavam aqui (ver esperadoGuardado). */
    private const val KEY_ESPERADO_PREFIXO = "esperado_"
    private const val PASTA_ESPERADO = "esperado"

    // Potência do leitor em % da máxima (Configurações). 100 = máxima, como era fixo até a versão 1.5.0
    fun setPotenciaLeitor(context: Context, percentual: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putInt(KEY_POTENCIA_LEITOR, percentual.coerceIn(POTENCIA_MINIMA, 100))
        }
    }

    fun getPotenciaLeitor(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_POTENCIA_LEITOR, 100).coerceIn(POTENCIA_MINIMA, 100)

    private const val KEY_POTENCIA_LEITOR = "potencia_leitor"
    const val POTENCIA_MINIMA = 10

    // ================= FILTROS DA LEITURA (FiltroLeitura) =================

    /** RSSI mínimo em dBm das leituras do gatilho; null = sem limite (o padrão). */
    fun getRssiMinimo(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_RSSI_MINIMO, SEM_LIMITE).takeIf { it != SEM_LIMITE }

    fun setRssiMinimo(context: Context, dbm: Int?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_RSSI_MINIMO, dbm ?: SEM_LIMITE) }
    }

    /** Só etiquetas gravadas com o prefixo da empresa (FiltroLeitura.PREFIXO_EMPRESA). Desligado por padrão. */
    fun isSoEtiquetasEmpresa(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SO_EMPRESA, false)

    fun setSoEtiquetasEmpresa(context: Context, ligado: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SO_EMPRESA, ligado) }
    }

    private const val KEY_RSSI_MINIMO = "rssi_minimo"
    private const val KEY_SO_EMPRESA = "so_etiquetas_empresa"
    private const val SEM_LIMITE = Int.MIN_VALUE
}

