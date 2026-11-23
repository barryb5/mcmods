package com.tprand1min;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Set;

import net.minecraft.util.RandomSource;
import java.util.List;
import java.util.function.Predicate;

public class Teleporter {
    public static final Logger LOGGER = LoggerFactory.getLogger("Teleporter");

    public static TeleportTransition TeleportPlayer(ServerLevel level, ServerPlayer player, Vec3 location) {
        LOGGER.info("Teleporting to x:{} y:{} z:{}", location.x, location.y, location.z);
        if (player.teleportTo(level, location.x, location.y, location.z, Set.of(Relative.X_ROT, Relative.Y_ROT), 0, 0, false)) {
            return new TeleportTransition(level, location, Vec3.ZERO, Relative.X_ROT.ordinal(), Relative.Y_ROT.ordinal(), TeleportTransition.DO_NOTHING);
        }
        return null;
    }

    public static void TeleportEntity(ServerLevel level, Entity entity, Vec3 location) {
        entity.teleport(new TeleportTransition(level, location, Vec3.ZERO, entity.getYRot(), entity.getXRot(), TeleportTransition.DO_NOTHING));
    }

    public static void TeleportAllEntities(ServerLevel fromlevel, ServerLevel toLevel, Vec3 location, Predicate<Entity> filter) {
        List<? extends Entity> entities = fromlevel.getEntities(EntityTypeTest.forClass(Entity.class), filter != null ? filter : entity -> true);

        int count = 0;
        for (Entity entity : entities) {
            // skip if is a passenger, the vehicle entity will teleport them
            // prob doesn't work if filter filters out the vehicle but w/e
            if (entity.isPassenger()) {
                continue;
            }
            TeleportEntity(toLevel, entity, location);
        }
        LOGGER.info("Teleported {} entities", count);
    }

    public static TeleportTransition TeleportRandomPlayer(MinecraftServer server, ServerPlayer player) {
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
                return TeleportPlayer(level, player, new Vec3(x + .5, y, z + .5));
            }
        }
        return null;
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
