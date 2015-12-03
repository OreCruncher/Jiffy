package org.blockartistry.world;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
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
			return (EntityLiving) entityClass.getConstructor(new Class[] { World.class })
					.newInstance(new Object[] { world });
		} catch (Exception exception) {
			exception.printStackTrace();
		}
		return null;
	}

	private static int dSquared(final ChunkCoordinates coord, final int x, final int y, final int z) {
		final int dX = x - coord.posX;
		final int dY = y - coord.posY;
		final int dZ = z - coord.posZ;
		return (dX * dX) + (dY * dY) + (dZ * dZ);
	}

	@SuppressWarnings("unchecked")
	private static boolean playersWithin(final World world, final int x, final int y, final int z, final int distSq) {
		for (final EntityPlayer entityPlayer : (List<EntityPlayer>) world.playerEntities) {
			final ChunkCoordinates coord = new ChunkCoordinates(entityPlayer.chunkCoordX, entityPlayer.chunkCoordY,
					entityPlayer.chunkCoordZ);
			if (dSquared(coord, x, y, z) <= distSq)
				return true;
		}

		return false;
	}

	/**
	 * adds all chunks within the spawn radius of the players to
	 * eligibleChunksForSpawning. pars: the world, hostileCreatures,
	 * passiveCreatures. returns number of eligible chunks.
	 */
	@SuppressWarnings("unchecked")
	public int findChunksForSpawning(WorldServer world, boolean hostileMobs, boolean peacefulMobs, boolean animals) {
		if ((!hostileMobs && !peacefulMobs) || world.playerEntities.size() == 0) {
			return 0;
		} else {
			final Set<ChunkCoordIntPair> trueMap = new HashSet<ChunkCoordIntPair>();
			final Set<ChunkCoordIntPair> falseMap = new HashSet<ChunkCoordIntPair>();

			for (final EntityPlayer player : (List<EntityPlayer>) world.playerEntities) {
				final int centerX = MathHelper.floor_double(player.posX / 16.0D);
				final int centerY = MathHelper.floor_double(player.posZ / 16.0D);
				final int range = 8;

				for (int l = -range; l <= range; ++l) {
					for (int i1 = -range; i1 <= range; ++i1) {

						final boolean flag3 = l == -range || l == range || i1 == -range || i1 == range;
						final ChunkCoordIntPair pair = new ChunkCoordIntPair(l + centerX, i1 + centerY);

						if (!flag3) {
							if (falseMap.add(pair))
								trueMap.remove(pair);
						} else if (!falseMap.contains(pair)) {
							trueMap.add(pair);
						}
					}
				}
			}

			final int chunkCount = trueMap.size() + falseMap.size();
			final List<ChunkCoordIntPair> eligibleChunksForSpawning = new ArrayList<ChunkCoordIntPair>(falseMap);

			int i = 0;
			ChunkCoordinates spawnPoint = world.getSpawnPoint();

			for (final EnumCreatureType critter : EnumCreatureType.values()) {

				if ((!critter.getPeacefulCreature() || peacefulMobs) && (critter.getPeacefulCreature() || hostileMobs)
						&& (!critter.getAnimal() || animals)
						&& world.countEntities(critter, true) <= critter.getMaxNumberOfCreature() * chunkCount / 256) {

					Collections.shuffle(eligibleChunksForSpawning);

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

											// Rearrange conditions from least to most expensive in terms
											// of computation
											if (dSquared(spawnPoint, cX, cY, cZ) >= 576
													&& !playersWithin(world, cX, cY, cZ, 24)
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

												entityliving.setLocationAndAngles((double) cXf, (double) cYf,
														(double) cZf, world.rand.nextFloat() * 360.0F, 0.0F);

												final Result canSpawn = ForgeEventFactory.canEntitySpawn(entityliving,
														world, cXf, cYf, cZf);
												if (canSpawn == Result.ALLOW || (canSpawn == Result.DEFAULT
														&& entityliving.getCanSpawnHere())) {
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
