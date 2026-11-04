package com.example.rfid_server

import android.content.Context
import android.util.Log
import com.example.rfid_server.dados.EsperadoGuardado
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File

object AppConfig {

    // Fica fora do backup do Android (res/xml/backup_rules.xml e data_extraction_rules.xml)
    // porque guarda a chave do coletor. Se mudar este nome, mude também nesses dois arquivos.
    private const val PREFS = "rfid_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val KEY_CHAVE_DISPOSITIVO = "chave_dispositivo"
    private const val KEY_LOCAIS = "locais_inventario"
    private const val KEY_CATALOGO = "catalogo_json"
    // Sem IP padrão: antes ficava aqui o IP da máquina de desenvolvimento (182.17.10.x), e um coletor novo
    // tentava falar com ele. Agora o coletor só conversa com o servidor depois de configurado.

    fun saveServerIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_IP, ip)
            .apply()
    }

    fun getServerIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, "").orEmpty().trim()
    }

    // Chave cadastrada no servidor (rfid.dispositivos.chaves) para este coletor
    fun saveChaveDispositivo(context: Context, chave: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CHAVE_DISPOSITIVO, chave)
            .apply()
    }

    fun getChaveDispositivo(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CHAVE_DISPOSITIVO, "") ?: ""
    }

    // Locais de inventário conhecidos (vindos do servidor ou digitados aqui): sugestões mesmo sem rede
    fun saveLocais(context: Context, locais: Collection<String>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putStringSet(KEY_LOCAIS, locais.toSet())
            .apply()
    }

    fun getLocais(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY_LOCAIS, emptySet()).orEmpty().sortedBy { it.lowercase() }
    }

    // Catálogo de tipo/marca/modelo (JSON de /api/android/catalogo): formulário de cadastro offline
    fun saveCatalogoJson(context: Context, json: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_CATALOGO, json)
            .apply()
    }

    /** null se o catálogo nunca foi baixado neste coletor. */
    fun getCatalogoJson(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CATALOGO, null)
    }

    // Sessão do operador logado (token + permissões). Use SessaoAtual, que já trata a expiração
    fun saveSessao(context: Context, sessao: SessaoOperador) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SESSAO_TOKEN, sessao.token)
            .putLong(KEY_SESSAO_EXPIRA, sessao.expiraEm)
            .putString(KEY_SESSAO_NOME, sessao.nome)
            .putString(KEY_SESSAO_EMAIL, sessao.email)
            .putBoolean(KEY_SESSAO_LER, sessao.podeLer)
            .putBoolean(KEY_SESSAO_CADASTRAR, sessao.podeCadastrar)
            .putString(KEY_ULTIMO_EMAIL, sessao.email)
            .apply()
    }

    fun getSessao(context: Context): SessaoOperador? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val token = prefs.getString(KEY_SESSAO_TOKEN, null) ?: return null
        return SessaoOperador(
            token = token,
            expiraEm = prefs.getLong(KEY_SESSAO_EXPIRA, 0),
            nome = prefs.getString(KEY_SESSAO_NOME, "").orEmpty(),
            email = prefs.getString(KEY_SESSAO_EMAIL, "").orEmpty(),
            podeLer = prefs.getBoolean(KEY_SESSAO_LER, false),
            podeCadastrar = prefs.getBoolean(KEY_SESSAO_CADASTRAR, false)
        )
    }

    fun limparSessao(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_SESSAO_TOKEN).remove(KEY_SESSAO_EXPIRA).remove(KEY_SESSAO_NOME)
            .remove(KEY_SESSAO_EMAIL).remove(KEY_SESSAO_LER).remove(KEY_SESSAO_CADASTRAR)
            .apply()
    }

    // Esperado de cada local (JSON de /api/android/inventarios/esperado): o inventário mostra o que falta
    // mesmo sem rede, com a última lista baixada. Fica em arquivo, um por local (EsperadoGuardado).
    fun saveEsperadoJson(context: Context, local: String, json: String) {
        esperadoGuardado(context).salvar(local, json)
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
            prefs.edit().apply { antigas.keys.forEach { remove(KEY_ESPERADO_PREFIXO + it) } }.apply()
            Log.i("AppConfig", "Lista do esperado de $trazidas local(is) passada das configurações para arquivos")
        }
        esperadoMigrado = true
        return guardado
    }

    // Bip e vibração a cada etiqueta nova na leitura e no inventário (ligado por padrão)
    fun setAvisoLeitura(context: Context, ligado: Boolean) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_AVISO_LEITURA, ligado)
            .apply()
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
        prefs.edit()
            .putString(KEY_IMPRESSAO_DIGITAL, impressao.uppercase())
            .apply()
    }

    fun getImpressaoDigital(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_IMPRESSAO_DIGITAL, null)
    }

    // PIN das Configurações (PinColetor): só o hash e o sal, mais o controle de tentativas erradas
    fun savePin(context: Context, hash: String, sal: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putString(KEY_PIN_SAL, sal)
            .remove(KEY_PIN_ERROS).remove(KEY_PIN_BLOQUEADO_ATE)
            .remove(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO).remove(KEY_PIN_BLOQUEIO_DURACAO)
            .apply()
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
        prefs.edit()
            .remove(KEY_PIN_HASH).remove(KEY_PIN_SAL).remove(KEY_PIN_ERROS).remove(KEY_PIN_BLOQUEADO_ATE)
            .remove(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO).remove(KEY_PIN_BLOQUEIO_DURACAO)
            .apply()
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
    fun saveTentativasPin(context: Context, estado: PinColetor.EstadoTentativas) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putInt(KEY_PIN_ERROS, estado.erros)
            .putLong(KEY_PIN_BLOQUEADO_ATE, estado.bloqueadoAte)
            .putLong(KEY_PIN_BLOQUEIO_INICIO_DECORRIDO, estado.inicioDecorrido)
            .putLong(KEY_PIN_BLOQUEIO_DURACAO, estado.duracao)
            .commit()
    }

    /** PIN certo: zera os erros e o bloqueio. */
    fun limparTentativasPin(context: Context) = saveTentativasPin(context, PinColetor.EstadoTentativas())

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
    private const val KEY_ULTIMO_EMAIL = "ultimo_email"
    private const val KEY_AVISO_LEITURA = "aviso_leitura"
    /** Só para trazer o que a versão 1.5.0 e anteriores guardavam aqui (ver esperadoGuardado). */
    private const val KEY_ESPERADO_PREFIXO = "esperado_"
    private const val PASTA_ESPERADO = "esperado"

    // Potência do leitor em % da máxima (Configurações). 100 = máxima, como era fixo até a versão 1.5.0
    fun setPotenciaLeitor(context: Context, percentual: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_POTENCIA_LEITOR, percentual.coerceIn(POTENCIA_MINIMA, 100))
            .apply()
    }

    fun getPotenciaLeitor(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_POTENCIA_LEITOR, 100).coerceIn(POTENCIA_MINIMA, 100)

    private const val KEY_POTENCIA_LEITOR = "potencia_leitor"
    const val POTENCIA_MINIMA = 10
}

