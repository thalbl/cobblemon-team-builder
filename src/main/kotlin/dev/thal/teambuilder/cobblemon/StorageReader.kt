package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.Cobblemon
import com.cobblemon.mod.common.api.moves.MoveTemplate
import com.cobblemon.mod.common.api.pokemon.moves.Learnset
import com.cobblemon.mod.common.api.pokemon.stats.Stats
import com.cobblemon.mod.common.pokemon.Pokemon
import dev.thal.teambuilder.core.OwnedPokemon
import dev.thal.teambuilder.core.Source
import dev.thal.teambuilder.core.StatKey
import net.minecraft.server.level.ServerPlayer

/** Converte o party e o PC do jogador (lado servidor) nos modelos do nucleo. */
object StorageReader {

    fun read(player: ServerPlayer): List<OwnedPokemon> {
        val party = Cobblemon.storage.getParty(player).map { toOwned(it, Source.PARTY) }
        val pc = Cobblemon.storage.getPC(player).map { toOwned(it, Source.PC) }
        return party + pc
    }

    private fun toOwned(p: Pokemon, source: Source): OwnedPokemon {
        val form = p.form
        val baseStats = HashMap<StatKey, Int>()
        val ivs = HashMap<StatKey, Int>()
        val evs = HashMap<StatKey, Int>()
        for (stat in Stats.PERMANENT) {
            val key = StatKey.fromShowdownId(stat.showdownId) ?: continue
            baseStats[key] = form.baseStats[stat] ?: 0
            ivs[key] = p.ivs.getEffectiveBattleIV(stat)
            evs[key] = p.evs.getOrDefault(stat)
        }
        return OwnedPokemon(
            uuid = p.uuid.toString(),
            source = source,
            id = showdownId(form.showdownId()),
            speciesId = showdownId(p.species.showdownId()),
            displayName = if (form.name.equals("normal", ignoreCase = true)) p.species.name
                          else "${p.species.name}-${form.name}",
            level = p.level,
            types = p.types.map { it.showdownId.lowercase() },
            ability = showdownId(p.ability.name),
            nature = p.nature.name.path,
            baseStats = baseStats,
            ivs = ivs,
            evs = evs,
            currentMoves = p.moveSet.getMoves().map { showdownId(it.name) },
            accessibleMoves = p.allAccessibleMoves.mapTo(HashSet()) { showdownId(it.name) },
            legalMoves = form.moves.getAllLegalMoves().mapTo(HashSet()) { showdownId(it.name) },
            learnMethods = learnMethods(form.moves),
            legendary = isLegendary(p.species.labels),
            species = p.species.resourceIdentifier.toString(),
            aspects = p.aspects.toSet(),
        )
    }

    private val LEGENDARY_LABELS = setOf("legendary", "mythical", "restricted")

    fun isLegendary(labels: Collection<String>): Boolean = labels.any { it.lowercase() in LEGENDARY_LABELS }

    /**
     * Para cada golpe, o jeito mais simples de aprender, como codigo ("lv:36", "tm", "egg"...).
     * O texto final depende do idioma do jogador: veja [methodText].
     */
    fun learnMethods(learnset: Learnset): Map<String, String> {
        val out = HashMap<String, String>()
        fun add(moves: Iterable<MoveTemplate>, method: String) {
            for (m in moves) out.putIfAbsent(showdownId(m.name), method)
        }
        for ((level, moves) in learnset.levelUpMoves.toSortedMap()) add(moves, "lv:${level.coerceAtLeast(1)}")
        add(learnset.evolutionMoves, "evo")
        add(learnset.tmMoves, "tm")
        add(learnset.tutorMoves, "tutor")
        add(learnset.eggMoves, "egg")
        add(learnset.legacyMoves, "legacy")
        add(learnset.specialMoves, "special")
        add(learnset.formChangeMoves, "form")
        return out
    }

    /** "lv:36" -> "nivel 36" / "level 36"; "tm" -> "TM". */
    fun methodText(code: String, lang: dev.thal.teambuilder.core.Lang): String {
        if (code.isEmpty()) return ""
        if (code.startsWith("lv:")) return lang.t("method.level", code.removePrefix("lv:"))
        val key = "method.$code"
        return if (lang.has(key)) lang.t(key) else code
    }

    /** Normaliza para o formato de id do Showdown/Smogon: minusculas, so letras e numeros. */
    fun showdownId(raw: String): String = raw.lowercase().filter { it in 'a'..'z' || it in '0'..'9' }
}
