package com.millsboss.oneblockchunk.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Client half of OneBlockChunk. Registering the {@code oneblockchunk:view} channel is what
 * tells the plugin this player has the mod; from then on the plugin streams live views
 * instead of showing its copies, and this draws them.
 */
public final class OneBlockChunkClient implements ClientModInitializer {

	static final Logger LOG = LoggerFactory.getLogger("oneblockchunk");

	private static final Map<Integer, LiveView> VIEWS = new HashMap<>();
	private static ClientLevel viewsLevel;

	@Override
	public void onInitializeClient() {
		PayloadTypeRegistry.clientboundPlay().register(ViewPayload.TYPE, ViewPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(ViewPayload.TYPE, (payload, context) -> {
			ClientLevel level = context.client().level;
			if (level == null) return;
			syncLevel(level);
			try {
				Protocol.handle(payload.data(), level, VIEWS);
			} catch (Exception ex) {
				LOG.warn("OneBlockChunk: bad live view message: {}", ex.toString());
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (client.level == null) {
				clear();
				return;
			}
			syncLevel(client.level);
			for (LiveView v : VIEWS.values()) v.tick(client.level);
		});

		LevelRenderEvents.COLLECT_SUBMITS.register(context -> {
			if (VIEWS.isEmpty()) return;
			float partial = Minecraft.getInstance().getDeltaTracker().getGameTimeDeltaPartialTick(false);
			var camera = context.levelState().cameraRenderState;
			for (LiveView v : VIEWS.values()) {
				v.submit(context.poseStack(), context.submitNodeCollector(), camera, partial);
			}
		});

		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(OneBlockChunkClient::clear));
	}

	/** Views belong to one level; a new level (world change) starts clean. */
	private static void syncLevel(ClientLevel level) {
		if (viewsLevel != level) {
			VIEWS.clear();
			Render.reset();
			viewsLevel = level;
		}
	}

	private static void clear() {
		VIEWS.clear();
		viewsLevel = null;
	}
}
