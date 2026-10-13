plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.ksp)
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
        versionCode = 6
        versionName = "1.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true   // BuildConfig.VERSION_NAME etc. no log de início do app
    }

    buildTypes {
        release {
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