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

package org.blockartistry.world.chunk.storage;

import cpw.mods.fml.common.FMLLog;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.NextTickListEntry;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.NibbleArray;
import net.minecraft.world.chunk.storage.ExtendedBlockStorage;
import net.minecraft.world.chunk.storage.IChunkLoader;
import net.minecraft.world.storage.IThreadedFileIO;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkDataEvent;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.blockartistry.world.storage.ThreadedFileIOBase;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalCause;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;

/**
 * Implementation of a new AnvilChunkLoader. The improvements that have been
 * made:
 * 
 * + This implementation uses a Cache rather than an ArrayList(). This allows
 * for highly concurrent access between Minecraft and the underlying IO
 * routines. The write to disk occurs when an IO thread comes through and
 * invalidates the cache entry.
 * 
 * + Issue a close() on the ChunkInputStream object when the chunk has been
 * deserialized. This permits the object to be placed back into the object pool.
 * 
 * + Refine the scope of locks that need to be held so that they are as narrow
 * as possible.
 * 
 * + Removed the session lock check during chunk save. The check was opening and
 * reading the lock file every time a chunk save came through. Mechanism should
 * be changed to use a file as a semaphore by obtaining an exclusive lock when
 * the world loads and croak at that time if there is contention.
 *
 */
public class AnvilChunkLoader implements IChunkLoader, IThreadedFileIO {

	private static final Logger logger = LogManager.getLogger("AnvilChunkLoader");
	public final File chunkSaveLocation;

	// Interned version of the save location. This will be exploited by
	// the underlying RegionFileCache because the save path doesn't
	// dynamically change while running.
	protected final String saveDir;

	private final class ChunkFlush implements RemovalListener<ChunkCoordIntPair, NBTTagCompound> {
		@Override
		public void onRemoval(final RemovalNotification<ChunkCoordIntPair, NBTTagCompound> notification) {
			try {
				// Only flush the entry if it was invalidated. Any entry could
				// be updated prior to it being written by an IO thread so we
				// want to avoid unnecessary writes.
				if (notification.getCause() == RemovalCause.EXPLICIT)
					AnvilChunkLoader.this.writeChunkNBTTags(notification.getKey(), notification.getValue());
			} catch (final Exception e) {
				e.printStackTrace();
			}
		}
	}

	private final Cache<ChunkCoordIntPair, NBTTagCompound> pendingIO = CacheBuilder.newBuilder()
			.removalListener(new ChunkFlush()).build();

	public AnvilChunkLoader(final File saveLocation) {
		this.chunkSaveLocation = saveLocation;
		this.saveDir = saveLocation.getPath().intern();
	}

	public boolean chunkExists(final World world, final int chunkX, final int chunkZ) throws Exception {
		final ChunkCoordIntPair coords = new ChunkCoordIntPair(chunkX, chunkZ);
		if (pendingIO.getIfPresent(coords) != null)
			return true;
		return RegionFileCache.createOrLoadRegionFile(saveDir, chunkX, chunkZ).chunkExists(chunkX & 31, chunkZ & 31);
	}

	public Chunk loadChunk(final World world, final int chunkX, final int chunkZ) throws IOException {
		Object[] data = null;
		try {
			data = loadChunk__Async(world, chunkX, chunkZ);
		} catch (ExecutionException e) {
			e.printStackTrace();
		}
		if (data != null) {
			final Chunk chunk = (Chunk) data[0];
			final NBTTagCompound nbt = (NBTTagCompound) data[1];
			loadEntities(world, nbt.getCompoundTag("Level"), chunk);
			return chunk;
		}
		return null;
	}

	public Object[] loadChunk__Async(final World world, final int chunkX, final int chunkZ)
			throws IOException, ExecutionException {
		final ChunkCoordIntPair coords = new ChunkCoordIntPair(chunkX, chunkZ);
		NBTTagCompound nbt = pendingIO.getIfPresent(coords);
		
		if (nbt == null) {
			DataInputStream stream = null;

			try {
				stream = RegionFileCache.getChunkInputStream(saveDir, chunkX, chunkZ);
			} catch (final Exception ex) {
				ex.printStackTrace();
			}

			if (stream == null) {
				return null;
			}
			nbt = CompressedStreamTools.read(stream);

			// Need this. Underneath it triggers the routines to put
			// the stream into it's object pool for reuse. Besides,
			// you should always close a stream when done because it
			// may be needed.
			stream.close();
		}

		return checkedReadChunkFromNBT__Async(world, chunkX, chunkZ, nbt);
	}

