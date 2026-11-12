package dev.thal.teambuilder.core

import com.google.gson.JsonParser
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Traducoes do mod. Usa os mesmos arquivos de idioma do Minecraft
 * (assets/cobblemon_team_builder/lang/<idioma>.json), mas le direto do jar: assim o servidor
 * consegue escrever motivos e avisos no idioma de cada jogador, e o nucleo continua testavel
 * sem Minecraft.
 *
 * Tipos dentro de um texto vao como "{fire}"; a tela troca por icones e o chat pelo nome do tipo.
 */
class Lang private constructor(val code: String) {

    fun t(key: String, vararg args: Any?): String {
        val pattern = table(code)[key] ?: table(FALLBACK)[key] ?: return key
        if (args.isEmpty()) return pattern
        return try {
            String.format(Locale.ROOT, pattern, *args)
        } catch (e: Exception) {
            pattern
        }
    }

    fun has(key: String): Boolean = table(code).containsKey(key) || table(FALLBACK).containsKey(key)

    companion object {
        private const val FALLBACK = "en_us"
        private val tables = ConcurrentHashMap<String, Map<String, String>>()

        val EN = Lang("en_us")
        val PT = Lang("pt_br")

        /** Qualquer variante de portugues usa pt_br; o resto, ingles. */
        fun of(code: String?): Lang = if (code.orEmpty().lowercase().startsWith("pt")) PT else EN

        private fun table(code: String): Map<String, String> = tables.getOrPut(code) {
            val stream = Lang::class.java.getResourceAsStream("/assets/cobblemon_team_builder/lang/$code.json")
                ?: return@getOrPut emptyMap()
            stream.reader(Charsets.UTF_8).use { reader ->
                JsonParser.parseReader(reader).asJsonObject.entrySet().associate { (k, v) -> k to v.asString }
            }
        }

        /** "{fire}{flying}" — tipos como fichas para o texto rico. */
        fun types(types: Collection<String>): String = types.joinToString("") { "{$it}" }

        val TYPE_TOKEN = Regex("\\{([a-z]+)\\}")
    }
}
