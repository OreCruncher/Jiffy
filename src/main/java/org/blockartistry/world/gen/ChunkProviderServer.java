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

	// Moving the last element to fill a vacancy created
	// by a remove is much faster than the ArrayList's
	// arraycopy shift from the right. The logic in this
	// class doesn't care about the ordering of elements
	// in the list - just cares about membership.
	private long[] chunksToUnload = new long[2000];
	private int newChunksToUnload = 0;

	private void addToChunksToUnload(final long chunkId) {
		if (this.newChunksToUnload == this.chunksToUnload.length) {
			final long[] newArray = new long[this.chunksToUnload.length + 500];
			System.arraycopy(this.chunksToUnload, 0, newArray, 0, this.chunksToUnload.length);
			this.chunksToUnload = newArray;
		}
		this.chunksToUnload[this.newChunksToUnload++] = chunkId;
	}

	private void removeFromChunksToUnload(final long chunkId) {
		for (int i = 0; i < this.newChunksToUnload; i++) {
			if (this.chunksToUnload[i] == chunkId) {
				this.chunksToUnload[i] = this.chunksToUnload[--this.newChunksToUnload];
				return;
			}
		}
	}

	// Similar to chunksToUnload. Also, a given chunk instance is
	// unique so we can get away with an identity compare rather
	// than an equals().
	private Chunk[] theLoadedChunks = new Chunk[4000];
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

	// Thin wrapper to make components that consume the
	// loadedChunks field happy. Not a lot of error checking.
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
	public World worldObj;
	private Set<Long> loadingChunks = com.google.common.collect.Sets.newHashSet();
	@SuppressWarnings("unused")
	private static final String __OBFID = "CL_00001436";

	private final boolean worryAboutSpawn;

	public ChunkProviderServer(World world, IChunkLoader loader, IChunkProvider provider) {
		this.defaultEmptyChunk = new EmptyChunk(world, 0, 0);
		this.worldObj = world;
		this.currentChunkLoader = loader;
		this.currentChunkProvider = provider;

		// TODO: Do a static calc to speed things up if possible. Need
		// to find out if this assumption is safe.
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

	// Vanilla version does it in blocks, this does it in chunks since
	// the math is simpler as well as the fact we are talking chunks, not
	// blocks.
	private static final int RANGE_CHUNKS = 32;

	public void unloadChunksIfNotNearSpawn(final int chunkX, final int chunkZ) {
		if (this.worryAboutSpawn) {
			// Chunk distance, not block distance.
			final ChunkCoordinates chunkcoordinates = this.worldObj.getSpawnPoint();
			int t = chunkX - (chunkcoordinates.posX >> 4);
			if (t >= -RANGE_CHUNKS && t <= RANGE_CHUNKS) {
				t = chunkZ - (chunkcoordinates.posZ >> 4);
				if (t >= -RANGE_CHUNKS && t <= RANGE_CHUNKS)
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
		for (int i = 0; i < this.newLoadedChunk; i++) {
			final Chunk chunk = this.theLoadedChunks[i];
			unloadChunksIfNotNearSpawn(chunk.xPosition, chunk.zPosition);
		}
	}

	/**
	 * loads or generates the chunk at the chunk location specified
	 */
	public Chunk loadChunk(final int chunkX, final int chunkZ) {
		return loadChunk(chunkX, chunkZ, null);
	}

	public Chunk loadChunk(final int chunkX, final int chunkZ, final Runnable runnable) {
		final long key = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
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

	public Chunk originalLoadChunk(final int chunkX, final int chunkZ) {
		final long key = ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ);
		removeFromChunksToUnload(key);
		Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(key);

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
		Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(ChunkCoordIntPair.chunkXZ2Int(chunkX, chunkZ));
		return chunk == null ? (!this.worldObj.findingSpawnPoint && !this.loadChunkOnProvideRequest
				? this.defaultEmptyChunk : this.loadChunk(chunkX, chunkZ)) : chunk;
	}

	/**
	 * used by loadChunk, but catches any exceptions if the load fails.
	 */
	private Chunk safeLoadChunk(final int chunkX, final int chunkZ) {
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
	public boolean saveChunks(final boolean saveAll, final IProgressUpdate notUsed) {
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

	// Vanilla caps the limit at 100. It is single threaded and saves
	// every chunk regardless of need. Bumped to 300 because chunk
	// IO is multi-threaded and has bandwidth. Also, the only chunks
	// that are counted against this limit are those that actually
	// save.
	//
	// Should point out that increasing the number will also help
	// mitigate the impact of processing chunk loaded chunks. A
	// larger batch size reduces the number of times that loop has to
	// execute.
	private static final int UNLOAD_SAVE_LIMIT = 300;

	// Debug flag to control save behavior while it is being evaluated.
	private static final boolean ALWAYS_SAVE = true;

	/**
	 * Unloads chunks that are marked to be unloaded. This is not guaranteed to
	 * unload every such chunk.
	 */
	@SuppressWarnings("unused")
	public boolean unloadQueuedChunks() {
		if (canSave()) {

			if (this.newChunksToUnload > 0) {

				// This loop can get expensive depending on the number of chunk
				// loaded chunks that are currently active.
				for (final ChunkCoordIntPair forced : this.worldObj.getPersistentChunks().keySet()) {
					removeFromChunksToUnload(ChunkCoordIntPair.chunkXZ2Int(forced.chunkXPos, forced.chunkZPos));
				}

				int throttle = 0;
				while (this.newChunksToUnload > 0 && throttle < UNLOAD_SAVE_LIMIT) {
					final long key = this.chunksToUnload[--this.newChunksToUnload];
					final Chunk chunk = (Chunk) this.loadedChunkHashMap.getValueByKey(key);

					// Shouldn't get this, but better safe than sorry
					if (chunk == null)
						continue;

					// Let folks know the chunk is about to unload
					chunk.onChunkUnload();

					// TODO: Does it always have to save regardless? onChunkUnload()
					// - may trigger chunk changes that are not captured in the
					// modified flag.
					if (ALWAYS_SAVE || chunk.needsSaving(true)) {
						throttle++;
						this.safeSaveChunk(chunk);
						this.safeSaveExtraChunkData(chunk);
					}

					// Cleanup
					removeFromLoadedChunks(chunk);
					this.loadedChunkHashMap.remove(key);
					ForgeChunkManager.putDormantChunk(key, chunk);

					// If we have no loaded chunks and the dimension is not
					// pinned in memory, unload the dimension.
					if (this.newLoadedChunk == 0 && ForgeChunkManager.getPersistentChunksFor(this.worldObj).size() == 0
							&& !DimensionManager.shouldLoadSpawn(this.worldObj.provider.dimensionId)) {
						DimensionManager.unloadWorld(this.worldObj.provider.dimensionId);
						return this.currentChunkProvider.unloadQueuedChunks();
					}
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
		// Have to do this casting because of the cyclic dependency
		// between WorldServer and ChunkProviderServer.  The dependency
		// plays havoc with class loading.
		return !((net.minecraft.world.WorldServer)(this.worldObj)).levelSaving;
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
	public List<?> getPossibleCreatures(EnumCreatureType type, int x, int y, int z) {
		return this.currentChunkProvider.getPossibleCreatures(type, x, y, z);
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
