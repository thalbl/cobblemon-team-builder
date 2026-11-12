package dev.thal.teambuilder.core

/**
 * Modelos do nucleo. Nada aqui depende de Minecraft ou Cobblemon, para que o motor
 * de pontuacao possa ser testado com testes unitarios comuns.
 */

enum class Source { PARTY, PC }

/** Ordem fixa dos seis atributos, igual a usada nos spreads do Smogon. */
enum class StatKey(val showdownId: String) {
    HP("hp"), ATK("atk"), DEF("def"), SPA("spa"), SPD("spd"), SPE("spe");

    companion object {
        fun fromShowdownId(id: String): StatKey? = entries.firstOrNull { it.showdownId == id }
    }
}

/** Um Pokemon que o jogador possui, ja reduzido ao que o motor precisa. */
data class OwnedPokemon(
    val uuid: String,
    val source: Source,
    /** Id no estilo Showdown da forma atual, ex.: "rillaboom", "ninetalesalola". */
    val id: String,
    val speciesId: String,
    val displayName: String,
    val level: Int,
    val types: List<String>,
    val ability: String,
    val nature: String,
    val baseStats: Map<StatKey, Int>,
    val ivs: Map<StatKey, Int>,
    val evs: Map<StatKey, Int>,
    val currentMoves: List<String>,
    /** Golpes que ele pode trocar agora (bancados/reaprendiveis), sem TM nem cruzamento. */
    val accessibleMoves: Set<String>,
    /** Tudo que a especie pode aprender por qualquer metodo. */
    val legalMoves: Set<String>,
    /** Como a especie aprende cada golpe: "nivel 36", "TM", "tutor", "egg move"... */
    val learnMethods: Map<String, String> = emptyMap(),
    /** Lendario, mitico ou restrito, segundo as etiquetas da especie no Cobblemon. */
    val legendary: Boolean = false,
    /** Identificador da especie ("cobblemon:charizard") e aspectos, para desenhar o modelo. */
    val species: String = "",
    val aspects: Set<String> = emptySet(),
) {
    val baseStatTotal: Int get() = baseStats.values.sum()
    val ivTotal: Int get() = ivs.values.sum()
}

data class Spread(val nature: String, val evs: List<Int>, val freq: Double)

/** Dados competitivos de um Pokemon, vindos do snapshot do Smogon. */
data class MetaEntry(
    val name: String,
    val format: String,
    /** 0 = formato mais forte (Ubers); cresce conforme o tier desce. */
    val tier: Int,
    val usage: Double,
    val moves: Map<String, Double>,
    val items: Map<String, Double>,
    val abilities: Map<String, Double>,
    val spreads: List<Spread>,
    /** P(parceiro no time | este Pokemon no time). */
    val teammates: Map<String, Double>,
    val counters: Map<String, Double>,
)

class MetaSnapshot(
    val month: String,
    private val formatUsage: Map<String, Map<String, Double>>,
    private val species: Map<String, MetaEntry>,
) {
    val size: Int get() = species.size
    val ids: Set<String> get() = species.keys

    operator fun get(id: String): MetaEntry? = species[id]

    /**
     * Quanto [mate] aparece ao lado de [id] acima do esperado pelo uso geral dele.
     * 1.0 = neutro, >1 = boa dupla, <1 = raramente juntos. Null se nao ha dados.
     */
    fun teammateLift(id: String, mate: String): Double? {
        val entry = species[id] ?: return null
        val together = entry.teammates[mate] ?: return null
        val baseline = formatUsage[entry.format]?.get(mate) ?: return null
        if (baseline <= 0.0) return null
        return together / baseline
    }

    companion object {
        val EMPTY = MetaSnapshot("none", emptyMap(), emptyMap())
    }
}
