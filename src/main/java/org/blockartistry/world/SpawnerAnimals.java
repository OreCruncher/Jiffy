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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EnumCreatureType;
import net.minecraft.entity.IEntityLivingData;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.MathHelper;
import net.minecraft.util.WeightedRandom;
import net.minecraft.world.ChunkCoordIntPair;
import net.minecraft.world.ChunkPosition;
import net.minecraft.world.World;
import net.minecraft.world.biome.BiomeGenBase;
import net.minecraft.world.chunk.Chunk;

import cpw.mods.fml.common.eventhandler.Event.Result;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.event.ForgeEventFactory;

public final class SpawnerAnimals {

	protected static ChunkPosition func_151350_a(final World world, final int chunkX, final int chunkZ) {
		final Chunk chunk = world.getChunkFromChunkCoords(chunkX, chunkZ);
		final int x = chunkX * 16 + world.rand.nextInt(16);
		final int z = chunkZ * 16 + world.rand.nextInt(16);
		final int y = world.rand
				.nextInt(chunk == null ? world.getActualHeight() : chunk.getTopFilledSegment() + 16 - 1);
		return new ChunkPosition(x, y, z);
	}

	private static EntityLiving createCritter(final World world, final Class<?> entityClass) {
		try {
			return (EntityLiving) entityClass.getConstructor(World.class).newInstance(world);
		} catch (final Exception exception) {
			exception.printStackTrace();
		}
		return null;
	}

	private static long dSquared(final ChunkCoordinates coord, final int x, final int y, final int z) {
		return (long) coord.getDistanceSquared(x, y, z);
		/*
		 * final long dX = x - coord.posX; final long dY = y - coord.posY; final
		 * long dZ = z - coord.posZ; return (dX * dX) + (dY * dY) + (dZ * dZ);
		 */
	}

	@SuppressWarnings("unchecked")
	private static boolean anyPlayersWithin(final World world, final int x, final int y, final int z, final int dist) {
		final double dSq = dist * dist;
		for (final EntityPlayer entityPlayer : (List<EntityPlayer>) world.playerEntities)
			if (entityPlayer.getDistanceSq(x, y, z) <= dSq)
				return true;
		return false;
	}

	@SuppressWarnings("unchecked")
	public static int countEntities(final World world, final EnumCreatureType type) {
		int count = 0;
		for (final Entity entity : (List<Entity>) world.loadedEntityList)
			if (entity.isCreatureType(type, true))
				count++;
		return count;
	}

	// Collect the chunks that will actually be eligible for
	// spawning. Note that the RANGE is 1 less than Vanilla because
	// the border chunks are ignored in the scan.
	private static final int RANGE = 7;

	// The chunk area covered by each player. The idea is to take
	// the total chunks in the candidate list (plus factor) and
	// ratio it against the area per player. This gives a scaling
	// factor to apply to the max possible critters per type. The
	// larger the area, the more critters permitted.
	//
	// Note that the Vanilla version hard coded the area to 256
	// chunks per player. This assumes a 16x16 chunk region around
	// the player. The reality is that the eligible spawn range is
	// 15x15.
	private static final int CHUNKS_PER_PLAYER = (RANGE * 2 + 1) * (RANGE * 2 + 1);

	// Additional chunks to add per player when determining
	// the creature scale factor.  Essentially the total chunks in
	// the border region defined by RANGE.
	private static final int PLAYER_COUNT_FACTOR = ((RANGE * 2) + 3) * 2;

