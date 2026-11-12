package dev.thal.teambuilder.net

import com.google.gson.Gson
import dev.thal.teambuilder.TeamBuilderMod
import dev.thal.teambuilder.cobblemon.TeamBuilderService
import dev.thal.teambuilder.core.BuilderRequest
import dev.thal.teambuilder.core.BuilderState
import dev.thal.teambuilder.core.SearchRequest
import dev.thal.teambuilder.core.SearchResult
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.ResourceLocation

private const val MAX_JSON = 262144

/** Cliente -> servidor: um [BuilderRequest] em JSON. */
class RequestPayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<RequestPayload>(
            ResourceLocation.fromNamespaceAndPath(TeamBuilderMod.MOD_ID, "request")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, RequestPayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.json, MAX_JSON) },
            { buf -> RequestPayload(buf.readUtf(MAX_JSON)) },
        )
    }
}

/** Servidor -> cliente: um [BuilderState] em JSON. */
class StatePayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<StatePayload>(
            ResourceLocation.fromNamespaceAndPath(TeamBuilderMod.MOD_ID, "state")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, StatePayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.json, MAX_JSON) },
            { buf -> StatePayload(buf.readUtf(MAX_JSON)) },
        )
    }
}

/** Cliente -> servidor: um [SearchRequest] em JSON, enviado enquanto o jogador digita. */
class SearchPayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SearchPayload>(
            ResourceLocation.fromNamespaceAndPath(TeamBuilderMod.MOD_ID, "search")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SearchPayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.json, 1024) },
            { buf -> SearchPayload(buf.readUtf(1024)) },
        )
    }
}

/** Servidor -> cliente: um [SearchResult] em JSON. */
class SearchResultPayload(val json: String) : CustomPacketPayload {
    override fun type() = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<SearchResultPayload>(
            ResourceLocation.fromNamespaceAndPath(TeamBuilderMod.MOD_ID, "search_result")
        )
        val CODEC: StreamCodec<RegistryFriendlyByteBuf, SearchResultPayload> = StreamCodec.of(
            { buf, p -> buf.writeUtf(p.json, MAX_JSON) },
            { buf -> SearchResultPayload(buf.readUtf(MAX_JSON)) },
        )
    }
}

object Network {
    val GSON = Gson()

    /** Chamado nos dois lados: registra os tipos de pacote e o tratamento no servidor. */
    fun registerCommon() {
        PayloadTypeRegistry.playC2S().register(RequestPayload.TYPE, RequestPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(StatePayload.TYPE, StatePayload.CODEC)
        PayloadTypeRegistry.playC2S().register(SearchPayload.TYPE, SearchPayload.CODEC)
        PayloadTypeRegistry.playS2C().register(SearchResultPayload.TYPE, SearchResultPayload.CODEC)

        ServerPlayNetworking.registerGlobalReceiver(SearchPayload.TYPE) { payload, context ->
            val player = context.player()
            val result = try {
                val query = GSON.fromJson(payload.json, SearchRequest::class.java).query.orEmpty().take(40)
                TeamBuilderService.search(player, query)
            } catch (e: Exception) {
                TeamBuilderMod.LOGGER.error("Falha na busca de {}", player.gameProfile.name, e)
                SearchResult()
            }
            ServerPlayNetworking.send(player, SearchResultPayload(GSON.toJson(result)))
        }

        ServerPlayNetworking.registerGlobalReceiver(RequestPayload.TYPE) { payload, context ->
            val player = context.player()
            val state = try {
                val request = GSON.fromJson(payload.json, BuilderRequest::class.java)
                // O cliente nao e confiavel: limita o tamanho do que ele pede.
                val safe = request.copy(
                    keys = request.keys.orEmpty().take(TeamBuilderService.TEAM_SIZE),
                    add = request.add?.take(200),
                    raid = request.raid?.take(100),
                    roles = request.roles.orEmpty().take(TeamBuilderService.TEAM_SIZE).map { it?.take(16) },
                )
                TeamBuilderService.state(player, safe)
            } catch (e: Exception) {
                TeamBuilderMod.LOGGER.error("Falha ao montar sugestoes para {}", player.gameProfile.name, e)
                BuilderState(error = TeamBuilderService.lang(player).t("error.server"))
            }
            ServerPlayNetworking.send(player, StatePayload(GSON.toJson(state)))
        }
    }
}
