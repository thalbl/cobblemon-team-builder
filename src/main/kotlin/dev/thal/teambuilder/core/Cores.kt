package dev.thal.teambuilder.core

import kotlin.math.max

/**
 * Nucleos de time: clima, terreno e Trick Room. Um nucleo fica ativo quando alguem do time o
 * ativa (habilidade ou golpe); os membros que se beneficiam dele valem mais nesse time.
 */
enum class Core(
    val id: String,
    val setterAbilities: Set<String>,
    val setterMoves: Set<String>,
    /** Habilidades que so funcionam (ou funcionam muito melhor) com o nucleo ativo. */
    val abuserAbilities: Set<String>,
    /** Golpes que ficam mais fortes ou mais precisos com o nucleo ativo. */
    val boostedMoves: Set<String>,
    /** Tipos de ataque reforcados; contam como meio aproveitador. */
    val boostedTypes: Set<String>,
) {
    RAIN(
        "rain", setOf("drizzle", "primordialsea"), setOf("raindance"),
        setOf("swiftswim", "raindish", "hydration", "dryskin"), setOf("thunder", "hurricane", "weatherball", "electroshot"), setOf("water"),
    ),
    SUN(
        "sun", setOf("drought", "desolateland", "orichalcumpulse"), setOf("sunnyday"),
        setOf("chlorophyll", "solarpower", "protosynthesis", "flowergift", "harvest", "leafguard"),
        setOf("solarbeam", "solarblade", "weatherball", "growth", "hydrosteam"), setOf("fire"),
    ),
    SAND(
        "sand", setOf("sandstream"), setOf("sandstorm"),
        setOf("sandrush", "sandforce", "sandveil"), setOf("shoreup", "weatherball"), setOf("rock"),
    ),
    SNOW(
        "snow", setOf("snowwarning"), setOf("snowscape", "chillyreception"),
        setOf("slushrush", "icebody", "snowcloak"), setOf("auroraveil", "blizzard", "weatherball"), setOf("ice"),
    ),
    ELECTRIC_TERRAIN(
        "electric_terrain", setOf("electricsurge", "hadronengine"), setOf("electricterrain"),
        setOf("surgesurfer", "quarkdrive"), setOf("risingvoltage"), setOf("electric"),
    ),
    GRASSY_TERRAIN(
        "grassy_terrain", setOf("grassysurge", "seedsower"), setOf("grassyterrain"),
        setOf("grasspelt"), setOf("grassyglide"), setOf("grass"),
    ),
    PSYCHIC_TERRAIN(
        "psychic_terrain", setOf("psychicsurge"), setOf("psychicterrain"),
        emptySet(), setOf("expandingforce"), setOf("psychic"),
    ),
    MISTY_TERRAIN(
        "misty_terrain", setOf("mistysurge"), setOf("mistyterrain"),
        emptySet(), setOf("mistyexplosion"), emptySet(),
    ),
    TRICK_ROOM("trick_room", emptySet(), setOf("trickroom"), emptySet(), emptySet(), emptySet());

    fun label(lang: Lang): String = lang.t("core.$id")
}

/** Um nucleo ativo no time, com quem o ativa e quem aproveita. */
data class ActiveCore(val core: Core, val setters: List<Profile>, val abusers: List<Profile>, val value: Double)

object Cores {
    private fun stat(p: Profile, key: StatKey) = p.candidate.baseStats[key] ?: 0

    private fun abilities(p: Profile): Set<String> =
        if (p.isOwned && !p.isMega) setOf(p.ability) else (p.candidate.abilities + p.ability).toSet()

    fun sets(p: Profile, core: Core): Boolean =
        p.ability in core.setterAbilities || p.likelyMoves.any { it in core.setterMoves }

    /** Quanto [p] aproveita o nucleo: 1 = muito (habilidade, golpe), 0.5 = um pouco (tipo), 0 = nada. */
    fun abuse(p: Profile, core: Core): Double {
        if (core == Core.TRICK_ROOM) {
            val spe = stat(p, StatKey.SPE)
            val offense = max(stat(p, StatKey.ATK), stat(p, StatKey.SPA))
            return when {
                spe <= 45 && offense >= 90 -> 1.0
                spe <= 60 && offense >= 100 -> 0.5
                else -> 0.0
            }
        }
        if (abilities(p).any { it in core.abuserAbilities }) return 1.0
        if (p.likelyMoves.any { it in core.boostedMoves }) return 1.0
        if (p.candidate.types.any { it in core.boostedTypes }) return 0.5
        return 0.0
    }

    /** Nucleos ativos no time; o valor soma quanto os membros aproveitam (no maximo 3). */
    fun active(team: List<Profile>): List<ActiveCore> = Core.entries.mapNotNull { core ->
        val setters = team.filter { sets(it, core) }
        if (setters.isEmpty()) return@mapNotNull null
        val abusers = team.filter { abuse(it, core) > 0 }
        val value = minOf(3.0, team.sumOf { abuse(it, core) })
        ActiveCore(core, setters, abusers, value)
    }
}
