/*
 * This file is part of Jiffy, licensed under the MIT License (MIT).
 *
 * Copyright (c) OreCruncher
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.blockartistry.world;

import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.BlockEventData;
import net.minecraft.block.material.Material;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityTracker;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.INpc;
import net.minecraft.entity.effect.EntityLightningBolt;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityWaterMob;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.network.play.server.S19PacketEntityStatus;
import net.minecraft.network.play.server.S24PacketBlockAction;
import net.minecraft.network.play.server.S27PacketExplosion;
import net.minecraft.network.play.server.S2APacketParticles;
import net.minecraft.network.play.server.S2BPacketChangeGameState;
import net.minecraft.network.play.server.S2CPacketSpawnGlobalEntity;
import net.minecraft.profiler.Profiler;
import net.minecraft.scoreboard.ScoreboardSaveData;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerManager;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.IntHashMap;
import net.minecraft.util.ReportedException;
import net.minecraft.util.Vec3;
import net.minecraft.util.WeightedRandom;
import net.minecraft.util.WeightedRandomChestContent;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.Explosion;
import net.minecraft.world.GameRules;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.Teleporter;
import net.minecraft.world.World;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.biome.BiomeGenBase.SpawnListEntry;
import net.minecraft.world.biome.WorldChunkManager;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.gen.feature.WorldGeneratorBonusChest;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraftforge.common.ChestGenHooks;
import static net.minecraftforge.common.ChestGenHooks.BONUS_CHEST;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.event.world.WorldEvent;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.blockartistry.world.gen.ChunkProviderServer;

public class WorldServer extends World {

	private static final Logger logger = LogManager.getLogger();
	private final MinecraftServer mcServer;
	private final EntityTracker theEntityTracker;
	private final PlayerManager thePlayerManager;
	public ChunkProviderServer theChunkProviderServer;
	/** Whether or not level saving is enabled */
	public boolean levelSaving;
	/** is false if there are no players */
	private boolean allPlayersSleeping;
	private int updateEntityTick;

	// Use a priority queue for ordering
	private static final int QUEUE_SIZE = 10000;
	private PriorityQueue<NextTickListEntry> pendingTickListEntries;
	private Set<NextTickListEntry> containment;

	/**
	 * the teleporter to use when the entity is being transferred into the
	 * dimension
	 */
	private final Teleporter worldTeleporter;
	private final SpawnerAnimals animalSpawner = new SpawnerAnimals();

	private WorldServer.ServerBlockEventList[] field_147490_S = new WorldServer.ServerBlockEventList[] {
			new WorldServer.ServerBlockEventList(null), new WorldServer.ServerBlockEventList(null) };
	private int blockEventCacheIndex;

	public static final WeightedRandomChestContent[] bonusChestContent = new WeightedRandomChestContent[] {
			new WeightedRandomChestContent(Items.stick, 0, 1, 3, 10),
			new WeightedRandomChestContent(Item.getItemFromBlock(Blocks.planks), 0, 1, 3, 10),
			new WeightedRandomChestContent(Item.getItemFromBlock(Blocks.log), 0, 1, 3, 10),
			new WeightedRandomChestContent(Items.stone_axe, 0, 1, 1, 3),
			new WeightedRandomChestContent(Items.wooden_axe, 0, 1, 1, 5),
			new WeightedRandomChestContent(Items.stone_pickaxe, 0, 1, 1, 3),
			new WeightedRandomChestContent(Items.wooden_pickaxe, 0, 1, 1, 5),
			new WeightedRandomChestContent(Items.apple, 0, 2, 3, 5),
			new WeightedRandomChestContent(Items.bread, 0, 2, 3, 3),
			new WeightedRandomChestContent(Item.getItemFromBlock(Blocks.log2), 0, 1, 3, 10) };

	/** An IntHashMap of entity IDs (integers) to their Entity objects. */
	private IntHashMap entityIdMap;

	public List<Teleporter> customTeleporters = new ArrayList<Teleporter>();

	private final GameRules rules;

	// Helper method to construct objects that expect a
	// Minecraft WorldServer parameter rather than the
	// class override. At runtime things will work.
	@SuppressWarnings("unchecked")
	private static <T> T produce(Class<T> clazz, Object... init) {
		try {
			return (T) clazz.getConstructors()[0].newInstance(init);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return null;
	}

	public WorldServer(MinecraftServer p_i45284_1_, ISaveHandler p_i45284_2_, String p_i45284_3_, int p_i45284_4_,
			WorldSettings p_i45284_5_, Profiler p_i45284_6_) throws InstantiationException, IllegalAccessException,
					IllegalArgumentException, InvocationTargetException, NoSuchMethodException, SecurityException {
		super(p_i45284_2_, p_i45284_3_, p_i45284_5_, WorldProvider.getProviderForDimension(p_i45284_4_), p_i45284_6_);
		this.mcServer = p_i45284_1_;

		this.theEntityTracker = produce(EntityTracker.class, this);
		this.thePlayerManager = produce(PlayerManager.class, this);

		// Possible to have been initialized by World
		// because of initial world gen.
		if (this.entityIdMap == null)
			this.entityIdMap = new IntHashMap();
		if (this.pendingTickListEntries == null)
			this.pendingTickListEntries = new PriorityQueue<NextTickListEntry>(QUEUE_SIZE);
		if (this.containment == null)
			containment = new HashSet<NextTickListEntry>(QUEUE_SIZE);

		this.rules = this.getGameRules();

		this.worldTeleporter = produce(Teleporter.class, this);
		this.worldScoreboard = new ServerScoreboard(p_i45284_1_);
		ScoreboardSaveData scoreboardsavedata = (ScoreboardSaveData) this.mapStorage.loadData(ScoreboardSaveData.class,
				"scoreboard");

		if (scoreboardsavedata == null) {
			scoreboardsavedata = new ScoreboardSaveData();
			this.mapStorage.setData("scoreboard", scoreboardsavedata);
		}

		// Forge: We fix the global mapStorage, which causes us to
		// share scoreboards early. So don't associate the save data
		// with the temporary scoreboard
		if (!((Object) this instanceof WorldServerMulti)) {
			scoreboardsavedata.func_96499_a(this.worldScoreboard);
		}
		((ServerScoreboard) this.worldScoreboard).func_96547_a(scoreboardsavedata);
		DimensionManager.setWorld(p_i45284_4_, this.thePlayerManager.getWorldServer());
	}

	/**
	 * Runs a single tick for the world
	 */
	public void tick() {
		super.tick();

		if (this.getWorldInfo().isHardcoreModeEnabled() && this.difficultySetting != EnumDifficulty.HARD) {
			this.difficultySetting = EnumDifficulty.HARD;
		}

		this.provider.worldChunkMgr.cleanupCache();

		if (this.areAllPlayersAsleep()) {
			if (this.rules.getGameRuleBooleanValue("doDaylightCycle")) {
				long i = this.worldInfo.getWorldTime() + 24000L;
				this.worldInfo.setWorldTime(i - i % 24000L);
			}

			this.wakeAllPlayers();
		}

		// mobSpawner was here! Move after unloadChunks.

		this.theProfiler.startSection("chunkSource");
		this.chunkProvider.unloadQueuedChunks();
		int j = this.calculateSkylightSubtracted(1.0F);

		if (j != this.skylightSubtracted) {
			this.skylightSubtracted = j;
		}

		this.worldInfo.incrementTotalWorldTime(this.worldInfo.getWorldTotalTime() + 1L);

		if (this.rules.getGameRuleBooleanValue("doDaylightCycle")) {
			this.worldInfo.setWorldTime(this.worldInfo.getWorldTime() + 1L);
		}

		// START mobSpawner
		this.theProfiler.endStartSection("mobSpawner");

		if (this.rules.getGameRuleBooleanValue("doMobSpawning")) {
			this.animalSpawner.findChunksForSpawning(this, this.spawnHostileMobs, this.spawnPeacefulMobs,
					this.worldInfo.getWorldTotalTime() % 400L == 0L);
		}
		// END

		this.theProfiler.endStartSection("tickPending");
		this.tickUpdates(false);
		this.theProfiler.endStartSection("tickBlocks");
		this.func_147456_g();
		this.theProfiler.endStartSection("chunkMap");
		this.thePlayerManager.updatePlayerInstances();
		this.theProfiler.endStartSection("village");
		this.villageCollectionObj.tick();
		this.villageSiegeObj.tick();
		this.theProfiler.endStartSection("portalForcer");
		this.worldTeleporter.removeStalePortalLocations(this.getTotalWorldTime());
		for (Teleporter tele : customTeleporters) {
			tele.removeStalePortalLocations(getTotalWorldTime());
		}
		this.theProfiler.endSection();
		this.func_147488_Z();
	}

	/**
	 * only spawns creatures allowed by the chunkProvider
	 */
	public BiomeGenBase.SpawnListEntry spawnRandomCreature(EnumCreatureType type, int x, int y, int z) {
		@SuppressWarnings("unchecked")
		List<SpawnListEntry> list = this.getChunkProvider().getPossibleCreatures(type, x, y, z);
		list = ForgeEventFactory.getPotentialSpawns(this.thePlayerManager.getWorldServer(), type, x, y, z, list);
		return list != null && !list.isEmpty()
				? (BiomeGenBase.SpawnListEntry) WeightedRandom.getRandomItem(this.rand, list) : null;
	}

	/**
	 * Updates the flag that indicates whether or not all players in the world
	 * are sleeping.
	 */
	@SuppressWarnings("unchecked")
	public void updateAllPlayersSleepingFlag() {
		this.allPlayersSleeping = !this.playerEntities.isEmpty();

		for (final EntityPlayer entityPlayer : (List<EntityPlayer>) this.playerEntities)
			if (!entityPlayer.isPlayerSleeping()) {
				this.allPlayersSleeping = false;
				break;
			}
	}

	@SuppressWarnings("unchecked")
	protected void wakeAllPlayers() {
		this.allPlayersSleeping = false;

		for (final EntityPlayer entityPlayer : (List<EntityPlayer>) this.playerEntities)
			if (!entityPlayer.isPlayerSleeping()) {
				entityPlayer.wakeUpPlayer(false, false, true);
			}

		this.resetRainAndThunder();
	}

	private void resetRainAndThunder() {
		provider.resetRainAndThunder();
	}

	@SuppressWarnings("unchecked")
	public boolean areAllPlayersAsleep() {
		if (this.allPlayersSleeping && !this.isRemote) {

			for (final EntityPlayer entityPlayer : (List<EntityPlayer>) this.playerEntities)
				if (!entityPlayer.isPlayerFullyAsleep()) {
					return false;
				}

			return true;
		}

		return false;
	}

	/**
	 * Sets a new spawn location by finding an uncovered block at a random (x,z)
	 * location in the chunk.
	 */
	@SideOnly(Side.CLIENT)
	public void setSpawnLocation() {
		if (this.worldInfo.getSpawnY() <= 0) {
			this.worldInfo.setSpawnY(64);
		}

		int i = this.worldInfo.getSpawnX();
		int j = this.worldInfo.getSpawnZ();
		int k = 0;

		while (this.getTopBlock(i, j).getMaterial() == Material.air) {
			i += this.rand.nextInt(8) - this.rand.nextInt(8);
			j += this.rand.nextInt(8) - this.rand.nextInt(8);
			++k;

			if (k == 10000) {
				break;
			}
		}

		this.worldInfo.setSpawnX(i);
		this.worldInfo.setSpawnZ(j);
	}

	protected void func_147456_g() {
		super.func_147456_g();
		Iterator<?> iterator = this.activeChunkSet.iterator();

		while (iterator.hasNext()) {
			ChunkCoordIntPair chunkcoordintpair = (ChunkCoordIntPair) iterator.next();
			int k = chunkcoordintpair.chunkXPos * 16;
			int l = chunkcoordintpair.chunkZPos * 16;
			this.theProfiler.startSection("getChunk");
			Chunk chunk = this.getChunkFromChunkCoords(chunkcoordintpair.chunkXPos, chunkcoordintpair.chunkZPos);
			this.func_147467_a(k, l, chunk);
			this.theProfiler.endStartSection("tickChunk");
			chunk.func_150804_b(false);
			this.theProfiler.endStartSection("thunder");
			int i1;
			int j1;
			int k1;
			int l1;

			if (provider.canDoLightning(chunk) && this.rand.nextInt(100000) == 0 && this.isRaining()
					&& this.isThundering()) {
				this.updateLCG = this.updateLCG * 3 + 1013904223;
				i1 = this.updateLCG >> 2;
				j1 = k + (i1 & 15);
				k1 = l + (i1 >> 8 & 15);
				l1 = this.getPrecipitationHeight(j1, k1);

				if (this.canLightningStrikeAt(j1, l1, k1)) {
					this.addWeatherEffect(new EntityLightningBolt(this, (double) j1, (double) l1, (double) k1));
				}
			}

			this.theProfiler.endStartSection("iceandsnow");

			if (provider.canDoRainSnowIce(chunk) && this.rand.nextInt(16) == 0) {
				this.updateLCG = this.updateLCG * 3 + 1013904223;
				i1 = this.updateLCG >> 2;
				j1 = i1 & 15;
				k1 = i1 >> 8 & 15;
				l1 = this.getPrecipitationHeight(j1 + k, k1 + l);

				if (this.isBlockFreezableNaturally(j1 + k, l1 - 1, k1 + l)) {
					this.setBlock(j1 + k, l1 - 1, k1 + l, Blocks.ice);
				}

				if (this.isRaining() && this.func_147478_e(j1 + k, l1, k1 + l, true)) {
					this.setBlock(j1 + k, l1, k1 + l, Blocks.snow_layer);
				}

				if (this.isRaining()) {
					BiomeGenBase biomegenbase = this.getBiomeGenForCoords(j1 + k, k1 + l);

					if (biomegenbase.canSpawnLightningBolt()) {
						this.getBlock(j1 + k, l1 - 1, k1 + l).fillWithRain(this, j1 + k, l1 - 1, k1 + l);
					}
				}
			}

			this.theProfiler.endStartSection("tickBlocks");
			ExtendedBlockStorage[] aextendedblockstorage = chunk.getBlockStorageArray();
			j1 = aextendedblockstorage.length;

			for (k1 = 0; k1 < j1; ++k1) {
				ExtendedBlockStorage extendedblockstorage = aextendedblockstorage[k1];

				if (extendedblockstorage != null && extendedblockstorage.getNeedsRandomTick()) {
					for (int i3 = 0; i3 < 3; ++i3) {
						this.updateLCG = this.updateLCG * 3 + 1013904223;
						int i2 = this.updateLCG >> 2;
						int j2 = i2 & 15;
						int k2 = i2 >> 8 & 15;
						int l2 = i2 >> 16 & 15;
						Block block = extendedblockstorage.getBlockByExtId(j2, l2, k2);

						if (block.getTickRandomly()) {
							block.updateTick(this, j2 + k, l2 + extendedblockstorage.getYLocation(), k2 + l, this.rand);
						}
					}
				}
			}

			this.theProfiler.endSection();
		}
	}

	/**
	 * Returns true if the given block will receive a scheduled tick in this
	 * tick. Args: X, Y, Z, Block
	 */
	public boolean isBlockTickScheduledThisTick(int x, int y, int z, Block block) {
		// Events are pulled from the list as needed meaning
		// the this tick list isn't required.
		return false;

		// Not sure how this is needed. The pendingTickListEntriesThisTick list
		// is intialized and cleared during tickUpdate() meaning that when this
		// is called the list will always be empty. Keeping around just in case
		// this logic is "crash recoverable".

		// Check the most common case first - empty list.
		// if (this.pendingTickListEntriesThisTick.isEmpty())
		// return false;

		// If for some reason it isn't empty, do a search
		// return this.pendingTickListEntriesThisTick.contains(new
		// NextTickListEntry(x, y, z, block));
	}

	/**
	 * Schedules a tick to a block with a delay (Most commonly the tick rate)
	 */
	public void scheduleBlockUpdate(int x, int y, int z, Block block, int delay) {
		this.scheduleBlockUpdateWithPriority(x, y, z, block, delay, 0);
	}

	public void scheduleBlockUpdateWithPriority(int x, int y, int z, Block block, int delay, int priority) {

		// Can't tick air
		if (block.getMaterial() == Material.air)
			return;

		// Keeping here as a note for future when it may be restored.
		// boolean isForced = getPersistentChunks().containsKey(new
		// ChunkCoordIntPair(nextticklistentry.xCoord >> 4,
		// nextticklistentry.zCoord >> 4));
		// byte b0 = isForced ? 0 : 8;
		int b0 = 0;

		if (this.scheduledUpdatesAreImmediate) {
			if (block.func_149698_L()) {
				b0 = 8;

				if (this.checkChunksExist(x - b0, y - b0, z - b0, x + b0, y + b0, z + b0)) {
					final Block block1 = this.getBlock(x, y, z);

					if (block1.getMaterial() != Material.air && block1 == block) {
						block1.updateTick(this, x, y, z, this.rand);
					}
				}

				return;
			}

			delay = 1;
		}

		if (this.checkChunksExist(x - b0, y - b0, z - b0, x + b0, y + b0, z + b0))
			this.func_147446_b(x, y, z, block, delay, priority);
	}

	// Used by the chunk load routines to insert a tick event from a save.
	// Newly used by the existing logic to have a common funnel point.
	public void func_147446_b(int x, int y, int z, Block block, int delay, int priority) {
		if (block.getMaterial() == Material.air)
			return;

		final NextTickListEntry tickEntry = new NextTickListEntry(x, y, z, block);

		// If we can add it isn't a duplicate
		if (this.containment.add(tickEntry)) {
			tickEntry.setPriority(priority);
			tickEntry.setScheduledTime((long) delay + this.worldInfo.getWorldTotalTime());
			this.pendingTickListEntries.add(tickEntry);
		}
	}

	/**
	 * Updates (and cleans up) entities and tile entities
	 */
	public void updateEntities() {
		if (this.playerEntities.isEmpty() && getPersistentChunks().isEmpty()) {
			if (this.updateEntityTick++ >= 1200) {
				return;
			}
		} else {
			this.resetUpdateEntityTick();
		}

		super.updateEntities();
	}

	/**
	 * Resets the updateEntityTick field to 0
	 */
	public void resetUpdateEntityTick() {
		this.updateEntityTick = 0;
	}

	/**
	 * Runs through the list of updates to run and ticks them
	 */
	// This value should probably scale based on load and
	// server capabilities.
	private static final int MAX_EVENTS_PER_TICK = 1000;

	public boolean tickUpdates(boolean p_72955_1_) {
		// Cap the number of events that are going to be
		// processed this tick.
		int eventsToProcess = Math.min(MAX_EVENTS_PER_TICK, this.pendingTickListEntries.size());

		this.theProfiler.startSection("ticking");
		while (eventsToProcess-- > 0) {
			// Is it time to process the next event? If not terminate
			// the loop because we are done.
			if (!p_72955_1_ && this.pendingTickListEntries.peek().scheduledTime > this.worldInfo.getWorldTotalTime())
				break;

			final NextTickListEntry tickEntry = this.pendingTickListEntries.poll();
			this.containment.remove(tickEntry);

			// Only update if the chunk is loaded. The chunkExists() name
			// doesn't match what is really being checked. The reason for the
			// check is that the chunk may have been saved/unloaded and the tick
			// info saved with it. When the chunk loads again those events will
			// be queued for processing.
			if (this.chunkExists(tickEntry.xCoord >> 4, tickEntry.zCoord >> 4)) {
				// Get the target of our desire
				final Block block = this.getBlock(tickEntry.xCoord, tickEntry.yCoord, tickEntry.zCoord);

				// If it's air, or not the block that is expected go to the next
				// entry
				if (block.getMaterial() == Material.air || !Block.isEqualTo(block, tickEntry.func_151351_a()))
					continue;

				// Do the update
				try {
					block.updateTick(this, tickEntry.xCoord, tickEntry.yCoord, tickEntry.zCoord, this.rand);
				} catch (Throwable throwable1) {
					CrashReport crashreport = CrashReport.makeCrashReport(throwable1,
							"Exception while ticking a block");
					CrashReportCategory crashreportcategory = crashreport.makeCategory("Block being ticked");
					int k;

					try {
						k = this.getBlockMetadata(tickEntry.xCoord, tickEntry.yCoord, tickEntry.zCoord);
					} catch (Throwable throwable) {
						k = -1;
					}

					CrashReportCategory.func_147153_a(crashreportcategory, tickEntry.xCoord, tickEntry.yCoord,
							tickEntry.zCoord, block, k);
					throw new ReportedException(crashreport);
				}
			}
		}

		this.theProfiler.endSection();
		return !this.pendingTickListEntries.isEmpty();
	}

	// Called by the NBT serializer during chunk save. It looks for tick updates
	// for items related to the chunk so it can save them to the persistent
	// stream.
	public List<NextTickListEntry> getPendingBlockUpdates(final Chunk chunk, final boolean removeFlag) {

		final ArrayList<NextTickListEntry> arraylist = new ArrayList<NextTickListEntry>();
		final ChunkCoordIntPair chunkcoordintpair = chunk.getChunkCoordIntPair();
		int i = (chunkcoordintpair.chunkXPos << 4) - 2;
		int j = i + 16 + 2;
		int k = (chunkcoordintpair.chunkZPos << 4) - 2;
		int l = k + 16 + 2;

		// pendingTickListEntriesThisTick should be empty because it is filled
		// and drained during tickUpdate(). Leaving things in tact because the
		// logic could be crash recoverable.

		// if (!this.pendingTickListEntriesThisTick.isEmpty()) {
		// logger.debug("toBeTicked = " +
		// this.pendingTickListEntriesThisTick.size());
		// }

		/*
		 * for (int x = 0; x < 2; x++) { final Iterator<NextTickListEntry>
		 * iterator = x == 0 ? this.pendingTickListEntries.iterator() :
		 * this.pendingTickListEntriesThisTick.iterator();
		 */
		final Iterator<NextTickListEntry> iterator = this.pendingTickListEntries.iterator();
		while (iterator.hasNext()) {
			final NextTickListEntry tickEntry = iterator.next();
			if (tickEntry.xCoord >= i && tickEntry.xCoord < j && tickEntry.zCoord >= k && tickEntry.zCoord < l) {
				arraylist.add(tickEntry);

				// Remove if forced. Otherwise they will be removed
				// in a lazy fashion when tickUpdate() occurs.
				if (removeFlag) {
					iterator.remove();
					this.containment.remove(tickEntry);
				}
			}
		}
		// }

		return arraylist.isEmpty() ? null : arraylist;
	}

	/**
	 * Will update the entity in the world if the chunk the entity is in is
	 * currently loaded or its forced to update. Args: entity, forceUpdate
	 */
	public void updateEntityWithOptionalForce(Entity entity, boolean forceUpdate) {
		if (!this.mcServer.getCanSpawnAnimals()
				&& (entity instanceof EntityAnimal || entity instanceof EntityWaterMob)) {
			entity.setDead();
		}

		if (!this.mcServer.getCanSpawnNPCs() && entity instanceof INpc) {
			entity.setDead();
		}

		super.updateEntityWithOptionalForce(entity, forceUpdate);
	}

	/**
	 * Creates the chunk provider for this world. Called in the constructor.
	 * Retrieves provider from worldProvider?
	 */
	protected IChunkProvider createChunkProvider() {
		IChunkLoader ichunkloader = this.saveHandler.getChunkLoader(this.provider);
		this.theChunkProviderServer = new ChunkProviderServer(this, ichunkloader, this.provider.createChunkGenerator());
		return this.theChunkProviderServer;
	}

	public List<TileEntity> func_147486_a(int x1, int y1, int z1, int x2, int y2, int z2) {
		final ArrayList<TileEntity> arraylist = new ArrayList<TileEntity>();

		for (int x = (x1 >> 4); x <= (x2 >> 4); x++) {
			for (int z = (z1 >> 4); z <= (z2 >> 4); z++) {
				final Chunk chunk = getChunkFromChunkCoords(x, z);
				if (chunk != null) {
					for (final Object obj : chunk.chunkTileEntityMap.values()) {
						final TileEntity entity = (TileEntity) obj;
						if (!entity.isInvalid()) {
							if (entity.xCoord >= x1 && entity.yCoord >= y1 && entity.zCoord >= z1 && entity.xCoord <= x2
									&& entity.yCoord <= y2 && entity.zCoord <= z2) {
								arraylist.add(entity);
							}
						}
					}
				}
			}
		}

		return arraylist;
	}

	/**
	 * Called when checking if a certain block can be mined or not. The 'spawn
	 * safe zone' check is located here.
	 */
	public boolean canMineBlock(EntityPlayer p_72962_1_, int p_72962_2_, int p_72962_3_, int p_72962_4_) {
		return super.canMineBlock(p_72962_1_, p_72962_2_, p_72962_3_, p_72962_4_);
	}

	public boolean canMineBlockBody(EntityPlayer par1EntityPlayer, int par2, int par3, int par4) {
		return !this.mcServer.isBlockProtected(this, par2, par3, par4, par1EntityPlayer);
	}

	protected void initialize(WorldSettings settings) {
		// This is here because the World CTOR triggers world
		// gen that can occur *before* WorldServer has a chance
		// to initialize. :\
		if (this.entityIdMap == null)
			this.entityIdMap = new IntHashMap();
		if (this.pendingTickListEntries == null)
			this.pendingTickListEntries = new PriorityQueue<NextTickListEntry>(QUEUE_SIZE);
		if (this.containment == null)
			containment = new HashSet<NextTickListEntry>(QUEUE_SIZE);

		this.createSpawnPosition(settings);
		super.initialize(settings);
	}

	/**
	 * creates a spawn position at random within 256 blocks of 0,0
	 */
	protected void createSpawnPosition(WorldSettings p_73052_1_) {
		if (!this.provider.canRespawnHere()) {
			this.worldInfo.setSpawnPosition(0, this.provider.getAverageGroundLevel(), 0);
		} else {
			if (net.minecraftforge.event.ForgeEventFactory.onCreateWorldSpawn(this, p_73052_1_))
				return;
			this.findingSpawnPoint = true;
			WorldChunkManager worldchunkmanager = this.provider.worldChunkMgr;
			List<?> list = worldchunkmanager.getBiomesToSpawnIn();
			Random random = new Random(this.getSeed());
			ChunkPosition chunkposition = worldchunkmanager.findBiomePosition(0, 0, 256, list, random);
			int i = 0;
			int j = this.provider.getAverageGroundLevel();
			int k = 0;

			if (chunkposition != null) {
				i = chunkposition.chunkPosX;
				k = chunkposition.chunkPosZ;
			} else {
				logger.warn("Unable to find spawn biome");
			}

			int l = 0;

			while (!this.provider.canCoordinateBeSpawn(i, k)) {
				i += random.nextInt(64) - random.nextInt(64);
				k += random.nextInt(64) - random.nextInt(64);
				++l;

				if (l == 1000) {
					break;
				}
			}

			this.worldInfo.setSpawnPosition(i, j, k);
			this.findingSpawnPoint = false;

			if (p_73052_1_.isBonusChestEnabled()) {
				this.createBonusChest();
			}
		}
	}

	/**
	 * Creates the bonus chest in the world.
	 */
	protected void createBonusChest() {
		WorldGeneratorBonusChest worldgeneratorbonuschest = new WorldGeneratorBonusChest(
				ChestGenHooks.getItems(BONUS_CHEST, rand), ChestGenHooks.getCount(BONUS_CHEST, rand));

		for (int i = 0; i < 10; ++i) {
			int j = this.worldInfo.getSpawnX() + this.rand.nextInt(6) - this.rand.nextInt(6);
			int k = this.worldInfo.getSpawnZ() + this.rand.nextInt(6) - this.rand.nextInt(6);
			int l = this.getTopSolidOrLiquidBlock(j, k) + 1;

			if (worldgeneratorbonuschest.generate(this, this.rand, j, l, k)) {
				break;
			}
		}
	}

	/**
	 * Gets the hard-coded portal location to use when entering this dimension.
	 */
	public ChunkCoordinates getEntrancePortalLocation() {
		return this.provider.getEntrancePortalLocation();
	}

	/**
	 * Saves all chunks to disk while updating progress bar.
	 */
	public void saveAllChunks(final boolean saveAll, final IProgressUpdate progress) throws MinecraftException {
		if (this.chunkProvider.canSave()) {
			if (progress != null) {
				progress.displayProgressMessage("Saving level");
			}

			this.saveLevel();

			if (progress != null) {
				progress.resetProgresAndWorkingMessage("Saving chunks");
			}

			this.chunkProvider.saveChunks(saveAll, progress);
			MinecraftForge.EVENT_BUS.post(new WorldEvent.Save(this));

			for (final Chunk chunk : this.theChunkProviderServer.func_152380_a()) {
				if (chunk != null && !this.thePlayerManager.func_152621_a(chunk.xPosition, chunk.zPosition)) {
					this.theChunkProviderServer.unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
				}
			}
		}
	}

	/**
	 * saves chunk data - currently only called during execution of the Save All
	 * command
	 */
	public void saveChunkData() {
		if (this.chunkProvider.canSave()) {
			this.chunkProvider.saveExtraData();
		}
	}

	/**
	 * Saves the chunks to disk.
	 */
	protected void saveLevel() throws MinecraftException {
		this.checkSessionLock();
		this.saveHandler.saveWorldInfoWithPlayer(this.worldInfo,
				this.mcServer.getConfigurationManager().getHostPlayerData());
		this.mapStorage.saveAllData();
		this.perWorldStorage.saveAllData();
	}

	public void onEntityAdded(Entity p_72923_1_) {
		super.onEntityAdded(p_72923_1_);
		this.entityIdMap.addKey(p_72923_1_.getEntityId(), p_72923_1_);
		Entity[] aentity = p_72923_1_.getParts();

		if (aentity != null) {
			for (int i = 0; i < aentity.length; ++i) {
				this.entityIdMap.addKey(aentity[i].getEntityId(), aentity[i]);
			}
		}
	}

	public void onEntityRemoved(Entity p_72847_1_) {
		super.onEntityRemoved(p_72847_1_);
		this.entityIdMap.removeObject(p_72847_1_.getEntityId());
		Entity[] aentity = p_72847_1_.getParts();

		if (aentity != null) {
			for (int i = 0; i < aentity.length; ++i) {
				this.entityIdMap.removeObject(aentity[i].getEntityId());
			}
		}
	}

	/**
	 * Returns the Entity with the given ID, or null if it doesn't exist in this
	 * World.
	 */
	public Entity getEntityByID(int entityId) {
		return (Entity) this.entityIdMap.lookup(entityId);
	}

	/**
	 * adds a lightning bolt to the list of lightning bolts in this world.
	 */
	public boolean addWeatherEffect(Entity p_72942_1_) {
		if (super.addWeatherEffect(p_72942_1_)) {
			this.mcServer.getConfigurationManager().sendToAllNear(p_72942_1_.posX, p_72942_1_.posY, p_72942_1_.posZ,
					512.0D, this.provider.dimensionId, new S2CPacketSpawnGlobalEntity(p_72942_1_));
			return true;
		} else {
			return false;
		}
	}

	/**
	 * sends a Packet 38 (Entity Status) to all tracked players of that entity
	 */
	public void setEntityState(Entity p_72960_1_, byte p_72960_2_) {
		this.getEntityTracker().func_151248_b(p_72960_1_, new S19PacketEntityStatus(p_72960_1_, p_72960_2_));
	}

	/**
	 * returns a new explosion. Does initiation (at time of writing Explosion is
	 * not finished)
	 */
	public Explosion newExplosion(Entity p_72885_1_, double x, double y, double z, float p_72885_8_, boolean isFlaming,
			boolean isSmoking) {
		Explosion explosion = new Explosion(this, p_72885_1_, x, y, z, p_72885_8_);
		explosion.isFlaming = isFlaming;
		explosion.isSmoking = isSmoking;
		if (net.minecraftforge.event.ForgeEventFactory.onExplosionStart(this, explosion))
			return explosion;
		explosion.doExplosionA();
		explosion.doExplosionB(false);

		if (!isSmoking) {
			explosion.affectedBlockPositions.clear();
		}

		Iterator<?> iterator = this.playerEntities.iterator();

		while (iterator.hasNext()) {
			EntityPlayer entityplayer = (EntityPlayer) iterator.next();

			if (entityplayer.getDistanceSq(x, y, z) < 4096.0D) {
				((EntityPlayerMP) entityplayer).playerNetServerHandler
						.sendPacket(new S27PacketExplosion(x, y, z, p_72885_8_, explosion.affectedBlockPositions,
								(Vec3) explosion.func_77277_b().get(entityplayer)));
			}
		}

		return explosion;
	}

	/**
	 * Adds a block event with the given Args to the blockEventCache. During the
	 * next tick(), the block specified will have its onBlockEvent handler
	 * called with the given parameters. Args: X,Y,Z, Block, EventID,
	 * EventParameter
	 */
	public void addBlockEvent(int x, int y, int z, Block block, int eventId, int eventParm) {
		// Because the event list is a Set duplicates cannot be inserted
		this.field_147490_S[this.blockEventCacheIndex].add(new BlockEventData(x, y, z, block, eventId, eventParm));
	}

	private void func_147488_Z() {
		while (!this.field_147490_S[this.blockEventCacheIndex].isEmpty()) {
			int i = this.blockEventCacheIndex;
			this.blockEventCacheIndex ^= 1;

			for (final BlockEventData eventData : this.field_147490_S[i]) {
				if (this.func_147485_a(eventData)) {
					this.mcServer.getConfigurationManager().sendToAllNear((double) eventData.func_151340_a(),
							(double) eventData.func_151342_b(), (double) eventData.func_151341_c(), 64.0D,
							this.provider.dimensionId,
							new S24PacketBlockAction(eventData.func_151340_a(), eventData.func_151342_b(),
									eventData.func_151341_c(), eventData.getBlock(), eventData.getEventID(),
									eventData.getEventParameter()));
				}
			}

			this.field_147490_S[i].clear();
		}
	}

	private boolean func_147485_a(BlockEventData event) {
		final Block block = this.getBlock(event.func_151340_a(), event.func_151342_b(), event.func_151341_c());
		return block == event.getBlock() ? block.onBlockEventReceived(this, event.func_151340_a(),
				event.func_151342_b(), event.func_151341_c(), event.getEventID(), event.getEventParameter()) : false;
	}

	/**
	 * Syncs all changes to disk and wait for completion.
	 */
	public void flush() {
		this.saveHandler.flush();
	}

	/**
	 * Updates all weather states.
	 */
	protected void updateWeather() {
		boolean flag = this.isRaining();
		super.updateWeather();

		if (this.prevRainingStrength != this.rainingStrength) {
			this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
					new S2BPacketChangeGameState(7, this.rainingStrength), this.provider.dimensionId);
		}

		if (this.prevThunderingStrength != this.thunderingStrength) {
			this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
					new S2BPacketChangeGameState(8, this.thunderingStrength), this.provider.dimensionId);
		}

		/*
		 * The function in use here has been replaced in order to only send the
		 * weather info to players in the correct dimension, rather than to all
		 * players on the server. This is what causes the client-side rain, as
		 * the client believes that it has started raining locally, rather than
		 * in another dimension.
		 */
		if (flag != this.isRaining()) {
			if (flag) {
				this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
						new S2BPacketChangeGameState(2, 0.0F), this.provider.dimensionId);
			} else {
				this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
						new S2BPacketChangeGameState(1, 0.0F), this.provider.dimensionId);
			}

			this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
					new S2BPacketChangeGameState(7, this.rainingStrength), this.provider.dimensionId);
			this.mcServer.getConfigurationManager().sendPacketToAllPlayersInDimension(
					new S2BPacketChangeGameState(8, this.thunderingStrength), this.provider.dimensionId);
		}
	}

	protected int func_152379_p() {
		return this.mcServer.getConfigurationManager().getViewDistance();
	}

	public MinecraftServer func_73046_m() {
		return this.mcServer;
	}

	/**
	 * Gets the EntityTracker
	 */
	public EntityTracker getEntityTracker() {
		return this.theEntityTracker;
	}

	public PlayerManager getPlayerManager() {
		return this.thePlayerManager;
	}

	public Teleporter getDefaultTeleporter() {
		return this.worldTeleporter;
	}

	public void func_147487_a(String p_147487_1_, double p_147487_2_, double p_147487_4_, double p_147487_6_,
			int p_147487_8_, double p_147487_9_, double p_147487_11_, double p_147487_13_, double p_147487_15_) {
		S2APacketParticles s2apacketparticles = new S2APacketParticles(p_147487_1_, (float) p_147487_2_,
				(float) p_147487_4_, (float) p_147487_6_, (float) p_147487_9_, (float) p_147487_11_,
				(float) p_147487_13_, (float) p_147487_15_, p_147487_8_);

		for (int j = 0; j < this.playerEntities.size(); ++j) {
			EntityPlayerMP entityplayermp = (EntityPlayerMP) this.playerEntities.get(j);
			ChunkCoordinates chunkcoordinates = entityplayermp.getPlayerCoordinates();
			double d7 = p_147487_2_ - (double) chunkcoordinates.posX;
			double d8 = p_147487_4_ - (double) chunkcoordinates.posY;
			double d9 = p_147487_6_ - (double) chunkcoordinates.posZ;
			double d10 = d7 * d7 + d8 * d8 + d9 * d9;

			if (d10 <= 256.0D) {
				entityplayermp.playerNetServerHandler.sendPacket(s2apacketparticles);
			}
		}
	}

	public File getChunkSaveLocation() {
		return ((AnvilChunkLoader) theChunkProviderServer.currentChunkLoader).chunkSaveLocation;
	}

	@SuppressWarnings("serial")
	static class ServerBlockEventList extends HashSet<BlockEventData> {
		private ServerBlockEventList() {
		}

		ServerBlockEventList(Object p_i1521_1_) {
			this();
		}
	}
}