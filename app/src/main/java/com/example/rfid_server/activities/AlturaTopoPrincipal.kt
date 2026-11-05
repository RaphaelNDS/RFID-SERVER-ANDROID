package com.example.rfid_server.activities

/**
 * Altura do bloco de avisos da tela principal (recusa, contadores e faixa de sincronização), que fica entre a
 * faixa do leitor e as abas (lógica pura, testável sem Android).
 *
 * Na tela de ~4" do coletor, com todos os avisos ao mesmo tempo, a lista de etiquetas ficava com quase 0dp.
 * Agora o bloco usa a altura natural enquanto couber; se não couber, ele fica com o que sobra depois de
 * reservar [MINIMO_LISTA_DP] para a lista, e rola.
 */
object AlturaTopoPrincipal {

    /** Altura mínima da lista de etiquetas (ViewPager), em dp: mais ou menos duas etiquetas à vista. */
    const val MINIMO_LISTA_DP = 120

    /**
     * Altura que o bloco deve ter, ou null para a altura natural (wrap_content).
     *
     * @param alturaConteudo altura natural do conteúdo do bloco (tudo o que está visível nele)
     * @param alturaBlocoMaisLista o que a tela tem para o bloco e a lista juntos (a tela menos a barra, a faixa
     *   do leitor, as abas, os divisores e o rodapé)
     * @param minimoLista altura reservada para a lista, na mesma unidade (px)
     */
    fun limite(alturaConteudo: Int, alturaBlocoMaisLista: Int, minimoLista: Int): Int? {
        val maximo = (alturaBlocoMaisLista - minimoLista).coerceAtLeast(0)
        return if (alturaConteudo <= maximo) null else maximo
    }
}
