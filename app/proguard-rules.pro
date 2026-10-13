# Regras do R8 (minificação do APK de release). OkHttp, Room e WorkManager trazem as
# regras deles nas próprias bibliotecas; aqui fica só o que é específico deste app.

# ---------- SDK do leitor Zebra (gradle/libs/*.aar) ----------
# O SDK usa reflexão e código nativo, e várias AARs não trazem regras próprias.
# Se o R8 renomear ou remover algo, o leitor deixa de conectar sem erro claro: mantém tudo.
-keep class com.zebra.** { *; }
-keep interface com.zebra.** { *; }
-keep class vendor.zebra.** { *; }
-dontwarn com.zebra.**
-dontwarn vendor.zebra.**

# ---------- Rastreamento de erros ----------
# Mantém número de linha nos stack traces do release (o nome do arquivo vira "SourceFile").
# Para traduzir um stack trace ofuscado: app/build/outputs/mapping/release/mapping.txt
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- Logs ----------
# No release, remove Log.d e Log.v (detalhe para desenvolvimento, alguns por tag lida).
# Log.i, Log.w e Log.e continuam: são os que ajudam a investigar problema em campo.
-assumenosideeffects class android.util.Log {
    public static int d(...);
    public static int v(...);
}
