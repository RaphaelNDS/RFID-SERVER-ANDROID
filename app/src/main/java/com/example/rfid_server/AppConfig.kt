package com.example.rfid_server

import android.content.Context

object AppConfig {

    // Fica fora do backup do Android (res/xml/backup_rules.xml e data_extraction_rules.xml)
    // porque guarda a chave do coletor. Se mudar este nome, mude também nesses dois arquivos.
    private const val PREFS = "rfid_prefs"
    private const val KEY_SERVER_IP = "server_ip"
    private const val KEY_CHAVE_DISPOSITIVO = "chave_dispositivo"
    private const val KEY_LOCAIS = "locais_inventario"
    private const val KEY_CATALOGO = "catalogo_json"
    private const val DEFAULT_IP = "182.17.10.174"

    fun saveServerIp(context: Context, ip: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SERVER_IP, ip)
            .apply()
    }

    fun getServerIp(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_SERVER_IP, DEFAULT_IP) ?: DEFAULT_IP
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

    /** Email do último operador que entrou, para já vir preenchido na tela de login. */
    fun getUltimoEmail(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ULTIMO_EMAIL, "").orEmpty()
    }

    fun getBaseUrl(context: Context): String = montarEndereco(getServerIp(context))

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

    private const val KEY_IMPRESSAO_DIGITAL = "certificado_sha256"
    private const val KEY_SESSAO_TOKEN = "sessao_token"
    private const val KEY_SESSAO_EXPIRA = "sessao_expira_em"
    private const val KEY_SESSAO_NOME = "sessao_nome"
    private const val KEY_SESSAO_EMAIL = "sessao_email"
    private const val KEY_SESSAO_LER = "sessao_pode_ler"
    private const val KEY_SESSAO_CADASTRAR = "sessao_pode_cadastrar"
    private const val KEY_ULTIMO_EMAIL = "ultimo_email"
}

