package com.tpmobtype1min;

import com.mcutils.BossText;
import com.mcutils.Teleporter;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.RandomSource;
import net.minecraft.util.Util;
import net.minecraft.util.random.Weighted;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TpMobType1Min implements ModInitializer {
	public static final String MOD_ID = "tpmobtype1min";
	public static int ticksUntilTeleport = 20 * 60;

	// Chunk radius loaded around the matching position in other dimensions while picking
	private static final int LOAD_RADIUS = 4;
	// Ticks to wait after loading other dimensions before picking, so their chunks/entities are ready
	private static final int PREPARE_TICKS = 100;
	// Ticks before the teleport that the chunks holding saved mobs get loaded again
	private static final int RELOAD_TICKS = 100;
	// Random positions checked per other dimension when picking
	private static final int SCAN_ATTEMPTS = 400;
	// Max mobs spawned in other dimensions per round
	private static final int MAX_SPAWNS = 20;

	private static final List<MobCategory> SPAWN_CATEGORIES = Arrays.stream(MobCategory.values())
			.filter(c -> c != MobCategory.MISC)
			.toList();

	public static List<EntityType<?>> mobs = new ArrayList<>();
	public static EntityType<?> current = null;

	// Mobs of `current` found/spawned in other dimensions this round, and the chunk they were last seen in
	private record SavedMob(ResourceKey<Level> dimension, ChunkPos chunk) {}
	private static final Map<UUID, SavedMob> saved = new HashMap<>();

	// A spot in another dimension where a mob type passed all natural spawn checks
	private record SpawnSpot(ServerLevel level, BlockPos pos) {}

	// Server tick the current pick started at, -1 when not picking
	private static int pickStartTick = -1;

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

		// Bosses: teleporting the dragon out of the End breaks the fight
		mobs.remove(EntityTypes.ENDER_DRAGON);
		mobs.remove(EntityTypes.WITHER);
	}

	private void onTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		int tickInCycle = server.getTickCount() % ticksUntilTeleport;

		if (!players.isEmpty()) {
			// Everything is anchored to the first player (singleplayer assumption)
			ServerPlayer player = players.getFirst();

			// Teleport at the end of the cycle, then start picking the next mob
			if (tickInCycle == 0 && current != null) {
				teleportCurrent(server, player);
				current = null;
				saved.clear();
			}

			if (current == null) {
				if (pickStartTick < 0) {
					pickStartTick = server.getTickCount();
					loadOtherDimensions(server, player);
				} else if (server.getTickCount() - pickStartTick >= PREPARE_TICKS) {
					pickAndFill(server, player);
					pickStartTick = -1;
				}
			}

			if (server.getTickCount() % 20 == 0 && !saved.isEmpty()) {
				trackSaved(server, tickInCycle >= ticksUntilTeleport - RELOAD_TICKS);
			}
		}

		// Boss bar countdown (updated every tick so the bar drains smoothly;
		// ServerBossEvent only sends packets when the name/progress actually change)
		int ticksLeft = ticksUntilTeleport - tickInCycle;
		int secondsLeft = (ticksLeft + 19) / 20; // round up: 60..1
		float progress = (float) ticksLeft / ticksUntilTeleport; // 1.0 -> 0.0
		if (current != null) {
			BossText.onTick(server, progress, Component.literal(String.format("Teleporting %s to player in %ds", current.getDescription().getString(), secondsLeft)));
		} else {
			BossText.onTick(server, 1.f, Component.literal("Searching for mobs"));
		}
	}

	private static void teleportCurrent(MinecraftServer server, ServerPlayer player) {
		LOGGER.info("TP Mob Type 1 Min teleporting all {}", BuiltInRegistries.ENTITY_TYPE.getKey(current));
		EntityType<?> type = current;
		for (ServerLevel level : server.getAllLevels()) {
			Teleporter.TeleportAllEntities(level, player.level(), player.position(), (Entity e) -> e.getType() == type);
		}
	}

	private static void loadOtherDimensions(MinecraftServer server, ServerPlayer player) {
		for (ServerLevel level : server.getAllLevels()) {
			if (level != player.level()) {
				level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, ChunkPos.containing(posIn(level, player)), LOAD_RADIUS);
			}
		}
	}

	private static void pickAndFill(MinecraftServer server, ServerPlayer player) {
		RandomSource random = server.overworld().getRandom();
		ServerLevel home = player.level();

		// Player's own dimension: vanilla spawning handles it, just ask what can spawn where they stand
		Set<EntityType<?>> candidates = new HashSet<>();
		for (MobCategory category : SPAWN_CATEGORIES) {
			for (Weighted<MobSpawnSettings.SpawnerData> entry : mobsAt(home, player.blockPosition(), category)) {
				candidates.add(entry.value().type());
			}
		}

		// Other dimensions: find actual valid spawn spots in the loaded chunks
		Map<EntityType<?>, List<SpawnSpot>> spots = new HashMap<>();
		for (ServerLevel level : server.getAllLevels()) {
			if (level != home) {
				scanForSpots(level, ChunkPos.containing(posIn(level, player)), random, spots);
			}
		}
		candidates.addAll(spots.keySet());
		candidates.retainAll(new HashSet<>(mobs));

		if (candidates.isEmpty()) {
			LOGGER.info("No mobs can spawn nearby, picking any mob");
			current = getRandomMobAny(random);
		} else {
			List<EntityType<?>> list = new ArrayList<>(candidates);
			current = list.get(random.nextInt(list.size()));
		}
		LOGGER.info("Picked {} out of {} candidates", BuiltInRegistries.ENTITY_TYPE.getKey(current), candidates.size());

		// Save mobs of this type that already exist in other dimensions
		EntityType<?> type = current;
		for (ServerLevel level : server.getAllLevels()) {
			if (level != home) {
				for (Entity e : level.getEntities(EntityTypeTest.forClass(Entity.class), e -> e.getType() == type)) {
					saved.put(e.getUUID(), new SavedMob(level.dimension(), e.chunkPosition()));
				}
			}
		}
		int existing = saved.size();

		// Fill the spots found for this type
		List<SpawnSpot> found = new ArrayList<>(spots.getOrDefault(type, List.of()));
		Util.shuffle(found, random);
		int spawned = 0;
		for (SpawnSpot spot : found) {
			if (spawned >= MAX_SPAWNS) {
				break;
			}
			BlockPos pos = spot.pos();
			// Re-check room, an earlier spawn may have taken this spot
			if (!spot.level().noCollision(type.getSpawnAABB(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5))) {
				continue;
			}
			Entity e = type.spawn(spot.level(), pos, EntitySpawnReason.NATURAL);
			if (e != null) {
				saved.put(e.getUUID(), new SavedMob(spot.level().dimension(), e.chunkPosition()));
				spawned++;
			}
		}
		LOGGER.info("Saved {} {} from other dimensions ({} existing, {} spawned)", saved.size(), BuiltInRegistries.ENTITY_TYPE.getKey(type), existing, spawned);
	}

	private static void scanForSpots(ServerLevel level, ChunkPos center, RandomSource random, Map<EntityType<?>, List<SpawnSpot>> spots) {
		for (int i = 0; i < SCAN_ATTEMPTS; i++) {
			int cx = center.x() + random.nextIntBetweenInclusive(-LOAD_RADIUS, LOAD_RADIUS);
			int cz = center.z() + random.nextIntBetweenInclusive(-LOAD_RADIUS, LOAD_RADIUS);

			// Chunks/entities load async after the ticket is added, skip any that aren't ready
			if (!level.getChunkSource().hasChunk(cx, cz) || !level.areEntitiesLoaded(ChunkPos.pack(cx, cz))) {
				continue;
			}

			// Random position from the bottom of the world up to the surface
			int x = (cx << 4) + random.nextInt(16);
			int z = (cz << 4) + random.nextInt(16);
			int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) + 1;
			if (top <= level.getMinY()) {
				continue;
			}
			BlockPos pos = new BlockPos(x, random.nextIntBetweenInclusive(level.getMinY(), top), z);

			if (level.getBlockState(pos).isRedstoneConductor(level, pos)) {
				continue;
			}

			for (MobCategory category : SPAWN_CATEGORIES) {
				for (Weighted<MobSpawnSettings.SpawnerData> entry : mobsAt(level, pos, category)) {
					EntityType<?> type = entry.value().type();
					if (SpawnPlacements.isSpawnPositionOk(type, level, pos)
							&& SpawnPlacements.checkSpawnRules(type, level, EntitySpawnReason.NATURAL, pos, random)
							&& level.noCollision(type.getSpawnAABB(x + 0.5, pos.getY(), z + 0.5))) {
						spots.computeIfAbsent(type, k -> new ArrayList<>()).add(new SpawnSpot(level, pos));
					}
				}
			}
		}
	}

	private static List<Weighted<MobSpawnSettings.SpawnerData>> mobsAt(ServerLevel level, BlockPos pos, MobCategory category) {
		return level.getChunkSource().getGenerator().getMobsAt(level, level.structureManager(), category, pos).unwrap();
	}

	private static void trackSaved(MinecraftServer server, boolean reload) {
		Iterator<Map.Entry<UUID, SavedMob>> it = saved.entrySet().iterator();
		while (it.hasNext()) {
			Map.Entry<UUID, SavedMob> entry = it.next();
			ServerLevel level = server.getLevel(entry.getValue().dimension());
			if (level == null) {
				it.remove();
				continue;
			}

			Entity e = level.getEntity(entry.getKey());
			if (e != null) {
				if (!e.isAlive()) {
					it.remove();
					continue;
				}
				entry.setValue(new SavedMob(level.dimension(), e.chunkPosition()));
			}

			if (reload) {
				level.getChunkSource().addTicketWithRadius(TicketType.PORTAL, entry.getValue().chunk(), 2);
			}
		}
	}

	private static BlockPos posIn(ServerLevel target, ServerPlayer player) {
		double scale = DimensionType.getTeleportationScale(player.level().dimensionType(), target.dimensionType());
		return BlockPos.containing(player.getX() * scale, player.getY(), player.getZ() * scale);
	}

	public static EntityType<?> getRandomMobAny(RandomSource random) {
		return mobs.get(random.nextInt(mobs.size()));
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