	/**
	 * adds all chunks within the spawn radius of the players to
	 * eligibleChunksForSpawning. pars: the world, hostileCreatures,
	 * passiveCreatures. returns number of eligible chunks.
	 */
	@SuppressWarnings("unchecked")
	public int findChunksForSpawning(WorldServer world, boolean hostileMobs, boolean peacefulMobs, boolean animals) {
		if ((!hostileMobs && !peacefulMobs) || world.playerEntities.size() == 0) {
			return 0;
		}

		// Vanilla does fancy stuff with map of booleans. This version
		// creates a single list that has chunk candidates, and adds a
		// factor to the size to come up with chunkCount to take into
		// account the player region "border". Since most players keep
		// separate this is close to being accurate with Vanilla. Worse case
		// is if players share a common region the border chunks will be
		// counted twice meaning a slightly higher density of mobs.

		final Set<ChunkCoordIntPair> candidates = new HashSet<ChunkCoordIntPair>();

		for (final EntityPlayer player : (List<EntityPlayer>) world.playerEntities) {
			final int centerX = MathHelper.floor_double(player.posX / 16.0D);
			final int centerY = MathHelper.floor_double(player.posZ / 16.0D);

			for (int l = -RANGE; l <= RANGE; ++l)
				for (int i1 = -RANGE; i1 <= RANGE; ++i1)
					candidates.add(new ChunkCoordIntPair(l + centerX, i1 + centerY));
		}

		// Estimate what the count needs to be adjusted by because we ignored
		// the border chunks in the scan. Horse shoes and hand grenades...
		final int chunkCount = candidates.size() + (PLAYER_COUNT_FACTOR * world.playerEntities.size());

		// Need to make into a list for shuffling.  Do an initial shuffle
		// to help mitigate the deterministic nature of the list because
		// of how it was generated (i.e. there is an order to it).  Supply
		// our own random function because it will be replaced with a fast
		// random when Forge loads the mod.
		final List<ChunkCoordIntPair> eligibleChunksForSpawning = new ArrayList<ChunkCoordIntPair>(candidates);
		Collections.shuffle(eligibleChunksForSpawning, world.rand);

		int i = 0;
		final ChunkCoordinates spawnPoint = world.getSpawnPoint();

		for (final EnumCreatureType critter : EnumCreatureType.values()) {

			// The below logic is interesting in that creature counts are based on
			// current logged on players.  I have seen cases where the bulk of the
			// creatures spawn around a player rather than being evenly distributed
			// throughout the possible chunks.  It's pretty funny, actually.
			if ((!critter.getPeacefulCreature() || peacefulMobs) && (critter.getPeacefulCreature() || hostileMobs)
					&& (!critter.getAnimal() || animals) && countEntities(world,
							critter) <= (critter.getMaxNumberOfCreature() * chunkCount) / CHUNKS_PER_PLAYER) {

				Collections.shuffle(eligibleChunksForSpawning, world.rand);

				label110:

				for (final ChunkCoordIntPair coords : eligibleChunksForSpawning) {
					final ChunkPosition chunkPosition = func_151350_a(world, coords.chunkXPos, coords.chunkZPos);
					final int critterX = chunkPosition.chunkPosX;
					final int critterY = chunkPosition.chunkPosY;
					final int critterZ = chunkPosition.chunkPosZ;

					final Block block = world.getBlock(critterX, critterY, critterZ);
					if (!block.isNormalCube() && block.getMaterial() == critter.getCreatureMaterial()) {
						int i2 = 0;
						int j2 = 0;

						while (j2 < 3) {
							int cX = critterX;
							int cY = critterY;
							int cZ = critterZ;
							final int noise = 6;
							BiomeGenBase.SpawnListEntry spawnEntry = null;
							IEntityLivingData livingData = null;
							int j3 = 0;

							while (true) {
								if (j3 < 4) {
									label103: {
										cX += world.rand.nextInt(noise) - world.rand.nextInt(noise);
										cY += world.rand.nextInt(1) - world.rand.nextInt(1);
										cZ += world.rand.nextInt(noise) - world.rand.nextInt(noise);

										// Rearrange conditions from least
										// to most expensive in terms
										// of computation. Computations are
										// ints rather than floats to make
										// things a little bit faster.
										if (dSquared(spawnPoint, cX, cY, cZ) >= 576
												&& !anyPlayersWithin(world, cX, cY, cZ, 24)
												&& canCreatureTypeSpawnAtLocation(critter, world, cX, cY, cZ)) {

											if (spawnEntry == null) {
												spawnEntry = world.spawnRandomCreature(critter, cX, cY, cZ);

												if (spawnEntry == null) {
													break label103;
												}
											}

											final EntityLiving entityliving = createCritter(world,
													spawnEntry.entityClass);
											if (entityliving == null)
												return i;

											final float cXf = (float) cX + 0.5F;
											final float cYf = (float) cY;
											final float cZf = (float) cZ + 0.5F;

											entityliving.setLocationAndAngles((double) cXf, (double) cYf, (double) cZf,
													world.rand.nextFloat() * 360.0F, 0.0F);

											final Result canSpawn = ForgeEventFactory.canEntitySpawn(entityliving,
													world, cXf, cYf, cZf);
											if (canSpawn == Result.ALLOW
													|| (canSpawn == Result.DEFAULT && entityliving.getCanSpawnHere())) {
												++i2;
												world.spawnEntityInWorld(entityliving);
												if (!ForgeEventFactory.doSpecialSpawn(entityliving, world, cXf, cYf,
														cZf)) {
													livingData = entityliving.onSpawnWithEgg(livingData);
												}

												if (j2 >= ForgeEventFactory.getMaxSpawnPackSize(entityliving)) {
													continue label110;
												}
											}

											i += i2;
										}

										++j3;
										continue;
									}
								}

								++j2;
								break;
							}
						}
					}
				}
			}
		}

		return i;
	}

