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
import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.EmptyChunk;
import net.minecraftforge.common.util.ForgeDirection;

/**
 * Used by the client renderer as well as path finding routines. Changes:
 * 
 * + Chunk array vs. matrix
 * 
 * + Removed unnecessary checks
 */
public class ChunkCache implements IBlockAccess {

	// Useful proxy for when a chunk is not found
	// for some reason. The default methods return
	// exactly what is needed from the ChunkCache.
	private final static Chunk EMPTY_CHUNK = new EmptyChunk(null, 0, 0);

	private final int chunkX;
	private final int chunkZ;
	private final int dimX;
	private final int dimZ;
	private final Chunk[] chunkArray;
	private final boolean isEmpty;
	private final World worldObj;

	public ChunkCache(World world, int x1, int y1, int z1, int x2, int y2, int z2, int buffer) {
		this.worldObj = world;
		this.chunkX = x1 - buffer >> 4;
		this.chunkZ = z1 - buffer >> 4;
		int l1 = x2 + buffer >> 4;
		int i2 = z2 + buffer >> 4;

		this.dimX = l1 - this.chunkX + 1;
		this.dimZ = i2 - this.chunkZ + 1;
		this.chunkArray = new Chunk[this.dimX * this.dimZ];

		boolean emptyFlag = true;

		for (int j2 = this.chunkX; j2 <= l1; ++j2) {
			for (int k2 = this.chunkZ; k2 <= i2; ++k2) {
				final int idx = j2 - this.chunkX + (k2 - this.chunkZ) * this.dimX;
				final Chunk chunk = this.chunkArray[idx] = world.getChunkFromChunkCoords(j2, k2);
				assert chunk != null;
				if (emptyFlag && !chunk.getAreLevelsEmpty(y1, y2))
					emptyFlag = false;
			}
		}

		this.isEmpty = emptyFlag;
	}

	/**
	 * set by !chunk.getAreLevelsEmpty
	 */
	@SideOnly(Side.CLIENT)
	public boolean extendedLevelsInChunkCache() {
		return this.isEmpty;
	}

	private Chunk getChunkFromArray(final int x, final int y, final int z) {
		// Seen out of range Ys come in. Haven't seen out of range
		// X or Z. Relaxing range checks as not needed.
		Chunk chunk = EMPTY_CHUNK;
		if (y >= 0 && y < 256) {
			final int l = (x >> 4) - this.chunkX;
			final int i1 = (z >> 4) - this.chunkZ;
			assert (l >= 0 && l < this.dimX && i1 >= 0 && i1 < this.dimZ);
			// if (l >= 0 && l < this.dimX && i1 >= 0 && i1 < this.dimZ)
			chunk = this.chunkArray[l + i1 * this.dimX];
		}
		return chunk;
	}

	public Block getBlock(final int x, final int y, final int z) {
		return getChunkFromArray(x, y, z).getBlock(x & 15, y, z & 15);
	}

	public TileEntity getTileEntity(final int x, final int y, final int z) {
		return getChunkFromArray(x, y, z).func_150806_e(x & 15, y, z & 15);
	}

	public int getBlockMetadata(final int x, final int y, final int z) {
		return getChunkFromArray(x, y, z).getBlockMetadata(x & 15, y, z & 15);
	}

	public boolean isAirBlock(final int x, final int y, final int z) {
		return getBlock(x, y, z).isAir(this, x, y, z);
	}

	public int isBlockProvidingPowerTo(final int x, final int y, final int z, final int dir) {
		return getBlock(x, y, z).isProvidingStrongPower(this, x, y, z, dir);
	}

	/**
	 * Any Light rendered on a 1.8 Block goes through here
	 */
	@SideOnly(Side.CLIENT)
	public int getLightBrightnessForSkyBlocks(final int x, final int y, final int z, int p_72802_4_) {
		int i1 = this.getSkyBlockTypeBrightness(EnumSkyBlock.Sky, x, y, z);
		int j1 = this.getSkyBlockTypeBrightness(EnumSkyBlock.Block, x, y, z);

		if (j1 < p_72802_4_) {
			j1 = p_72802_4_;
		}

		return i1 << 20 | j1 << 4;
	}

	/**
	 * Gets the biome for a given set of x/z coordinates
	 */
	@SideOnly(Side.CLIENT)
	public BiomeGenBase getBiomeGenForCoords(final int x, final int z) {
		return this.worldObj.getBiomeGenForCoords(x, z);
	}

	/**
	 * Brightness for SkyBlock.Sky is clear white and (through color computing
	 * it is assumed) DEPENDENT ON DAYTIME. Brightness for SkyBlock.Block is
	 * yellowish and independent.
	 */
	@SideOnly(Side.CLIENT)
	public int getSkyBlockTypeBrightness(final EnumSkyBlock skyBlock, final int x, int y, final int z) {
		if (y < 0) {
			y = 0;
		} else if (y >= 256) {
			y = 255;
		}

		if (x >= -30000000 && z >= -30000000 && x < 30000000 && z <= 30000000) {
			if (skyBlock == EnumSkyBlock.Sky && this.worldObj.provider.hasNoSky) {
				return 0;
			} else {
				if (this.getBlock(x, y, z).getUseNeighborBrightness()) {
					int l = this.getSpecialBlockBrightness(skyBlock, x, y + 1, z);
					int i1 = this.getSpecialBlockBrightness(skyBlock, x + 1, y, z);
					int j1 = this.getSpecialBlockBrightness(skyBlock, x - 1, y, z);
					int k1 = this.getSpecialBlockBrightness(skyBlock, x, y, z + 1);
					int l1 = this.getSpecialBlockBrightness(skyBlock, x, y, z - 1);

					if (i1 > l) {
						l = i1;
					}

					if (j1 > l) {
						l = j1;
					}

					if (k1 > l) {
						l = k1;
					}

					if (l1 > l) {
						l = l1;
					}

					return l;
				} else {
					return getChunkFromArray(x, y, z).getSavedLightValue(skyBlock, x & 15, y, z & 15);
				}
			}
		} else {
			return skyBlock.defaultLightValue;
		}
	}

	/**
	 * is only used on stairs and tilled fields
	 */
	@SideOnly(Side.CLIENT)
	public int getSpecialBlockBrightness(final EnumSkyBlock skyBlock, final int x, int y, final int z) {
		if (y < 0) {
			y = 0;
		} else if (y >= 256) {
			y = 255;
		}

		if (x >= -30000000 && z >= -30000000 && x < 30000000 && z <= 30000000) {
			return getChunkFromArray(x, y, z).getSavedLightValue(skyBlock, x & 15, y, z & 15);
		} else {
			return skyBlock.defaultLightValue;
		}
	}

	/**
	 * Returns current world height.
	 */
	@SideOnly(Side.CLIENT)
	public int getHeight() {
		return 256;
	}

	@Override
	public boolean isSideSolid(final int x, final int y, final int z, final ForgeDirection side,
			final boolean _default) {
		if (x < -30000000 || z < -30000000 || x >= 30000000 || z >= 30000000) {
			return _default;
		}

		return getBlock(x, y, z).isSideSolid(this, x, y, z, side);
	}
}