	protected Chunk checkedReadChunkFromNBT(final World world, final int chunkX, final int chunkZ, NBTTagCompound nbt) {
		final Object[] data = checkedReadChunkFromNBT__Async(world, chunkX, chunkZ, nbt);
		return data != null ? (Chunk) data[0] : null;
	}

	protected Object[] checkedReadChunkFromNBT__Async(final World world, final int chunkX, final int chunkZ,
			final NBTTagCompound nbt) {
		if (!nbt.hasKey("Level", 10)) {
			logger.error("Chunk file at " + chunkX + "," + chunkZ + " is missing level data, skipping");
			return null;
		}
		if (!nbt.getCompoundTag("Level").hasKey("Sections", 9)) {
			logger.error("Chunk file at " + chunkX + "," + chunkZ + " is missing block data, skipping");
			return null;
		}

		Chunk chunk = readChunkFromNBT(world, nbt.getCompoundTag("Level"));

		if (!chunk.isAtLocation(chunkX, chunkZ)) {
			logger.error("Chunk file at " + chunkX + "," + chunkZ + " is in the wrong location; relocating. (Expected "
					+ chunkX + ", " + chunkZ + ", got " + chunk.xPosition + ", " + chunk.zPosition + ")");
			nbt.setInteger("xPos", chunkX);
			nbt.setInteger("zPos", chunkZ);

			final NBTTagList tileEntities = nbt.getCompoundTag("Level").getTagList("TileEntities", 10);
			if (tileEntities != null) {
				for (int te = 0; te < tileEntities.tagCount(); te++) {
					final NBTTagCompound tileEntity = tileEntities.getCompoundTagAt(te);
					final int x = tileEntity.getInteger("x") - chunk.xPosition * 16;
					final int z = tileEntity.getInteger("z") - chunk.zPosition * 16;
					tileEntity.setInteger("x", chunkX * 16 + x);
					tileEntity.setInteger("z", chunkZ * 16 + z);
				}
			}
			chunk = readChunkFromNBT(world, nbt.getCompoundTag("Level"));
		}

		return new Object[] { chunk, nbt };
	}

	private class WriteChunkStream implements Callable<Void> {

		private final ChunkCoordIntPair chunkCoords;

		public WriteChunkStream(final ChunkCoordIntPair coords) {
			this.chunkCoords = coords;
		}

		@Override
		public Void call() throws Exception {
			// A simple invalidate will cause the eviction routine
			// to kick in for the entry. It is what actually does the
			// write to disk.
			AnvilChunkLoader.this.pendingIO.invalidate(chunkCoords);
			return null;
		}

	}

	public void saveChunk(final World world, final Chunk chunk) throws MinecraftException, IOException {
		try {
			final NBTTagCompound nbt1 = new NBTTagCompound();
			final NBTTagCompound nbt2 = new NBTTagCompound();
			nbt1.setTag("Level", nbt2);

			// This will generate the chunk into an NBTTagCompound. Compression
			// doesn't occur until IO.
			writeChunkToNBT(chunk, world, nbt2);

			MinecraftForge.EVENT_BUS.post(new ChunkDataEvent.Save(chunk, nbt1));

			// Put the write onto the pending list and queue
			// up an IO request.
			final ChunkCoordIntPair coords = chunk.getChunkCoordIntPair();
			pendingIO.put(coords, nbt1);
			ThreadedFileIOBase.getThreadedIOInstance().queue(new WriteChunkStream(coords));
		} catch (final Exception exception) {
			exception.printStackTrace();
		}
	}

	public boolean writeNextIO() {
		// Using the coordinate version
		return false;
	}

	private void writeChunkNBTTags(final ChunkCoordIntPair coords, final NBTTagCompound nbt) throws Exception {
		final DataOutputStream stream = RegionFileCache.getChunkOutputStream(saveDir, coords.chunkXPos,
				coords.chunkZPos);
		CompressedStreamTools.write(nbt, stream);
		stream.close();
	}

