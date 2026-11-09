import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
}

// ================= VERSÃO =================
// versionName: mude à mão a cada versão entregue (1.4.0 → 1.4.1 correção, 1.5.0 funcionalidade nova).
// versionCode: número de commits do git, então sobe sozinho a cada commit e o Android sempre aceita
// instalar por cima da versão anterior. Para forçar um valor: ./gradlew assembleRelease -Prfid.codigoVersao=N
val versaoApp = "1.7.3"

// Abaixo disto o Android recusa instalar por cima (a 1.3.0 instalada nos coletores tem versionCode 6)
val codigoVersaoMinimo = 7

// APK de produção pedido (assembleRelease, bundleRelease...): aí nada de valor provisório, o build falha com a
// explicação. No debug e nos testes os avisos continuam só avisos.
val pedidoRelease = gradle.startParameter.taskNames.any { it.contains("Release") || it.contains("release") }

// "build" e "assemble" também empacotam o APK de produção, sem "Release" no nome pedido: estes problemas são
// conferidos de novo quando o Gradle já sabe quais tarefas vão rodar (ver taskGraph.whenReady no fim do arquivo)
val problemasDoRelease = mutableListOf<String>()

val codigoVersaoInformado = providers.gradleProperty("rfid.codigoVersao").orNull?.toIntOrNull()

fun saidaDoGit(vararg argumentos: String): String? = try {
    providers.exec {
        commandLine("git", *argumentos)
        workingDir = rootDir
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { null }
} catch (e: Exception) {
    null
}

val commitsDoGit: Int? = saidaDoGit("rev-list", "--count", "HEAD")?.toIntOrNull()
// Clone raso (ex.: CI com fetch-depth 1): o git conta só parte dos commits e o código sairia baixo demais
val cloneRaso = commitsDoGit != null && saidaDoGit("rev-parse", "--is-shallow-repository") == "true"
val codigoVersao: Int = codigoVersaoInformado ?: commitsDoGit ?: codigoVersaoMinimo

if (codigoVersaoInformado == null && commitsDoGit != null && commitsDoGit < codigoVersaoMinimo) {
    throw GradleException("O git contou só $commitsDoGit commit(s), abaixo do código de versão mínimo $codigoVersaoMinimo " +
        "(clone raso?). Faça o clone completo ou informe -Prfid.codigoVersao=N")
}
if (codigoVersaoInformado == null && (commitsDoGit == null || cloneRaso)) {
    val motivo = if (commitsDoGit == null) "não foi possível contar os commits do git (git fora do PATH ou projeto sem .git)"
                 else "o projeto é um clone raso do git ($commitsDoGit commit(s) visíveis)"
    val problema = "APK de produção recusado: $motivo, então o código de versão sairia errado e o Android " +
        "poderia recusar a atualização. Gere com o git no PATH e o clone completo (git fetch --unshallow) ou informe -Prfid.codigoVersao=N"
    if (pedidoRelease) throw GradleException(problema)
    problemasDoRelease += problema
    logger.warn("AVISO: $motivo. Código de versão provisório $codigoVersao: no APK de produção informe -Prfid.codigoVersao=N")
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

// -Prfid.semAssinatura=true: gera o APK de produção sem assinar, de propósito (CI, que não tem a chave).
// Esse APK serve só para conferir o build (R8 etc.): o Android não instala APK sem assinatura.
val semAssinaturaPermitido = providers.gradleProperty("rfid.semAssinatura").orNull?.toBoolean() == true

if (!assinaturaConfigurada) {
    val motivo = "assinatura de produção não configurada (keystore.properties ausente ou incompleto, " +
        "ou arquivo da chave não encontrado: ${arquivoChave ?: "não informado"})"
    if (!semAssinaturaPermitido) {
        val problema = "APK de produção recusado: $motivo. Sem assinatura o Android não instala o APK. " +
            "Configure a chave (veja \"APK de produção\" no README) ou, só para testar o build, use -Prfid.semAssinatura=true"
        if (pedidoRelease) throw GradleException(problema)
        problemasDoRelease += problema
    }
    logger.warn("AVISO: $motivo. O APK de produção sairá SEM assinatura e o Android não instala. " +
        "Veja \"APK de produção\" no README.")
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
    // Esquema do banco local (Room) de cada versão, versionado no git para conferir as migrações
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }
    testOptions {
        // Nos testes no computador, android.util.Log e afins retornam valor padrão em vez de lançar exceção
        unitTests.isReturnDefaultValues = true
    }
}

// Bytecode Kotlin no mesmo alvo do Java (compileOptions acima)
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

// "build"/"assemble" vão empacotar o APK (ou bundle) de produção: mesmas travas de quando se pede assembleRelease
gradle.taskGraph.whenReady {
    val empacotaRelease = allTasks.any { it.project == project && it.name.startsWith("package") && it.name.contains("Release") }
    if (empacotaRelease && problemasDoRelease.isNotEmpty()) throw GradleException(problemasDoRelease.joinToString("\n"))
}

// APK com a versão no nome (ex.: RFID-Android-1.6.1-release.apk), ao lado do app-release.apk que o plugin gera.
// Feito só com a API pública do plugin Android (androidComponents): a forma antiga (applicationVariants com cast
// para uma classe interna) sai no plugin 9.
abstract class CopiarApkComVersao : DefaultTask() {
    @get:InputFiles
    abstract val pastaApk: DirectoryProperty

    @get:Internal
    abstract val carregador: Property<BuiltArtifactsLoader>

    @get:OutputFile
    abstract val destino: RegularFileProperty

    @TaskAction
    fun copiar() {
        val apk = carregador.get().load(pastaApk.get())?.elements?.singleOrNull()?.outputFile
            ?: throw GradleException("APK não encontrado em ${pastaApk.get().asFile}")
        File(apk).copyTo(destino.get().asFile, overwrite = true)
        logger.lifecycle("APK: ${destino.get().asFile}")
    }
}

androidComponents {
    onVariants { variante ->
        val sufixo = variante.name.replaceFirstChar { it.uppercase() }
        val copia = tasks.register<CopiarApkComVersao>("copiarApkComVersao$sufixo") {
            pastaApk.set(variante.artifacts.get(SingleArtifact.APK))
            carregador.set(variante.artifacts.getBuiltArtifactsLoader())
            destino.set(layout.buildDirectory.file("outputs/apk/${variante.name}/RFID-Android-$versaoApp-${variante.name}.apk"))
        }
        tasks.matching { it.name == "assemble$sufixo" }.configureEach { finalizedBy(copia) }
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
    testImplementation(libs.robolectric)                   // Android simulado no computador: banco local (Room) num SQLite de verdade
    testImplementation(libs.androidx.test.core)            // contexto do app nos testes com Robolectric
    testImplementation(libs.work.testing)                  // roda o SincronizacaoWorker nos testes (TestListenableWorkerBuilder)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.okhttp)
    implementation(libs.room.runtime)       // banco local: fila de leituras para o modo offline
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)       // envia as leituras pendentes em segundo plano quando a rede volta
}