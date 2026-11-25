package com.tprand1min;

import com.mcutils.Teleporter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.resources.Identifier;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.portal.TeleportTransition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TpRand1Min implements ModInitializer {
	public static final GameRule<Integer> TELEPORT_INTERVAL = GameRuleBuilder.forInteger(60).range(1, 3600).category(GameRuleCategory.PLAYER).buildAndRegister(id("teleport_interval"));
	public static final String MOD_ID = "tprand1min";
	public static int startTick = 0;

	// Logger
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("TP Rand 1 Min initialized");
		ServerTickEvents.END_SERVER_TICK.register(this::onTick);
	}

	private void onTick(MinecraftServer server) {
		int interval = server.getGameRules().get(TELEPORT_INTERVAL) * 20;
		int tick = server.getTickCount();

		if (startTick > tick) {
			startTick = tick;
		}

		int elapsed = tick - startTick;
		int ticksLeft = interval - elapsed;

		if (ticksLeft <= 0) {
			// Teleport Player if there are players, assume singleplayer
			LOGGER.info("TP Rand 1 Min would teleport");
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				TeleportTransition transition = Teleporter.TeleportRandomPlayer(server, player);
			}
			startTick = tick;
			ticksLeft = interval;
		}

		if (server.getTickCount() % 20 == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				int secondsLeft = ticksLeft / 20;
				player.sendOverlayMessage(Component.literal(String.format("Teleport in %ds", secondsLeft)));
			}
		}
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