	public void saveExtraChunkData(final World world, final Chunk chunk) {
	}

	public void chunkTick() {
	}

	public void saveExtraData() {
		while (writeNextIO()) {
		}
	}

	@SuppressWarnings("unchecked")
	private void writeChunkToNBT(final Chunk chunk, final World world, final NBTTagCompound nbt) {
		nbt.setByte("V", (byte) 1);
		nbt.setInteger("xPos", chunk.xPosition);
		nbt.setInteger("zPos", chunk.zPosition);
		nbt.setLong("LastUpdate", world.getTotalWorldTime());
		nbt.setIntArray("HeightMap", chunk.heightMap);
		nbt.setBoolean("TerrainPopulated", chunk.isTerrainPopulated);
		nbt.setBoolean("LightPopulated", chunk.isLightPopulated);
		nbt.setLong("InhabitedTime", chunk.inhabitedTime);

		NBTTagCompound scratch = null;

		final NBTTagList sections = new NBTTagList();
		final boolean flag = !world.provider.hasNoSky;
		final ExtendedBlockStorage[] ebs = chunk.getBlockStorageArray();
		for (int j = 0; j < ebs.length; j++) {
			final ExtendedBlockStorage tebs = ebs[j];
			if (tebs != null) {
				scratch = new NBTTagCompound();
				scratch.setByte("Y", (byte) (tebs.getYLocation() >> 4 & 0xFF));
				scratch.setByteArray("Blocks", tebs.getBlockLSBArray());
				if (tebs.getBlockMSBArray() != null) {
					scratch.setByteArray("Add", tebs.getBlockMSBArray().data);
				}
				scratch.setByteArray("Data", tebs.getMetadataArray().data);
				scratch.setByteArray("BlockLight", tebs.getBlocklightArray().data);
				if (flag) {
					scratch.setByteArray("SkyLight", tebs.getSkylightArray().data);
				} else {
					scratch.setByteArray("SkyLight", new byte[tebs.getBlocklightArray().data.length]);
				}
				sections.appendTag(scratch);
			}
		}
		nbt.setTag("Sections", sections);

		nbt.setByteArray("Biomes", chunk.getBiomeArray());

		final NBTTagList entities = new NBTTagList();
		for (int i = 0; i < chunk.entityLists.length; i++) {
			for (final Entity entity : (List<Entity>) chunk.entityLists[i]) {
				scratch = new NBTTagCompound();
				try {
					if (entity.writeToNBTOptional(scratch)) {
						entities.appendTag(scratch);
					}
				} catch (final Exception e) {
					FMLLog.log(Level.ERROR, e,
							"An Entity type %s has thrown an exception trying to write state. It will not persist. Report this to the mod author",
							new Object[] { entity.getClass().getName() });
				}
			}
		}
		chunk.hasEntities = entities.tagCount() > 0;
		nbt.setTag("Entities", entities);

		final NBTTagList tileEntities = new NBTTagList();
		for (final TileEntity tileentity : (Iterable<TileEntity>) chunk.chunkTileEntityMap.values()) {
			scratch = new NBTTagCompound();
			try {
				tileentity.writeToNBT(scratch);
				tileEntities.appendTag(scratch);
			} catch (final Exception e) {
				FMLLog.log(Level.ERROR, e,
						"A TileEntity type %s has throw an exception trying to write state. It will not persist. Report this to the mod author",
						new Object[] { tileentity.getClass().getName() });
			}
		}
		nbt.setTag("TileEntities", tileEntities);

		final List<NextTickListEntry> list = world.getPendingBlockUpdates(chunk, false);
		if (list != null) {
			final long k = world.getTotalWorldTime();
			final NBTTagList tileTicks = new NBTTagList();
			for (final NextTickListEntry tickEntry : list) {
				scratch = new NBTTagCompound();
				scratch.setInteger("i", Block.getIdFromBlock(tickEntry.func_151351_a()));
				scratch.setInteger("x", tickEntry.xCoord);
				scratch.setInteger("y", tickEntry.yCoord);
				scratch.setInteger("z", tickEntry.zCoord);
				scratch.setInteger("t", (int) (tickEntry.scheduledTime - k));
				scratch.setInteger("p", tickEntry.priority);
				tileTicks.appendTag(scratch);
			}
			nbt.setTag("TileTicks", tileTicks);
		}
	}

