package com.tpmobtype1min;

import com.mcutils.BossText;
import com.mcutils.Teleporter;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleEvents;
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
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRuleCategory;
import net.minecraft.world.level.levelgen.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class TpMobType1Min implements ModInitializer {
	public static final String MOD_ID = "tpmobtype1min";

	public static final GameRule<Boolean> ALL_MOBS = GameRuleBuilder.forBoolean(false).category(GameRuleCategory.PLAYER).buildAndRegister(id("all_mobs"));
	public static final GameRule<Integer> TELEPORT_INTERVAL = GameRuleBuilder.forInteger(60).range(1, 3600).category(GameRuleCategory.PLAYER).buildAndRegister(id("teleport_interval"));

	// Ticks between teleports, kept in sync with the teleport_interval gamerule
	private static int intervalTicks = 20 * 60;
	// Server tick the current cycle (search, then countdown) started at, -1 before the first one
	private static int cycleStartTick = -1;

	// Only in the pool when all_mobs is on: teleporting the dragon out of the End breaks the fight
	private static final List<EntityType<?>> BOSSES = List.of(EntityTypes.ENDER_DRAGON, EntityTypes.WITHER);

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
		// Bosses are added back by onAllMobsChange when all_mobs is on
		mobs.removeAll(BOSSES);

		ServerLifecycleEvents.SERVER_STARTED.register(TpMobType1Min::onServerStarted);
		GameRuleEvents.changeCallback(ALL_MOBS).register(TpMobType1Min::onAllMobsChange);
		GameRuleEvents.changeCallback(TELEPORT_INTERVAL).register(TpMobType1Min::onIntervalChange);
	}

	private static void onServerStarted(MinecraftServer server) {
		// Change callbacks only fire on /gamerule, so apply the values the world was saved with
		onAllMobsChange(server.getGameRules().get(ALL_MOBS), server);
		onIntervalChange(server.getGameRules().get(TELEPORT_INTERVAL), server);

		// Static state outlives a singleplayer world, start the next one fresh
		cycleStartTick = -1;
		current = null;
		saved.clear();
	}

	private static void onAllMobsChange(Boolean allMobs, MinecraftServer server) {
		// Remove first so setting the rule to the same value twice doesn't add duplicates
		mobs.removeAll(BOSSES);
		if (allMobs) {
			mobs.addAll(BOSSES);
		}
	}

	private static void onIntervalChange(Integer seconds, MinecraftServer server) {
		// The countdown is measured from cycleStartTick, so a change applies to the current cycle right away
		intervalTicks = seconds * 20;
	}

	private void onTick(MinecraftServer server) {
		List<ServerPlayer> players = server.getPlayerList().getPlayers();
		if (players.isEmpty()) {
			return;
		}
		// Everything is anchored to the first player (singleplayer assumption)
		ServerPlayer player = players.getFirst();
		int tick = server.getTickCount();

		if (cycleStartTick < 0) {
			startCycle(server, player);
		}
		int elapsed = tick - cycleStartTick;

		if (current == null) {
			// Give the other dimensions' chunks time to load before picking
			if (elapsed >= PREPARE_TICKS) {
				pickAndFill(server, player);
			}
		} else if (elapsed >= intervalTicks) {
			// >= rather than ==, so lowering the interval mid-cycle can't skip the teleport
			teleportCurrent(server, player);
			startCycle(server, player);
			elapsed = 0;
		}

		int ticksLeft = Math.max(intervalTicks - elapsed, 0);
		if (tick % 20 == 0 && !saved.isEmpty()) {
			trackSaved(server, ticksLeft <= RELOAD_TICKS);
		}

		// Boss bar countdown (updated every tick so the bar drains smoothly;
		// ServerBossEvent only sends packets when the name/progress actually change)
		if (current != null) {
			int secondsLeft = (ticksLeft + 19) / 20; // round up
			float progress = (float) ticksLeft / intervalTicks; // 1.0 -> 0.0
			BossText.onTick(server, progress, Component.literal(String.format("Teleporting %s to player in %ds", current.getDescription().getString(), secondsLeft)));
		} else {
			BossText.onTick(server, 1.f, Component.literal("Searching for mobs"));
		}
	}

	// Start searching for the next mob type
	private static void startCycle(MinecraftServer server, ServerPlayer player) {
		cycleStartTick = server.getTickCount();
		current = null;
		saved.clear();
		loadOtherDimensions(server, player);
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
