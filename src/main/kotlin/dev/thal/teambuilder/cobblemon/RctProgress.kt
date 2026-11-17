package dev.thal.teambuilder.cobblemon

import dev.thal.teambuilder.TeamBuilderMod
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.server.level.ServerPlayer

/**
 * Progresso do jogador no Radical Cobblemon Trainers: quem ele ja venceu, o limite de nivel
 * e os proximos treinadores da serie que ele esta seguindo. Tudo por reflexao, porque o RCT
 * e opcional e nao entra como dependencia de compilacao.
 */
object RctProgress {
    private const val PLAYER_STATE = "com.gitlab.srcmc.rctmod.api.data.sync.PlayerState"
    private const val RCT_MOD = "com.gitlab.srcmc.rctmod.api.RCTMod"

    data class Progress(
        val defeated: Set<String>,
        /** Proximos treinadores da serie atual (vazio no modo livre ou sem serie). */
        val next: Set<String>,
        /** 0 = sem limite conhecido. */
        val levelCap: Int,
    )

    val installed: Boolean by lazy { FabricLoader.getInstance().isModLoaded("rctmod") }

    @Volatile
    private var warned = false

    private fun method(target: Any, name: String, args: Int) =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args }

    private fun call(target: Any, name: String, vararg args: Any?): Any? =
        method(target, name, args.size)?.invoke(target, *args)

    fun of(player: ServerPlayer): Progress? {
        if (!installed) return null
        return try {
            val stateClass = Class.forName(PLAYER_STATE)
            val get = stateClass.methods.first { it.name == "get" && it.parameterCount == 1 }
            val state = get.invoke(null, player) ?: return null
            @Suppress("UNCHECKED_CAST")
            val defeated = (call(state, "getDefeatedTrainerIds") as? Set<String>).orEmpty().toSet()
            val cap = (call(state, "getLevelCap") as? Int) ?: 0
            val series = call(state, "getCurrentSeries") as? String
            Progress(defeated, nextOf(series, defeated), cap)
        } catch (e: Throwable) {
            if (!warned) {
                warned = true
                TeamBuilderMod.LOGGER.warn("Nao consegui ler o progresso do RCT", e)
            }
            null
        }
    }

    /** Proximos da serie: RCTMod.getInstance().getSeriesManager().getGraph(serie).getNext(vencidos). */
    private fun nextOf(series: String?, defeated: Set<String>): Set<String> {
        if (series.isNullOrBlank()) return emptySet()
        return try {
            val rct = Class.forName(RCT_MOD).getMethod("getInstance").invoke(null) ?: return emptySet()
            val manager = call(rct, "getSeriesManager") ?: return emptySet()
            val graph = call(manager, "getGraph", series) ?: return emptySet()
            val getNext = graph.javaClass.methods.firstOrNull {
                it.name == "getNext" && it.parameterCount == 1 && it.parameterTypes[0] == Set::class.java
            } ?: return emptySet()
            val next = getNext.invoke(graph, defeated) ?: return emptySet()
            val stream = call(next, "stream") as? java.util.stream.Stream<*> ?: return emptySet()
            stream.iterator().asSequence().mapNotNull { node -> node?.let { call(it, "id") as? String } }.toSet()
        } catch (e: Throwable) {
            TeamBuilderMod.LOGGER.debug("RCT: falha ao ler a proxima etapa da serie {}", series, e)
            emptySet()
        }
    }
}