	/**
	 * Returns whether or not the specified creature type can spawn at the
	 * specified location.
	 */
	public static boolean canCreatureTypeSpawnAtLocation(final EnumCreatureType type, final World world, final int x,
			final int y, final int z) {
		if (type.getCreatureMaterial() == Material.water) {
			return world.getBlock(x, y, z).getMaterial().isLiquid()
					&& world.getBlock(x, y - 1, z).getMaterial().isLiquid()
					&& !world.getBlock(x, y + 1, z).isNormalCube();
		}
		final Block block = world.getBlock(x, y - 1, z);
		if (block == Blocks.bedrock || !block.isSideSolid(world, x, y - 1, z, ForgeDirection.UP)
				|| !block.canCreatureSpawn(type, world, x, y - 1, z))
			return false;
		final Block block1 = world.getBlock(x, y, z);
		return !block1.isNormalCube() && !block1.getMaterial().isLiquid()
				&& !world.getBlock(x, y + 1, z).isNormalCube();
	}

	/**
	 * Called during chunk generation to spawn initial creatures.
	 */
	public static void performWorldGenSpawning(World world, BiomeGenBase biome, int p_77191_2_, int p_77191_3_,
			int p_77191_4_, int p_77191_5_, Random random) {

		List<?> list = biome.getSpawnableList(EnumCreatureType.creature);

		if (list.isEmpty())
			return;

		while (random.nextFloat() < biome.getSpawningChance()) {
			BiomeGenBase.SpawnListEntry spawnEntry = (BiomeGenBase.SpawnListEntry) WeightedRandom
					.getRandomItem(world.rand, list);
			IEntityLivingData ientitylivingdata = null;
			int packCount = spawnEntry.minGroupCount
					+ random.nextInt(1 + spawnEntry.maxGroupCount - spawnEntry.minGroupCount);
			int cX = p_77191_2_ + random.nextInt(p_77191_4_);
			int cZ = p_77191_3_ + random.nextInt(p_77191_5_);
			int l1 = cX;
			int i2 = cZ;

			for (int j2 = 0; j2 < packCount; ++j2) {
				boolean flag = false;

				for (int k2 = 0; !flag && k2 < 4; ++k2) {
					int cY = world.getTopSolidOrLiquidBlock(cX, cZ);

					if (canCreatureTypeSpawnAtLocation(EnumCreatureType.creature, world, cX, cY, cZ)) {

						final EntityLiving entityliving = createCritter(world, spawnEntry.entityClass);
						if (entityliving == null)
							continue;

						entityliving.setLocationAndAngles((double) cX + 0.5D, (double) cY, (double) cZ + 0.5D,
								random.nextFloat() * 360.0F, 0.0F);
						world.spawnEntityInWorld(entityliving);
						ientitylivingdata = entityliving.onSpawnWithEgg(ientitylivingdata);
						flag = true;
					}

					cX += random.nextInt(5) - random.nextInt(5);

					for (cZ += random.nextInt(5) - random.nextInt(5); cX < p_77191_2_ || cX >= p_77191_2_ + p_77191_4_
							|| cZ < p_77191_3_
							|| cZ >= p_77191_3_ + p_77191_4_; cZ = i2 + random.nextInt(5) - random.nextInt(5)) {
						cX = l1 + random.nextInt(5) - random.nextInt(5);
					}
				}
			}
		}
	}
}
