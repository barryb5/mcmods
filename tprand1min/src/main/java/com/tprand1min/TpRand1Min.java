package com.tprand1min;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;

import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpRand1Min implements ModInitializer {
	public static final String MOD_ID = "tprand1min";
	public static int ticksUntilTeleport = 20 * 60;
	public static int startTick = 0;

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("TP Rand 1 Min initialized");
		ServerTickEvents.END_SERVER_TICK.register(this::onTick);
	}

	private void onTick(MinecraftServer server) {
		if (server.getTickCount() % 20 == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				int secondsLeft = 60 - ((server.getTickCount() % ticksUntilTeleport) / 20);
				player.sendOverlayMessage(Component.literal(String.format("Teleport in %ds", secondsLeft)));
			}
		}

		if (server.getTickCount() % ticksUntilTeleport == 0) {
			// Teleport Player if there are players, assume singleplayer
			LOGGER.info("TP Rand 1 Min would teleport");
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				Teleporter.TeleportRandom(server, player);
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
