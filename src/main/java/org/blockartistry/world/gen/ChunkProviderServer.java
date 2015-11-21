/* This file is part of Jiffy, licensed under the MIT License (MIT).
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

package org.blockartistry.world.gen;

import cpw.mods.fml.common.registry.GameRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.crash.CrashReport;
import net.minecraft.crash.CrashReportCategory;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IProgressUpdate;
import net.minecraft.util.LongHashMap;
import net.minecraft.util.ReportedException;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.ForgeChunkManager;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.blockartistry.common.chunkio.ChunkIOExecutor;

public class ChunkProviderServer implements IChunkProvider {
	private static final Logger logger = LogManager.getLogger();
	@SuppressWarnings("unused")
	private static final String __OBFID = "CL_00001436";

	private final Chunk defaultEmptyChunk;
	public final IChunkProvider currentChunkProvider;
	public final IChunkLoader currentChunkLoader;

	public boolean loadChunkOnProvideRequest = true;
	public final WorldServer worldObj;
	
	private final Set<Long> chunksToUnload = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
	private final Set<Long> loadingChunks = com.google.common.collect.Sets.newHashSet();
	public final LongHashMap cache = new LongHashMap();
	public final List<Chunk> loadedChunks = new ArrayList<Chunk>();
	private final boolean worryAboutSpawn;

	public static long toLong(final int chunkX, final int chunkZ) {
		return ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
	}
	
	public ChunkProviderServer(final WorldServer world, final IChunkLoader loader, final IChunkProvider provider) {
		this.defaultEmptyChunk = new EmptyChunk(world, 0, 0);
		this.worldObj = world;
		this.currentChunkLoader = loader;
		this.currentChunkProvider = provider;

		// Do a static calc to speed things up if possible
		this.worryAboutSpawn = this.worldObj.provider.canRespawnHere()
				&& DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId);
	}

	/**
	 * Checks to see if a chunk exists at x, y
	 */
	public boolean chunkExists(final int chunkX, final int chunkZ) {
		return this.cache.containsItem(toLong(chunkX, chunkZ));
	}

	public List<Chunk> func_152380_a() {
		return this.loadedChunks;
	}

	/**
	 * marks chunk for unload by "unload100OldestChunks" if there is no spawn
	 * point, or if the center of the chunk is outside 200 blocks (x or z) of
	 * the spawn
	 */
	private final static short RANGE = 128;

	public void unloadChunksIfNotNearSpawn(final int chunkX, final int chunkZ) {
		if (this.worryAboutSpawn) {
			final ChunkCoordinates chunkcoordinates = this.worldObj.getSpawnPoint();
			final int k = chunkX * 16 + 8 - chunkcoordinates.posX;
			if (k >= -RANGE && k <= RANGE) {
				final int l = chunkZ * 16 + 8 - chunkcoordinates.posZ;
				if (l >= -RANGE && l <= RANGE)
					return;
			}
		}

		// Toss on another log...
		this.chunksToUnload.add(toLong(chunkX, chunkZ));
	}

	/**
	 * marks all chunks for unload, ignoring those near the spawn
	 */
	public void unloadAllChunks() {
		for (final Chunk chunk : func_152380_a())
			this.unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
	}

	/**
	 * loads or generates the chunk at the chunk location specified
	 */
	public Chunk loadChunk(final int chunkX, final int chunkZ) {
		return loadChunk(chunkX, chunkZ, null);
	}

	public Chunk loadChunk(final int chunkX, final int chunkZ, final Runnable runnable) {
		final long key = toLong(chunkX, chunkZ);
		this.chunksToUnload.remove(key);
		Chunk chunk = (Chunk) this.cache.getValueByKey(key);

		if (chunk == null) {
			final AnvilChunkLoader loader = (this.currentChunkLoader instanceof AnvilChunkLoader)
					? (AnvilChunkLoader) this.currentChunkLoader : null;

			// We can only use the queue for already generated chunks
			if (loader != null && loader.chunkExists(this.worldObj, chunkX, chunkZ)) {
				if (runnable != null) {
					ChunkIOExecutor.queueChunkLoad(this.worldObj, loader, this, chunkX, chunkZ, runnable);
					return null;
				} else {
					chunk = ChunkIOExecutor.syncChunkLoad(this.worldObj, loader, this, chunkX, chunkZ);
				}
			} else {
				chunk = this.originalLoadChunk(chunkX, chunkZ);
			}
		}

		// If we didn't load the chunk async and have a callback run it now
		if (runnable != null) {
			runnable.run();
		}

		if(chunk == null) {
			logger.error("Chunk is null?!?!?!?");
		}
		return chunk;
	}

	public Chunk originalLoadChunk(final int chunkX, final int chunkZ) {
		final long key = toLong(chunkX, chunkZ);
		this.chunksToUnload.remove(key);
		Chunk chunk = (Chunk) this.cache.getValueByKey(key);

		if (chunk == null) {
			boolean added = this.loadingChunks.add(key);
			if (!added) {
				cpw.mods.fml.common.FMLLog.bigWarning(
						"There is an attempt to load a chunk (%d,%d) in dimension %d that is already being loaded. This will cause weird chunk breakages.",
						chunkX, chunkZ, worldObj.provider.dimensionId);
			}
			chunk = ForgeChunkManager.fetchDormantChunk(key, this.worldObj);
			if (chunk == null) {
				chunk = this.safeLoadChunk(chunkX, chunkZ);
			}

			if (chunk == null) {
				if (this.currentChunkProvider == null) {
					chunk = this.defaultEmptyChunk;
				} else {
					try {
						chunk = this.currentChunkProvider.provideChunk(chunkX, chunkZ);
					} catch (final Throwable throwable) {
						CrashReport crashreport = CrashReport.makeCrashReport(throwable,
								"Exception generating new chunk");
						CrashReportCategory crashreportcategory = crashreport.makeCategory("Chunk to be generated");
						crashreportcategory.addCrashSection("Location", String.format("%d,%d",
								new Object[] { Integer.valueOf(chunkX), Integer.valueOf(chunkZ) }));
						crashreportcategory.addCrashSection("Position hash",
								ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
						crashreportcategory.addCrashSection("Generator", this.currentChunkProvider.makeString());
						throw new ReportedException(crashreport);
					}
				}
			}
			
			if(chunk == null) {
				logger.error("originalLoadChunk(): obtained null chunk?");
			}

			this.cache.add(key, chunk);
			this.loadedChunks.add(chunk);
			this.loadingChunks.remove(key);
			chunk.onChunkLoad();
			chunk.populateChunk(this, this, chunkX, chunkZ);
		}

		return chunk;
	}

	/**
	 * Will return back a chunk, if it doesn't exist and its not a MP client it
	 * will generates all the blocks for the specified chunk from the map seed
	 * and chunk seed
	 */
	public Chunk provideChunk(final int chunkX, final int chunkZ) {
		final Chunk chunk = (Chunk) this.cache.getValueByKey(toLong(chunkX, chunkZ));
		return chunk == null ? (!this.worldObj.findingSpawnPoint && !this.loadChunkOnProvideRequest
				? this.defaultEmptyChunk : this.loadChunk(chunkX, chunkZ)) : chunk;
	}

	/**
	 * used by loadChunk, but catches any exceptions if the load fails.
	 */
	private Chunk safeLoadChunk(int chunkX, int chunkZ) {
		if (this.currentChunkLoader == null)
			return null;

		try {
			final Chunk chunk = this.currentChunkLoader.loadChunk(this.worldObj, chunkX, chunkZ);

			if (chunk != null) {
				chunk.lastSaveTime = this.worldObj.getTotalWorldTime();

				if (this.currentChunkProvider != null) {
					this.currentChunkProvider.recreateStructures(chunkX, chunkZ);
				}
			}

			return chunk;
		} catch (Exception exception) {
			logger.error("Couldn\'t load chunk", exception);
		}

		return null;
	}

	/**
	 * used by saveChunks, but catches any exceptions if the save fails.
	 */
	private void safeSaveExtraChunkData(final Chunk chunk) {
		if (this.currentChunkLoader != null) {
			try {
				this.currentChunkLoader.saveExtraChunkData(this.worldObj, chunk);
			} catch (Exception exception) {
				logger.error("Couldn\'t save entities", exception);
			}
		}
	}

	/**
	 * used by saveChunks, but catches any exceptions if the save fails.
	 */
	private void safeSaveChunk(final Chunk chunk) {
		if (this.currentChunkLoader != null) {
			try {
				chunk.lastSaveTime = this.worldObj.getTotalWorldTime();
				this.currentChunkLoader.saveChunk(this.worldObj, chunk);
			} catch (IOException ioexception) {
				logger.error("Couldn\'t save chunk", ioexception);
			} catch (MinecraftException minecraftexception) {
				logger.error("Couldn\'t save chunk; already in use by another instance of Minecraft?",
						minecraftexception);
			}
		}
	}

	/**
	 * Populates chunk with ores etc etc
	 */
	public void populate(final IChunkProvider provider, final int chunkX, final int chunkZ) {
		final Chunk chunk = this.provideChunk(chunkX, chunkZ);

		if (!chunk.isTerrainPopulated) {
			chunk.func_150809_p();

			if (this.currentChunkProvider != null) {
				this.currentChunkProvider.populate(provider, chunkX, chunkZ);
				GameRegistry.generateWorld(chunkX, chunkZ, worldObj, currentChunkProvider, provider);
				chunk.setChunkModified();
			}
		}
	}

	/**
	 * Two modes of operation: if passed true, save all Chunks in one go. If
	 * passed false, save up to two chunks. Return true if all chunks have been
	 * saved.
	 */
	private final static int SAVE_LIMIT = 25;

	public boolean saveChunks(final boolean saveAll, final IProgressUpdate notUsed) {
		int throttle = 0;
		for (final Chunk chunk : func_152380_a()) {
			if (saveAll) {
				this.safeSaveExtraChunkData(chunk);
			}

			if (chunk.needsSaving(saveAll)) {
				this.safeSaveChunk(chunk);
				chunk.isModified = false;

				if (!saveAll && ++throttle == SAVE_LIMIT) {
					return false;
				}
			}
		}

		return true;
	}

	/**
	 * Save extra data not associated with any Chunk. Not saved during autosave,
	 * only during world unload. Currently unimplemented.
	 */
	public void saveExtraData() {
		if (this.currentChunkLoader != null) {
			this.currentChunkLoader.saveExtraData();
		}
	}

	/**
	 * Unloads chunks that are marked to be unloaded. This is not guaranteed to
	 * unload every such chunk.
	 */
	private static final int UNLOAD_LIMIT = 100;

	public boolean unloadQueuedChunks() {
		if (!this.worldObj.levelSaving) {

			for (final ChunkCoordIntPair forced : this.worldObj.getPersistentChunks().keySet())
				this.chunksToUnload.remove(toLong(forced.chunkXPos, forced.chunkZPos));

			int throttle = 0;
			while (!this.chunksToUnload.isEmpty() && throttle < UNLOAD_LIMIT) {
				final long key = this.chunksToUnload.iterator().next();
				final Chunk chunk = (Chunk) this.cache.getValueByKey(key);

				if (chunk != null) {
					chunk.onChunkUnload();
					// TODO: Need to find out...
					// Original code did a blind save
					// Not sure if that is really needed...
					if (chunk.needsSaving(true)) {
						throttle++;
						this.safeSaveChunk(chunk);
						this.safeSaveExtraChunkData(chunk);
					}
					this.cache.remove(key);
					this.loadedChunks.remove(chunk);
					ForgeChunkManager.putDormantChunk(key, chunk);
					if (this.cache.getNumHashElements() == 0
							&& ForgeChunkManager.getPersistentChunksFor(this.worldObj).size() == 0
							&& !DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId)) {
						DimensionManager.unloadWorld(this.worldObj.provider.dimensionId);
						return this.currentChunkProvider.unloadQueuedChunks();
					}
				}

				this.chunksToUnload.remove(key);
			}

			if (this.currentChunkLoader != null) {
				this.currentChunkLoader.chunkTick();
			}
		}

		return this.currentChunkProvider.unloadQueuedChunks();
	}

	/**
	 * Returns if the IChunkProvider supports saving.
	 */
	public boolean canSave() {
		return !this.worldObj.levelSaving;
	}

	/**
	 * Converts the instance data to a readable string.
	 */
	public String makeString() {
		return "ServerChunkCache: " + getLoadedChunkCount() + " Drop: " + this.chunksToUnload.size();
	}

	/**
	 * Returns a list of creatures of the specified type that can spawn at the
	 * given location.
	 */
	@SuppressWarnings("rawtypes")
	public List getPossibleCreatures(EnumCreatureType p_73155_1_, int p_73155_2_, int p_73155_3_, int p_73155_4_) {
		return this.currentChunkProvider.getPossibleCreatures(p_73155_1_, p_73155_2_, p_73155_3_, p_73155_4_);
	}

	public ChunkPosition func_147416_a(World p_147416_1_, String p_147416_2_, int p_147416_3_, int p_147416_4_,
			int p_147416_5_) {
		return this.currentChunkProvider.func_147416_a(p_147416_1_, p_147416_2_, p_147416_3_, p_147416_4_, p_147416_5_);
	}

	public int getLoadedChunkCount() {
		return (int) this.cache.getNumHashElements();
	}

	public void recreateStructures(int p_82695_1_, int p_82695_2_) {
	}
}