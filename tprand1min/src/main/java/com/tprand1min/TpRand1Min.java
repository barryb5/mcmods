package com.tprand1min;

import com.mcutils.Teleporter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpRand1Min implements ModInitializer {
	public static final String MOD_ID = "tprand1min";
	public static int ticksUntilTeleport = 20 * 60;
	public static int startTick = 0;

	// Logger
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
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
				TeleportTransition transition = Teleporter.TeleportRandomPlayer(server, player);
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
