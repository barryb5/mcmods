package com.tprand1min;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.state.BlockState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;

import net.minecraft.util.RandomSource;
import java.util.List;

public class Teleporter {
    public static final Logger LOGGER = LoggerFactory.getLogger("Teleporter");

    public static void Teleport(ServerLevel level, ServerPlayer player, double x, double y, double z) {
        player.teleportTo(level, x, y, z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0, 0, false);
        LOGGER.info("Teleporting to x:{} y:{} z:{}", x, y, z);
    }

    public static void TeleportRandom(MinecraftServer server, ServerPlayer player) {
        int x = 0;
        int z = 0;
        int y = 0;
        BlockState state;
        BlockState state1;
        BlockState state2;
        ServerLevel level;

        for (int i = 0; i < 5; ++i) {
            level = getRandomDimension(server);
            int count = 385;
            x = player.getBlockX() + level.getRandom().nextInt(-1000000, 1000000);
            z = player.getBlockZ() + level.getRandom().nextInt(-1000000, 1000000);
            if (Math.abs(x) > 30000000) {
                x = level.getRandom().nextInt(-10000000, 10000000);
            }
            if (Math.abs(z) > 30000000) {
                z = level.getRandom().nextInt(-10000000, 10000000);
            }
            boolean works = true;
            do {
                y = level.getRandom().nextIntBetweenInclusive(level.getMinY() + 1, level.getMinY() + level.dimensionType().logicalHeight() - 1);
                state = level.getBlockState(new BlockPos(x, y - 1, z));
                state1 = level.getBlockState(new BlockPos(x, y, z));
                state2 = level.getBlockState(new BlockPos(x, y + 1, z));
                count--;
                if (count == 0) {
                    LOGGER.info("Failed to find teleport location 128 times");
                    works = false;
                    break;
                }
            } while (!((state.isFaceSturdy(level, new BlockPos(x, y - 1, z), Direction.UP) || state.getFluidState().is(FluidTags.WATER))
                        && (state1.isAir() || state1.getFluidState().is(FluidTags.WATER))
                        && (state2.isAir() || state2.getFluidState().is(FluidTags.WATER))));

            if (works) {
                Teleport(level, player, x + .5, y, z + .5);
                break;
            }
        }
    }

    public static ServerLevel getRandomDimension(MinecraftServer server) {
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);

        if (levels.isEmpty()) {
            return server.overworld();
        }

        RandomSource random = server.overworld().getRandom();
        return levels.get(random.nextInt(levels.size()));
    }

}
