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
import java.util.AbstractList;
import java.util.List;
import java.util.Set;
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

	private long[] chunksToUnload = new long[1000];
	private int newChunksToUnload = 0;

	private void addToChunksToUnload(final long chunkIdx) {
		if (this.newChunksToUnload == this.chunksToUnload.length) {
			final long[] newArray = new long[this.chunksToUnload.length + 500];
			System.arraycopy(this.chunksToUnload, 0, newArray, 0, this.chunksToUnload.length);
			this.chunksToUnload = newArray;
		}
		this.chunksToUnload[this.newChunksToUnload++] = chunkIdx;
	}

	private void removeFromChunksToUnload(final long chunkIdx) {
		for (int i = 0; i < this.newChunksToUnload; i++) {
			if (this.chunksToUnload[i] == chunkIdx) {
				this.chunksToUnload[i] = this.chunksToUnload[--this.newChunksToUnload];
				break;
			}
		}
	}

	private Chunk[] theLoadedChunks = new Chunk[2500];
	private int newLoadedChunk = 0;

	private void addToLoadedChunks(final Chunk chunk) {
		if (this.newLoadedChunk == this.theLoadedChunks.length) {
			final Chunk[] newArray = new Chunk[this.theLoadedChunks.length + 500];
			System.arraycopy(this.theLoadedChunks, 0, newArray, 0, this.theLoadedChunks.length);
			this.theLoadedChunks = newArray;
		}
		this.theLoadedChunks[this.newLoadedChunk++] = chunk;
	}

	private void removeFromLoadedChunks(final Chunk chunk) {
		for (int i = 0; i < this.newLoadedChunk; i++) {
			if (this.theLoadedChunks[i] == chunk) {
				this.newLoadedChunk--;
				if (i == this.newLoadedChunk)
					this.theLoadedChunks[i] = null;
				else {
					this.theLoadedChunks[i] = this.theLoadedChunks[this.newLoadedChunk];
					this.theLoadedChunks[this.newLoadedChunk] = null;
				}
				break;
			}
		}
	}

	// Thing wrapper to make components that consume the 
	// loadedChunks field happy.  Not a lot of error checking.
	// ChunkIOExecutor and Chicken Chunks uses it - not sure
	// what else.
	private class SimpleChunkList extends AbstractList<Chunk> {
		@Override
		public Chunk get(int index) {
			return ChunkProviderServer.this.theLoadedChunks[index];
		}

		@Override
		public int size() {
			return ChunkProviderServer.this.newLoadedChunk;
		}

		@Override
		public boolean add(final Chunk chunk) {
			ChunkProviderServer.this.addToLoadedChunks(chunk);
			return true;
		}

		@Override
		public boolean remove(final Object chunk) {
			ChunkProviderServer.this.removeFromLoadedChunks((Chunk) chunk);
			return true;
		}
	}

	private Chunk defaultEmptyChunk;
	public IChunkProvider currentChunkProvider;
	public IChunkLoader currentChunkLoader;
	/**
	 * if this is false, the defaultEmptyChunk will be returned by the provider
	 */
	public boolean loadChunkOnProvideRequest = true;
	public LongHashMap loadedChunkHashMap = new LongHashMap();
	public List<Chunk> loadedChunks = new SimpleChunkList();
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
		addToChunksToUnload(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
	}

	/**
	 * marks all chunks for unload, ignoring those near the spawn
	 */
	public void unloadAllChunks() {
		for (int i = 0; i < this.newLoadedChunk; i++)
			unloadChunksIfNotNearSpawn(this.theLoadedChunks[i].xPosition, this.theLoadedChunks[i].zPosition);
	}

	/**
	 * loads or generates the chunk at the chunk location specified
	 */
	public Chunk loadChunk(int chunkX, int chunkZ) {
		return loadChunk(chunkX, chunkZ, null);
	}

	public Chunk loadChunk(int chunkX, int chunkZ, Runnable runnable) {
		long key = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
		removeFromChunksToUnload(key);
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
		removeFromChunksToUnload(key);
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
						crashreportcategory.addCrashSection("Location",
								String.format("%d,%d", new Object[] { chunkX, chunkZ }));
						crashreportcategory.addCrashSection("Position hash", key);
						crashreportcategory.addCrashSection("Generator", this.currentChunkProvider.makeString());
						throw new ReportedException(crashreport);
					}
				}
			}

			this.loadedChunkHashMap.add(key, chunk);
			addToLoadedChunks(chunk);
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
		int saveLimit = saveAll ? 0 : 25;

		for (int i = 0; i < this.newLoadedChunk; i++) {
			final Chunk chunk = this.theLoadedChunks[i];
			if (saveAll)
				this.safeSaveExtraChunkData(chunk);

			if (chunk.needsSaving(saveAll)) {
				this.safeSaveChunk(chunk);
				chunk.isModified = false;
				if (--saveLimit == 0)
					return false;
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

	private static final int UNLOAD_SAVE_LIMIT = 100;
	private static boolean ALWAYS_SAVE = true;

	/**
	 * Unloads chunks that are marked to be unloaded. This is not guaranteed to
	 * unload every such chunk.
	 */
	public boolean unloadQueuedChunks() {
		if (canSave()) {

			if (this.newChunksToUnload > 0) {

				for (final ChunkCoordIntPair forced : this.worldObj.getPersistentChunks().keySet()) {
					removeFromChunksToUnload(ChunkCoordIntPair.chunkXZ2Int(forced.chunkXPos, forced.chunkZPos));
				}

				int throttle = 0;
				while (newChunksToUnload > 0 && throttle < UNLOAD_SAVE_LIMIT) {
					final long olong = this.chunksToUnload[--newChunksToUnload];
					final Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(olong);

					if (chunk == null)
						continue;

					chunk.onChunkUnload();
					// TODO: Does it always have to save regardless? Chunk
					// spawns critters before unload for some reason, and it
					// doesn't look like it is marked dirty. May not be a
					// big deal.
					if (ALWAYS_SAVE || chunk.needsSaving(true)) {
						throttle++;
						this.safeSaveChunk(chunk);
						this.safeSaveExtraChunkData(chunk);
					}
					removeFromLoadedChunks(chunk);
					ForgeChunkManager.putDormantChunk(ChunkCoordIntPair.chunkXZ2Int(chunk.xPosition, chunk.zPosition),
							chunk);
					if (this.newLoadedChunk == 0 && ForgeChunkManager.getPersistentChunksFor(this.worldObj).size() == 0
							&& !DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId)) {
						DimensionManager.unloadWorld(this.worldObj.provider.dimensionId);
						return this.currentChunkProvider.unloadQueuedChunks();
					}

					this.loadedChunkHashMap.remove(olong);
				}
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
		return "ServerChunkCache: " + getLoadedChunkCount() + " Drop: " + this.newChunksToUnload;
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
