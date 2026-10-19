import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

// ================= VERSÃO =================
// versionName: mude à mão a cada versão entregue (1.4.0 → 1.4.1 correção, 1.5.0 funcionalidade nova).
// versionCode: número de commits do git, então sobe sozinho a cada commit e o Android sempre aceita
// instalar por cima da versão anterior. Para forçar um valor: ./gradlew assembleRelease -Prfid.codigoVersao=N
val versaoApp = "1.4.0"

// Abaixo disto o Android recusa instalar por cima (a 1.3.0 instalada nos coletores tem versionCode 6)
val codigoVersaoMinimo = 7

val codigoVersaoInformado = providers.gradleProperty("rfid.codigoVersao").orNull?.toIntOrNull()
val commitsDoGit: Int? = try {
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull()
} catch (e: Exception) {
    null
}
val codigoVersao: Int = codigoVersaoInformado ?: commitsDoGit ?: codigoVersaoMinimo

if (codigoVersaoInformado == null && commitsDoGit != null && commitsDoGit < codigoVersaoMinimo) {
    // Ex.: clone raso (CI com fetch-depth 1) conta só o último commit
    throw GradleException("O git contou só $commitsDoGit commit(s), abaixo do código de versão mínimo $codigoVersaoMinimo " +
        "(clone raso?). Faça o clone completo ou informe -Prfid.codigoVersao=N")
}
if (codigoVersaoInformado == null && commitsDoGit == null) {
    logger.warn("AVISO: não foi possível contar os commits do git (git fora do PATH ou projeto sem .git). " +
        "Código de versão provisório $codigoVersao: no APK de produção informe -Prfid.codigoVersao=N")
}

// ================= ASSINATURA DO APK DE PRODUÇÃO =================
// Lida de keystore.properties na raiz do projeto (FORA do git) ou das variáveis de ambiente
// RFID_KEYSTORE_ARQUIVO, RFID_KEYSTORE_SENHA, RFID_KEYSTORE_ALIAS e RFID_KEYSTORE_SENHA_CHAVE.
// ATENÇÃO: sem esta chave não dá para atualizar o app nos coletores (o Android exige a mesma
// assinatura). Guarde o .jks e as senhas num lugar seguro FORA deste computador.
val propriedadesAssinatura = Properties().apply {
    val arquivo = rootProject.file("keystore.properties")
    if (arquivo.exists()) arquivo.inputStream().use { load(it) }
}

fun configAssinatura(chave: String, variavel: String): String? =
    propriedadesAssinatura.getProperty(chave)?.takeIf { it.isNotBlank() } ?: System.getenv(variavel)?.takeIf { it.isNotBlank() }

val arquivoChave = configAssinatura("storeFile", "RFID_KEYSTORE_ARQUIVO")?.let { rootProject.file(it) }
val senhaArquivoChave = configAssinatura("storePassword", "RFID_KEYSTORE_SENHA")
val aliasChave = configAssinatura("keyAlias", "RFID_KEYSTORE_ALIAS")
val senhaChave = configAssinatura("keyPassword", "RFID_KEYSTORE_SENHA_CHAVE") ?: senhaArquivoChave
val assinaturaConfigurada = arquivoChave?.isFile == true && senhaArquivoChave != null && aliasChave != null

if (!assinaturaConfigurada) {
    logger.warn("AVISO: assinatura de produção não configurada (keystore.properties ausente ou incompleto, " +
        "ou arquivo da chave não encontrado: ${arquivoChave ?: "não informado"}). " +
        "O APK de produção sairá SEM assinatura e o Android não instala. Veja \"APK de produção\" no README.")
}

android {
    namespace = "com.example.rfid_server"
    compileSdk = 36

    defaultConfig {
        // Identidade do app no Android. ATENÇÃO: mudar este valor faz o Android tratar como OUTRO app
        // (instala ao lado do antigo, sem os dados dele). Era "com.example.rfid_server" até a 1.2.x.
        // O namespace acima (pacote do código) é independente e não precisa mudar.
        applicationId = "br.com.bkpbr.rfidandroid"
        minSdk = 26
        targetSdk = 36
        versionCode = codigoVersao
        versionName = versaoApp

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.VERSION_NAME etc. no log de início do app
    }

    signingConfigs {
        if (assinaturaConfigurada) {
            create("producao") {
                storeFile = arquivoChave
                storePassword = senhaArquivoChave
                keyAlias = aliasChave
                keyPassword = senhaChave
            }
        }
    }

    buildTypes {
        release {
            if (assinaturaConfigurada) signingConfig = signingConfigs.getByName("producao")

            // R8: remove código não usado, encolhe e ofusca o APK. Regras em proguard-rules.pro
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    // Esquema do banco local (Room) de cada versão, versionado no git para conferir as migrações
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    testOptions {
        // Nos testes no computador, android.util.Log e afins retornam valor padrão em vez de lançar exceção
        unitTests.isReturnDefaultValues = true
    }
}

// Nome do APK com a versão (ex.: RFID-Android-1.4.0-release.apk), em vez de app-release.apk
android.applicationVariants.all {
    val variante = this
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "RFID-Android-${variante.versionName}-${variante.name}.apk"
    }
}

dependencies {

    // SDK do leitor Zebra. Caminho relativo ao projeto: funciona em qualquer máquina
    implementation(fileTree(rootProject.file("gradle/libs")) { include("*.aar", "*.jar") })
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)          // servidor HTTP falso para testar o TagService
    testImplementation(libs.okhttp.tls)                    // certificados de teste: MockWebServer em HTTPS
    testImplementation(libs.org.json)                      // org.json real: o do android.jar é só um esqueleto nos testes
    testImplementation(libs.androidx.arch.core.testing)    // LiveData síncrono nos testes do ViewModel
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)       // banco local: fila de leituras para o modo offline
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)       // envia as leituras pendentes em segundo plano quando a rede volta
}