package dev.thal.teambuilder.cobblemon

import com.cobblemon.mod.common.api.Priority
import com.cobblemon.mod.common.api.battles.model.PokemonBattle
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor
import com.cobblemon.mod.common.api.battles.model.actor.EntityBackedBattleActor
import com.cobblemon.mod.common.api.events.CobblemonEvents
import com.cobblemon.mod.common.api.reactive.SimpleObservable
import com.cobblemon.mod.common.Cobblemon
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.core.AchievementList
import dev.thal.teambuilder.core.BuilderRequest
import dev.thal.teambuilder.core.BuilderState
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.storage.LevelResource
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Conquistas do Team Builder, guardadas como progressos do Minecraft.
 *
 * Algumas vem da propria tela (montar um time, abrir uma aba); as de batalha precisam saber o que
 * o mod recomendou: ao mostrar uma raid ou um treinador, guardamos os Pokemon do jogador que
 * apareceram como recomendados, e ao fim da batalha conferimos se foram eles que lutaram.
 */
object Achievements {
    private const val CRITERION = "done"

    /** Quantos Pokemon recomendados precisam estar no time da batalha contra um treinador. */
    private const val TRAINER_MATCH = 3

    /** O que o mod recomendou a cada jogador. Fica salvo no mundo para valer depois de reiniciar. */
    private class Memory(
        /** Especie do chefe ("cobblemon:charizard") -> uuids dos Pokemon recomendados. */
        val raids: MutableMap<String, MutableSet<String>> = HashMap(),
        /** Id do treinador do RCT -> uuids dos Pokemon recomendados contra ele. */
        val trainers: MutableMap<String, MutableSet<String>> = HashMap(),
    )

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private var memory = ConcurrentHashMap<String, Memory>()
    private var file: Path? = null

    /** Pokemon que cada jogador levou para a ultima batalha (as raids nao dizem quem lutou). */
    private val lastBattle = ConcurrentHashMap<UUID, Set<String>>()

