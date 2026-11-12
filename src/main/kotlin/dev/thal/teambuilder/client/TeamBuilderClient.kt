package dev.thal.teambuilder.client

import com.mojang.blaze3d.platform.InputConstants
import dev.thal.teambuilder.core.BuilderState
import dev.thal.teambuilder.core.SearchResult
import dev.thal.teambuilder.net.Network
import dev.thal.teambuilder.net.SearchResultPayload
import dev.thal.teambuilder.net.StatePayload
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

object TeamBuilderClient : ClientModInitializer {

    override fun onInitializeClient() {
        val openKey = KeyBindingHelper.registerKeyBinding(
            KeyMapping(
                "key.cobblemon_team_builder.open",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.cobblemon_team_builder",
            )
        )

        ClientTickEvents.END_CLIENT_TICK.register { client ->
            while (openKey.consumeClick()) {
                if (client.screen == null) client.setScreen(TeamBuilderScreen())
            }
        }

        ClientPlayNetworking.registerGlobalReceiver(StatePayload.TYPE) { payload, context ->
            val state = Network.GSON.fromJson(payload.json, BuilderState::class.java)
            (context.client().screen as? TeamBuilderScreen)?.accept(state)
        }

        ClientPlayNetworking.registerGlobalReceiver(SearchResultPayload.TYPE) { payload, context ->
            val result = Network.GSON.fromJson(payload.json, SearchResult::class.java)
            (context.client().screen as? TeamBuilderScreen)?.acceptSearch(result)
        }
    }
}
