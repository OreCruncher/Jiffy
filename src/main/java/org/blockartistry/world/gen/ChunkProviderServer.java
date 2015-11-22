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

import com.google.common.collect.Lists;
import cpw.mods.fml.common.registry.GameRegistry;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
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
	/**
	 * used by unload100OldestChunks to iterate the loadedChunkHashMap for
	 * unload (underlying assumption, first in, first out)
	 */
	private Set<Long> chunksToUnload = Collections.newSetFromMap(new ConcurrentHashMap<Long, Boolean>());
	private Chunk defaultEmptyChunk;
	public IChunkProvider currentChunkProvider;
	public IChunkLoader currentChunkLoader;
	/**
	 * if this is false, the defaultEmptyChunk will be returned by the provider
	 */
	public boolean loadChunkOnProvideRequest = true;
	public LongHashMap loadedChunkHashMap = new LongHashMap();
	public List<Chunk> loadedChunks = new ArrayList<Chunk>();
	public WorldServer worldObj;
	private Set<Long> loadingChunks = com.google.common.collect.Sets.newHashSet();
	@SuppressWarnings("unused")
	private static final String __OBFID = "CL_00001436";

	private final boolean worryAboutSpawn;

	public ChunkProviderServer(WorldServer world, IChunkLoader loader, IChunkProvider provider) {
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
	public boolean chunkExists(int chunkX, int chunkZ) {
		return this.loadedChunkHashMap.containsItem(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
	}

	public List<Chunk> func_152380_a() {
		return this.loadedChunks;
	}

	/**
	 * marks chunk for unload by "unload100OldestChunks" if there is no spawn
	 * point, or if the center of the chunk is outside 200 blocks (x or z) of
	 * the spawn
	 */
	private static final int RANGE = 128;

	public void unloadChunksIfNotNearSpawn(int chunkX, int chunkZ) {
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
		this.chunksToUnload.add(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
	}

	/**
	 * marks all chunks for unload, ignoring those near the spawn
	 */
	public void unloadAllChunks() {
		Iterator<Chunk> iterator = this.loadedChunks.iterator();

		while (iterator.hasNext()) {
			Chunk chunk = (Chunk) iterator.next();
			this.unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
		}
	}

	/**
	 * loads or generates the chunk at the chunk location specified
	 */
	public Chunk loadChunk(int chunkX, int chunkZ) {
		return loadChunk(chunkX, chunkZ, null);
	}

	public Chunk loadChunk(int chunkX, int chunkZ, Runnable runnable) {
		long key = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
		this.chunksToUnload.remove(Long.valueOf(key));
		Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(key);
		AnvilChunkLoader loader = null;

		if (this.currentChunkLoader instanceof AnvilChunkLoader) {
			loader = (AnvilChunkLoader) this.currentChunkLoader;
		}

		// We can only use the queue for already generated chunks
		if (chunk == null && loader != null && loader.chunkExists(this.worldObj, chunkX, chunkZ)) {
			if (runnable != null) {
				ChunkIOExecutor.queueChunkLoad(this.worldObj, loader, this, chunkX, chunkZ, runnable);
				return null;
			} else {
				chunk = ChunkIOExecutor.syncChunkLoad(this.worldObj, loader, this, chunkX, chunkZ);
			}
		} else if (chunk == null) {
			chunk = this.originalLoadChunk(chunkX, chunkZ);
		}

		// If we didn't load the chunk async and have a callback run it now
		if (runnable != null) {
			runnable.run();
		}

		return chunk;
	}

	public Chunk originalLoadChunk(int chunkX, int chunkZ) {
		long key = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
		this.chunksToUnload.remove(Long.valueOf(key));
		Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(key);

		if (chunk == null) {
			boolean added = loadingChunks.add(key);
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
					} catch (Throwable throwable) {
						CrashReport crashreport = CrashReport.makeCrashReport(throwable,
								"Exception generating new chunk");
						CrashReportCategory crashreportcategory = crashreport.makeCategory("Chunk to be generated");
						crashreportcategory.addCrashSection("Location", String.format("%d,%d",
								new Object[] { Integer.valueOf(chunkX), Integer.valueOf(chunkZ) }));
						crashreportcategory.addCrashSection("Position hash", Long.valueOf(key));
						crashreportcategory.addCrashSection("Generator", this.currentChunkProvider.makeString());
						throw new ReportedException(crashreport);
					}
				}
			}

			this.loadedChunkHashMap.add(key, chunk);
			this.loadedChunks.add(chunk);
			loadingChunks.remove(key);
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
	public Chunk provideChunk(int chunkX, int chunkZ) {
		Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
		return chunk == null ? (!this.worldObj.findingSpawnPoint && !this.loadChunkOnProvideRequest
				? this.defaultEmptyChunk : this.loadChunk(chunkX, chunkZ)) : chunk;
	}

	/**
	 * used by loadChunk, but catches any exceptions if the load fails.
	 */
	private Chunk safeLoadChunk(int chunkX, int chunkZ) {
		if (this.currentChunkLoader == null) {
			return null;
		} else {
			try {
				Chunk chunk = this.currentChunkLoader.loadChunk(this.worldObj, chunkX, chunkZ);

				if (chunk != null) {
					chunk.lastSaveTime = this.worldObj.getTotalWorldTime();

					if (this.currentChunkProvider != null) {
						this.currentChunkProvider.recreateStructures(chunkX, chunkZ);
					}
				}

				return chunk;
			} catch (Exception exception) {
				logger.error("Couldn\'t load chunk", exception);
				return null;
			}
		}
	}

	/**
	 * used by saveChunks, but catches any exceptions if the save fails.
	 */
	private void safeSaveExtraChunkData(Chunk chunk) {
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
	private void safeSaveChunk(Chunk chunk) {
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
	public void populate(IChunkProvider provider, int chunkX, int chunkZ) {
		Chunk chunk = this.provideChunk(chunkX, chunkZ);

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
	public boolean saveChunks(boolean saveAll, IProgressUpdate notUsed) {
		int i = 0;
		ArrayList<Chunk> arraylist = Lists.newArrayList(this.loadedChunks);

		for (int j = 0; j < arraylist.size(); ++j) {
			Chunk chunk = (Chunk) arraylist.get(j);

			if (saveAll) {
				this.safeSaveExtraChunkData(chunk);
			}

			if (chunk.needsSaving(saveAll)) {
				this.safeSaveChunk(chunk);
				chunk.isModified = false;
				++i;

				if (i == 24 && !saveAll) {
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
			for (ChunkCoordIntPair forced : this.worldObj.getPersistentChunks().keySet()) {
				this.chunksToUnload.remove(ChunkCoordIntPair.chunkXZ2Int(forced.chunkXPos, forced.chunkZPos));
			}

			int throttle = 0;
			while (!this.chunksToUnload.isEmpty() && throttle < UNLOAD_LIMIT) {
				Long olong = (Long) this.chunksToUnload.iterator().next();
				Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(olong.longValue());

				if (chunk != null) {
					chunk.onChunkUnload();
					// TODO: Does it always have to save regardless?
					if (chunk.needsSaving(true)) {
						throttle++;
						this.safeSaveChunk(chunk);
						this.safeSaveExtraChunkData(chunk);
					}
					this.loadedChunks.remove(chunk);
					ForgeChunkManager.putDormantChunk(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition),
							chunk);
					if (loadedChunks.size() == 0 && ForgeChunkManager.getPersistentChunksFor(this.worldObj).size() == 0
							&& !DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId)) {
						DimensionManager.unloadWorld(this.worldObj.provider.dimensionId);
						return currentChunkProvider.unloadQueuedChunks();
					}
				}

				this.chunksToUnload.remove(olong);
				this.loadedChunkHashMap.remove(olong.longValue());
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
		return "ServerChunkCache: " + this.loadedChunkHashMap.getNumHashElements() + " Drop: "
				+ this.chunksToUnload.size();
	}

	/**
	 * Returns a list of creatures of the specified type that can spawn at the
	 * given location.
	 */
	public List<?> getPossibleCreatures(EnumCreatureType p_73155_1_, int p_73155_2_, int p_73155_3_, int p_73155_4_) {
		return this.currentChunkProvider.getPossibleCreatures(p_73155_1_, p_73155_2_, p_73155_3_, p_73155_4_);
	}

	public ChunkPosition func_147416_a(World p_147416_1_, String p_147416_2_, int p_147416_3_, int p_147416_4_,
			int p_147416_5_) {
		return this.currentChunkProvider.func_147416_a(p_147416_1_, p_147416_2_, p_147416_3_, p_147416_4_, p_147416_5_);
	}

	public int getLoadedChunkCount() {
		return this.loadedChunkHashMap.getNumHashElements();
	}

	public void recreateStructures(int p_82695_1_, int p_82695_2_) {
	}
}