	private Chunk readChunkFromNBT(final World world, final NBTTagCompound nbt) {
		final int i = nbt.getInteger("xPos");
		final int j = nbt.getInteger("zPos");
		final Chunk chunk = new Chunk(world, i, j);
		chunk.heightMap = nbt.getIntArray("HeightMap");
		chunk.isTerrainPopulated = nbt.getBoolean("TerrainPopulated");
		chunk.isLightPopulated = nbt.getBoolean("LightPopulated");
		chunk.inhabitedTime = nbt.getLong("InhabitedTime");
		final NBTTagList sections = nbt.getTagList("Sections", 10);
		final ExtendedBlockStorage[] ebs = new ExtendedBlockStorage[16];
		final boolean flag = !world.provider.hasNoSky;
		for (int k = 0; k < sections.tagCount(); k++) {
			final NBTTagCompound scratch = sections.getCompoundTagAt(k);
			final byte b1 = scratch.getByte("Y");
			final ExtendedBlockStorage tebs = new ExtendedBlockStorage(b1 << 4, flag);
			tebs.setBlockLSBArray(scratch.getByteArray("Blocks"));
			if (scratch.hasKey("Add", 7)) {
				tebs.setBlockMSBArray(new NibbleArray(scratch.getByteArray("Add"), 4));
			}
			tebs.setBlockMetadataArray(new NibbleArray(scratch.getByteArray("Data"), 4));
			tebs.setBlocklightArray(new NibbleArray(scratch.getByteArray("BlockLight"), 4));
			if (flag) {
				tebs.setSkylightArray(new NibbleArray(scratch.getByteArray("SkyLight"), 4));
			}
			tebs.removeInvalidBlocks();
			ebs[b1] = tebs;
		}
		chunk.setStorageArrays(ebs);
		if (nbt.hasKey("Biomes", 7)) {
			chunk.setBiomeArray(nbt.getByteArray("Biomes"));
		}
		return chunk;
	}

	public void loadEntities(final World world, final NBTTagCompound nbt, final Chunk chunk) {
		NBTTagCompound scratch = null;
		final NBTTagList entities = nbt.getTagList("Entities", 10);
		if (entities != null) {
			for (int l = 0; l < entities.tagCount(); l++) {
				scratch = entities.getCompoundTagAt(l);
				final Entity entity2 = EntityList.createEntityFromNBT(scratch, world);
				chunk.hasEntities = true;
				if (entity2 != null) {
					chunk.addEntity(entity2);
					Entity entity = entity2;
					for (NBTTagCompound nbttagcompound2 = scratch; nbttagcompound2.hasKey("Riding",
							10); nbttagcompound2 = nbttagcompound2.getCompoundTag("Riding")) {
						final Entity entity1 = EntityList.createEntityFromNBT(nbttagcompound2.getCompoundTag("Riding"),
								world);
						if (entity1 != null) {
							chunk.addEntity(entity1);
							entity.mountEntity(entity1);
						}
						entity = entity1;
					}
				}
			}
		}

		final NBTTagList tileEntities = nbt.getTagList("TileEntities", 10);
		if (tileEntities != null) {
			for (int i1 = 0; i1 < tileEntities.tagCount(); i1++) {
				scratch = tileEntities.getCompoundTagAt(i1);
				final TileEntity tileentity = TileEntity.createAndLoadEntity(scratch);
				if (tileentity != null) {
					chunk.addTileEntity(tileentity);
				}
			}
		}

		if (nbt.hasKey("TileTicks", 9)) {
			final NBTTagList tileTicks = nbt.getTagList("TileTicks", 10);
			if (tileTicks != null) {
				for (int j1 = 0; j1 < tileTicks.tagCount(); j1++) {
					scratch = tileTicks.getCompoundTagAt(j1);
					world.func_147446_b(scratch.getInteger("x"), scratch.getInteger("y"), scratch.getInteger("z"),
							Block.getBlockById(scratch.getInteger("i")), scratch.getInteger("t"),
							scratch.getInteger("p"));
				}
			}
		}
	}
}
