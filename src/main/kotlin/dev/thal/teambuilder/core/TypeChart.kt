package dev.thal.teambuilder.core

/** Tabela de tipos da geracao 9, mais as habilidades que mudam o dano recebido por tipo. */
object TypeChart {
    val TYPES = listOf(
        "normal", "fire", "water", "electric", "grass", "ice", "fighting", "poison", "ground",
        "flying", "psychic", "bug", "rock", "ghost", "dragon", "dark", "steel", "fairy",
    )
    val COUNT = TYPES.size

    private val index = TYPES.withIndex().associate { it.value to it.index }

    /** table[ataque][defesa] */
    private val table = Array(COUNT) { DoubleArray(COUNT) { 1.0 } }

    private fun rule(attack: String, mult: Double, defenders: String) {
        val a = index.getValue(attack)
        for (d in defenders.split(' ')) table[a][index.getValue(d)] = mult
    }

    private fun attack(type: String, strong: String = "", weak: String = "", none: String = "") {
        if (strong.isNotEmpty()) rule(type, 2.0, strong)
        if (weak.isNotEmpty()) rule(type, 0.5, weak)
        if (none.isNotEmpty()) rule(type, 0.0, none)
    }

    init {
        attack("normal", weak = "rock steel", none = "ghost")
        attack("fire", "grass ice bug steel", "fire water rock dragon")
        attack("water", "fire ground rock", "water grass dragon")
        attack("electric", "water flying", "electric grass dragon", "ground")
        attack("grass", "water ground rock", "fire grass poison flying bug dragon steel")
        attack("ice", "grass ground flying dragon", "fire water ice steel")
        attack("fighting", "normal ice rock dark steel", "poison flying psychic bug fairy", "ghost")
        attack("poison", "grass fairy", "poison ground rock ghost", "steel")
        attack("ground", "fire electric poison rock steel", "grass bug", "flying")
        attack("flying", "grass fighting bug", "electric rock steel")
        attack("psychic", "fighting poison", "psychic steel", "dark")
        attack("bug", "grass psychic dark", "fire fighting poison flying ghost steel fairy")
        attack("rock", "fire ice flying bug", "fighting ground steel")
        attack("ghost", "psychic ghost", "dark", "normal")
        attack("dragon", "dragon", "steel", "fairy")
        attack("dark", "psychic ghost", "fighting dark fairy")
        attack("steel", "ice rock fairy", "fire water electric steel")
        attack("fairy", "fighting dragon dark", "fire poison steel")
    }

    /** Habilidade -> multiplicadores extras sobre o dano recebido de certos tipos. */
    private val abilityModifiers: Map<String, Map<String, Double>> = mapOf(
        "levitate" to mapOf("ground" to 0.0),
        "eartheater" to mapOf("ground" to 0.0),
        "flashfire" to mapOf("fire" to 0.0),
        "wellbakedbody" to mapOf("fire" to 0.0),
        "waterabsorb" to mapOf("water" to 0.0),
        "stormdrain" to mapOf("water" to 0.0),
        "dryskin" to mapOf("water" to 0.0, "fire" to 1.25),
        "voltabsorb" to mapOf("electric" to 0.0),
        "lightningrod" to mapOf("electric" to 0.0),
        "motordrive" to mapOf("electric" to 0.0),
        "sapsipper" to mapOf("grass" to 0.0),
        "thickfat" to mapOf("fire" to 0.5, "ice" to 0.5),
        "heatproof" to mapOf("fire" to 0.5),
        "waterbubble" to mapOf("fire" to 0.5),
        "purifyingsalt" to mapOf("ghost" to 0.5),
        "fluffy" to mapOf("fire" to 2.0),
    )

    fun indexOf(type: String): Int = index[type.lowercase()] ?: -1

    fun multiplier(attackType: Int, defenderType: Int): Double = table[attackType][defenderType]

    /** Multiplicador de dano recebido de cada tipo de ataque, ja com a habilidade. */
    fun defensiveProfile(types: List<String>, ability: String?): DoubleArray {
        val defenders = types.map(::indexOf).filter { it >= 0 }
        val mods = abilityModifiers[ability] ?: emptyMap()
        return DoubleArray(COUNT) { a ->
            var m = 1.0
            for (d in defenders) m *= table[a][d]
            m * (mods[TYPES[a]] ?: 1.0)
        }
    }

    /** Bitmask dos tipos defensores que algum dos [attackTypes] acerta com vantagem. */
    fun superEffectiveMask(attackTypes: Iterable<String>): Int {
        var mask = 0
        for (t in attackTypes) {
            val a = indexOf(t)
            if (a < 0) continue
            for (d in 0 until COUNT) if (table[a][d] > 1.0) mask = mask or (1 shl d)
        }
        return mask
    }

    fun typesIn(mask: Int): List<String> = TYPES.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
}
