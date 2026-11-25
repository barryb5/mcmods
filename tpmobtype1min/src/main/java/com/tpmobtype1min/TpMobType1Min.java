package com.tpmobtype1min;

import com.mcutils.Teleporter;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TpMobType1Min implements ModInitializer {
	public static final String MOD_ID = "tpmobtype1min";
	public static int ticksUntilTeleport = 20 * 60;
	public static List<EntityType<?>> mobs = new ArrayList<>();
	public static Set<EntityType<?>> existingMobs = new HashSet<>();
	public static EntityType<?> current = null;
	private static final int LOAD_RADIUS = 4;
	private static final int SPAWN_ATTEMPTS = 1000;
	private static final int MAX_SPAWNS = 50;

	// Logger
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		LOGGER.info("TP Mob Type 1 Min initialized");
		ServerTickEvents.END_SERVER_TICK.register(this::onTick);

		for (EntityType<?> entityType : BuiltInRegistries.ENTITY_TYPE) {
			if (entityType.getCategory() != MobCategory.MISC) {
				mobs.add(entityType);
			}
		}
		mobs.add(EntityTypes.VILLAGER);
		mobs.add(EntityTypes.IRON_GOLEM);
		mobs.add(EntityTypes.WANDERING_TRADER);
		mobs.add(EntityTypes.SNOW_GOLEM);
		mobs.add(EntityTypes.COPPER_GOLEM);
	}

	private void onTick(MinecraftServer server) {
		if (current == null && server.getPlayerList().getPlayers().isEmpty()) {
			// First tick only
			current = getRandomMobAny();
		} else if (current == null && !server.getPlayerList().getPlayers().isEmpty()) {
			current = getRandomMob(server, server.getPlayerList().getPlayers().getFirst());
		}

		// Text
		if (server.getTickCount() % 20 == 0) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				int secondsLeft = (ticksUntilTeleport/20) - ((server.getTickCount() % ticksUntilTeleport) / 20);
				if (current != null) {
					player.sendOverlayMessage(Component.literal(String.format("Teleporting all %s to player in %ds", current.getDescription().getString(), secondsLeft)));
				} else {
					player.sendOverlayMessage(Component.literal("Searching for mobs"));
				}
			}
		}

		// Load chunks in other dimensions
		if (server.getTickCount() % ticksUntilTeleport == 200) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				loadOtherDimensions(server, player);
			}
		}

		if (server.getTickCount() % ticksUntilTeleport == 290) {
			for (ServerLevel level : server.getAllLevels()) {
				for (ServerPlayer player : server.getPlayerList().getPlayers()) {
					// Load current mob in other dimensions too since portal won't trigger hostile mob spawns
					List<BlockPos> playerPos = getPlayerBlockPos(player);
					ChunkPos chunkPos = ChunkPos.containing(playerPos.getFirst());
					ChunkPos netherChunkPos = ChunkPos.containing(playerPos.getLast());

					spawnNaturally(level, level.dimension() == Level.NETHER ? netherChunkPos : chunkPos, current);
				}
			}
		}

		// Teleport mobs
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			if (server.getTickCount() % ticksUntilTeleport == 0) {
				LOGGER.info("TP Mob Type 1 Min teleport");
				Vec3 pos = new Vec3(player.position().x, player.position().y + 1, player.position().z);
				for (ServerLevel level : server.getAllLevels()) {
					Teleporter.TeleportAllEntities(level, player.level(), player.position(), (Entity e) -> { return e.getType() == current; });
				}
				// update current
				current = getRandomMob(server, player); // new random initial mob
			}
		}
	}

	private void loadOtherDimensions(MinecraftServer server, ServerPlayer player) {
		List<BlockPos> playerPos = getPlayerBlockPos(player);
		ChunkPos chunkPos = ChunkPos.containing(playerPos.getFirst());
		ChunkPos netherChunkPos = ChunkPos.containing(playerPos.getLast());

		for (ServerLevel level : server.getAllLevels()) {
			ChunkPos center = level.dimension() == Level.NETHER ? netherChunkPos : chunkPos;
			level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, center, LOAD_RADIUS);

			// Load current mob in other dimensions too since portal won't trigger hostile mob spawns
			if (level != player.level()) {
				spawnNaturally(level, center, current);
			}
		}
	}

	private static void spawnNaturally(ServerLevel level, ChunkPos center, EntityType<?> type) {
		RandomSource random = level.getRandom();
		MobCategory category = type.getCategory();
		int spawned = 0;

		for (int i = 0; i < SPAWN_ATTEMPTS && spawned < MAX_SPAWNS; i++) {
			// Random chunk within the loaded radius
			int cx = center.x() + random.nextIntBetweenInclusive(-LOAD_RADIUS, LOAD_RADIUS);
			int cz = center.z() + random.nextIntBetweenInclusive(-LOAD_RADIUS, LOAD_RADIUS);

			// Chunks/entities load async after the ticket is added, skip until they're ready
			if (!level.getChunkSource().hasChunk(cx, cz) || !level.areEntitiesLoaded(ChunkPos.pack(cx, cz))) {
				continue;
			}

			// Random position in that chunk, from bottom of the world up to the surface (same as vanilla)
			int x = (cx << 4) + random.nextInt(16);
			int z = (cz << 4) + random.nextInt(16);
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
			if (top <= level.getMinY()) {
				continue;
			}
			int y = random.nextIntBetweenInclusive(level.getMinY(), top);
			BlockPos pos = new BlockPos(x, y, z);

			// Does this mob spawn
			boolean inSpawnList = false;
			for (Weighted<MobSpawnSettings.SpawnerData> entry : level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), category, pos).unwrap()) {
				if (entry.value().type() == type) {
					inSpawnList = true;
					break;
				}
			}
			if (!inSpawnList) {
				continue;
			}

			// Valid spot: on ground/in water as needed, mob-specific rules like light level, and room for the hitbox
			if (!SpawnPlacements.isSpawnPositionOk(type, level, pos)
					|| !SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, random)
					|| !level.noCollision(type.getSpawnAABB(x + 0.5, y, z + 0.5))) {
				continue;
			}

			if (type.spawn(level, pos, EntitySpawnReason.NATURAL) != null) {
				spawned++;
			}
		}

		if (spawned > 0) {
			LOGGER.info("Naturally spawned {} {} in {}", spawned, BuiltInRegistries.ENTITY_TYPE.getKey(type), level.dimension().identifier());
		}
	}

	public static EntityType<?> getRandomMob(MinecraftServer server, ServerPlayer player) {
		Random random = new Random();
		existingMobs.clear();

		List<BlockPos> playerPos = getPlayerBlockPos(player);

		for (MobCategory catagory : MobCategory.values()) {
			for (ServerLevel level : server.getAllLevels()) {
				for (Weighted<MobSpawnSettings.SpawnerData> entry : level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), catagory, level.dimension() != Level.NETHER ? playerPos.getFirst() : playerPos.getLast()).unwrap()) {
					existingMobs.add(entry.value().type());
				}
			}
		}

		if (!existingMobs.isEmpty()) {
			return new ArrayList<>(existingMobs).get(random.nextInt(existingMobs.size()));
		}

		// If there are no mobs loaded, try spawning a cow
		LOGGER.info("No mobs available to spawn");
		return getRandomMobAny();
	}

	public static EntityType<?> getRandomMobAny() {
		Random random = new Random();
		return mobs.get(random.nextInt(mobs.size() - 1));
	}

	public static List<BlockPos> getPlayerBlockPos(ServerPlayer player) {
		Vec3 pos = new Vec3(player.position().x, player.position().y + 1, player.position().z);
		Vec3 netherPos = new Vec3(player.position().x/8, player.position().y + 1, player.position().z/8);

		if (player.level().dimension() == Level.NETHER) {
			netherPos = pos;
			pos = new Vec3(player.position().x*8, player.position().y + 1, player.position().z*8);
		}

		return List.of(BlockPos.containing(pos), BlockPos.containing(netherPos));
	}
	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