    fun register() {
        CobblemonEvents.BATTLE_STARTED_POST.subscribe(Priority.NORMAL) { event ->
            try {
                for (actor in event.battle.actors) {
                    val team = uuidsOf(actor)
                    for (id in actor.getPlayerUUIDs()) lastBattle[id] = team
                }
            } catch (e: Throwable) {
                TeamBuilderMod.LOGGER.debug("Conquistas: falha ao ler o inicio da batalha", e)
            }
        }
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL) { event ->
            try {
                onVictory(event.battle, event.winners, event.losers)
            } catch (e: Throwable) {
                TeamBuilderMod.LOGGER.debug("Conquistas: falha ao ler a vitoria", e)
            }
        }
        if (FabricLoader.getInstance().isModLoaded("cobblemonraiddens")) registerRaids()
    }

    // ------------------------------------------------------------- arquivo

    fun load(server: MinecraftServer) {
        val path = server.getWorldPath(LevelResource.ROOT).resolve("data").resolve("cobblemon_team_builder_memory.json")
        file = path
        memory = try {
            if (Files.exists(path)) {
                val type = object : TypeToken<Map<String, Memory>>() {}.type
                ConcurrentHashMap(gson.fromJson<Map<String, Memory>>(Files.readString(path), type).orEmpty())
            } else ConcurrentHashMap()
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui ler a memoria das conquistas", e)
            ConcurrentHashMap()
        }
    }

    private fun save() {
        val path = file ?: return
        try {
            Files.createDirectories(path.parent)
            Files.writeString(path, gson.toJson(memory))
        } catch (e: Exception) {
            TeamBuilderMod.LOGGER.warn("Nao consegui gravar a memoria das conquistas", e)
        }
    }

    private fun memoryOf(player: ServerPlayer): Memory = memory.getOrPut(player.uuid.toString()) { Memory() }

    // ------------------------------------------------------------ progressos

    private fun holder(player: ServerPlayer, id: String) =
        player.server.advancements.get(ResourceLocation.fromNamespaceAndPath(TeamBuilderMod.MOD_ID, id))

    fun award(player: ServerPlayer, id: String) {
        if (id !in AchievementList.ids) return
        val h = holder(player, id) ?: return
        // A arvore so faz sentido completa: ganhar uma conquista libera as anteriores a ela.
        AchievementList.all.firstOrNull { it.id == id }?.parent?.let { award(player, it) }
        if (!player.advancements.getOrStartProgress(h).isDone) player.advancements.award(h, CRITERION)
    }

    fun done(player: ServerPlayer): List<String> = AchievementList.all.mapNotNull { a ->
        val h = holder(player, a.id) ?: return@mapNotNull null
        a.id.takeIf { player.advancements.getOrStartProgress(h).isDone }
    }

    /** Evento vindo da tela (aba aberta, time salvo...); so os da lista sao aceitos. */
    fun fromClient(player: ServerPlayer, event: String) {
        if (event in AchievementList.CLIENT_EVENTS) award(player, event)
    }

    // --------------------------------------------------------------- tela

    /** Confere o que o jogador acabou de fazer na tela e guarda as recomendacoes de batalha. */
    fun afterState(player: ServerPlayer, request: BuilderRequest, state: BuilderState) {
        award(player, "root")
        if (state.error != null && state.team.isEmpty()) return
        val team = state.team
        if (team.size >= TeamBuilderService.TEAM_SIZE) award(player, "full_team")
        if (team.any { it.mega }) award(player, "mega")
        if (request.roles.any { it != null } && team.isNotEmpty()) award(player, "role")
        if (state.doubles && team.size >= TeamBuilderService.TEAM_SIZE) award(player, "doubles")
        if (request.apply && state.notice != null) award(player, "apply")

        // Checklist completo: todo item feito (IVs sao so informativos).
        val ready = team.indices.filter { i ->
            val slot = team[i]
            val list = state.sets.getOrNull(i)?.checklist.orEmpty()
            slot.owned && list.isNotEmpty() && list.all { it.state == "done" || it.state == "info" }
        }
        if (ready.isNotEmpty()) award(player, "ready_one")
        if (ready.size >= TeamBuilderService.TEAM_SIZE) award(player, "ready_team")

        var changed = false
        state.raid?.let { r ->
            award(player, "raid_tab")
            val picks = r.pcPicks.mapNotNull { uuidOfKey(it.slot.key) }.toMutableSet()
            if (picks.isNotEmpty() && memoryOf(player).raids.put(r.species, picks) != picks) changed = true
        }
        state.trainer?.let { t ->
            award(player, "trainer_tab")
            val picks = t.pcPicks.take(TeamBuilderService.TEAM_SIZE).mapNotNull { uuidOfKey(it.slot.key) }.toMutableSet()
            if (picks.isNotEmpty() && memoryOf(player).trainers.put(t.id, picks) != picks) changed = true
        }
        if (changed) save()
    }

    /** "uuid:<uuid>" ou "uuid:<uuid>|<mega>" -> "<uuid>"; especies do catalogo nao contam. */
    private fun uuidOfKey(key: String): String? =
        key.takeIf { it.startsWith("uuid:") }?.removePrefix("uuid:")?.substringBefore('|')

    private fun uuidsOf(actor: BattleActor): Set<String> =
        actor.pokemonList.mapTo(HashSet()) { it.originalPokemon.uuid.toString() }

    // ------------------------------------------------------------ batalhas

    private fun onVictory(battle: PokemonBattle, winners: List<BattleActor>, losers: List<BattleActor>) {
        val trainerId = losers.firstNotNullOfOrNull(::rctTrainerId) ?: return
        val trainer = Trainers.all[trainerId]
        for (actor in winners) {
            val team = uuidsOf(actor)
            for (id in actor.getPlayerUUIDs()) {
                val player = battle.players.firstOrNull { it.uuid == id } ?: continue
                val recommended = memory[id.toString()]?.trainers?.get(trainerId) ?: continue
                val used = team.count { it in recommended }
                if (used < minOf(TRAINER_MATCH, team.size)) continue
                award(player, "trainer_win")
                when (trainer?.type) {
                    "leader" -> award(player, "gym_win")
                    "e4", "champ" -> award(player, "elite_win")
                }
            }
        }
    }

    /** Id do treinador do Radical Cobblemon Trainers por tras do ator, por reflexao. */
    private fun rctTrainerId(actor: BattleActor): String? {
        if (!actor.javaClass.name.startsWith("com.gitlab.srcmc")) return null
        val entity = (actor as? EntityBackedBattleActor<*>)?.entity ?: return null
        val method = entity.javaClass.methods.firstOrNull { it.name == "getTrainerId" && it.parameterCount == 0 } ?: return null
        return method.invoke(entity) as? String
    }

    /** Fim de raid do Cobblemon Raid Dens (opcional, por reflexao). */
    @Suppress("UNCHECKED_CAST")
    private fun registerRaids() {
        try {
            val observable = Class.forName("com.necro.raid.dens.common.events.RaidEvents")
                .getField("RAID_END").get(null) as SimpleObservable<Any>
            observable.subscribe(Priority.NORMAL) { event ->
                try {
                    onRaidEnd(event)
                } catch (e: Throwable) {
                    TeamBuilderMod.LOGGER.debug("Conquistas: falha ao ler o fim da raid", e)
                }
            }
        } catch (e: Throwable) {
            TeamBuilderMod.LOGGER.warn("Conquistas: nao consegui ouvir as raids do Raid Dens", e)
        }
    }

    private fun call(target: Any, name: String): Any? =
        target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == 0 }?.invoke(target)

    private fun onRaidEnd(event: Any) {
        if (call(event, "isWin") != true) return
        val player = call(event, "player") as? ServerPlayer ?: return
        val raids = memory[player.uuid.toString()]?.raids ?: return
        if (raids.isEmpty()) return
        val boss = (call(event, "pokemon") as? com.cobblemon.mod.common.pokemon.Pokemon)?.species?.resourceIdentifier?.toString()
        val recommended = raids[boss] ?: raids.values.flatten().toSet()
        val team = lastBattle[player.uuid]
            ?: Cobblemon.storage.getParty(player).mapTo(HashSet()) { it.uuid.toString() }
        if (team.any { it in recommended }) award(player, "raid_win")
    }
}
