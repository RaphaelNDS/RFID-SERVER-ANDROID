package com.example.rfid_server.previa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import com.example.rfid_server.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Prévia das telas no computador, sem emulador nem coletor: desenha os layouts com o tema do app no tamanho do
 * MC3300x (320x533dp, hdpi) e grava PNG em app/build/previa. Use ao mexer no visual (cores, estilos, layouts):
 * ./gradlew testDebugUnitTest --tests "*PreviaVisualTest*" --rerun. Não confere nada: só gera as imagens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h533dp-port-hdpi")
class PreviaVisualTest {

    @Test
    fun gerar() {
        val pasta = File(System.getProperty("user.dir"), "build/previa").apply { mkdirs() }
        val controle = org.robolectric.Robolectric.buildActivity(androidx.appcompat.app.AppCompatActivity::class.java)
        controle.get().setTheme(R.style.Theme_RFIDAndroid)
        val contexto = controle.create().get()
        val telas = mapOf(
            "login" to R.layout.activity_login,
            "principal" to R.layout.activity_main,
            "inventario" to R.layout.activity_inventario,
            "localizar" to R.layout.activity_localizar,
            "cadastro" to R.layout.activity_cadastro_tag,
            "configuracoes" to R.layout.activity_settings,
            "item_tag" to R.layout.item_tag,
            "item_esperado" to R.layout.item_esperado,
        )
        for ((nome, layout) in telas) {
            val v = LayoutInflater.from(contexto).inflate(layout, null, false)
            val largura = 480
            val altura = if (nome.startsWith("item")) 200 else 800
            v.measure(View.MeasureSpec.makeMeasureSpec(largura, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(altura, View.MeasureSpec.EXACTLY))
            v.layout(0, 0, largura, altura)
            val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
            Canvas(bmp).apply { drawColor(android.graphics.Color.WHITE); v.draw(this) }
            File(pasta, "$nome.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
