package com.mcutils;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import java.util.ArrayList;
import java.util.UUID;

public class BossText {
    private static final ServerBossEvent BAR = new ServerBossEvent(
            UUID.randomUUID(),
            Component.literal("Teleport in 60s"),
            BossEvent.BossBarColor.PURPLE,
            BossEvent.BossBarOverlay.PROGRESS
    );

    public static void onTick(MinecraftServer server, float progress, Component text) {
        var online = server.getPlayerList().getPlayers();

        for (ServerPlayer player : new ArrayList<>(BAR.getPlayers())) {
            if (player.isRemoved() || !online.contains(player)) {
                BAR.removePlayer(player);
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!BAR.getPlayers().contains(player)) {
                BAR.addPlayer(player);
            }
        }

        BAR.setProgress(progress);

        BAR.setName(text);
    }
}
