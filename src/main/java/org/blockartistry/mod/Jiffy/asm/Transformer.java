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

package org.blockartistry.mod.Jiffy.asm;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.util.CheckClassAdapter;

import net.minecraft.launchwrapper.IClassTransformer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Transformer implements IClassTransformer {

	private static final Logger logger = LogManager.getLogger("Jiffy Transform");

	// Mapping between Minecraft classes and the class to replace them with
	private static Map<String, String> targets = new HashMap<String, String>();

	// Obsfucation mapping of methods to SRG
	private static Map<String, Map<String, String>> obsRemap = new HashMap<String, Map<String, String>>();

	// Class prefixes to replace Random class
	private static final List<String> randomReplaceExclude = new ArrayList<String>();

	static {

		targets.put("net.minecraft.world.chunk.storage.RegionFileCache", "world.chunk.storage.RegionFileCache");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileLoader",
				"world.chunk.storage.RegionFileCache$RegionFileLoader");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileEviction",
				"world.chunk.storage.RegionFileCache$RegionFileEviction");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileKey",
				"world.chunk.storage.RegionFileCache$RegionFileKey");

		targets.put("net.minecraft.world.chunk.storage.RegionFile", "world.chunk.storage.RegionFile");

		targets.put("net.minecraft.world.chunk.storage.ChunkBuffer", "world.chunk.storage.ChunkBuffer");
		targets.put("net.minecraft.world.chunk.storage.ChunkOutputStream", "world.chunk.storage.ChunkOutputStream");
		targets.put("net.minecraft.world.chunk.storage.ChunkInputStream", "world.chunk.storage.ChunkInputStream");

		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", "world.chunk.storage.AnvilChunkLoader");
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader$ChunkFlush",
				"world.chunk.storage.AnvilChunkLoader$ChunkFlush");
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader$WriteChunkStream",
				"world.chunk.storage.AnvilChunkLoader$WriteChunkStream");

		targets.put("net.minecraft.world.chunk.storage.AttachableByteArrayInputStream",
				"world.chunk.storage.AttachableByteArrayInputStream");

		targets.put("net.minecraft.world.storage.ThreadedFileIOBase", "world.storage.ThreadedFileIOBase");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO",
				"world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$CompletionCallback",
				"world.storage.ThreadedFileIOBase$CompletionCallback");

		targets.put("net.minecraft.world.gen.ChunkProviderServer", "world.gen.ChunkProviderServer");
		targets.put("net.minecraft.world.gen.ChunkProviderServer$SimpleChunkList",
				"world.gen.ChunkProviderServer$SimpleChunkList");

		targets.put("net.minecraft.util.LongHashMap", "util.LongHashMap");
		targets.put("net.minecraft.util.LongHashMap$Entry", "util.LongHashMap$Entry");

		targets.put("net.minecraft.world.biome.BiomeCache", "world.biome.BiomeCache");
		targets.put("net.minecraft.world.biome.BiomeCache$Block", "world.biome.BiomeCache$Block");
		targets.put("net.minecraft.world.biome.BiomeCache$RemoveOldEntries", "world.biome.BiomeCache$RemoveOldEntries");

		targets.put("net.minecraft.world.gen.layer.IntCache", "world.gen.layer.IntCache");

		targets.put("net.minecraft.world.ChunkCache", "world.ChunkCache");

		targets.put("net.minecraft.world.WorldServer", "world.WorldServer");
		targets.put("net.minecraft.world.WorldServer$ServerBlockEventList", "world.WorldServer$ServerBlockEventList");

		targets.put("net.minecraft.world.SpawnerAnimals", "world.SpawnerAnimals");

		// Forge classes
		targets.put("net.minecraftforge.common.chunkio.ChunkIOExecutor", "common.chunkio.ChunkIOExecutor");
		targets.put("net.minecraftforge.common.chunkio.ChunkIOProvider", "common.chunkio.ChunkIOProvider");
		targets.put("net.minecraftforge.common.chunkio.QueuedChunk", "common.chunkio.QueuedChunk");

		// Obsfucated names
		targets.put("aqj", "world.chunk.storage.RegionFileCache");
		targets.put("aqj$RegionFileLoader", "world.chunk.storage.RegionFileCache$RegionFileLoader");
		targets.put("aqj$RegionFileEviction", "world.chunk.storage.RegionFileCache$RegionFileEviction");
		targets.put("aqj$RegionFileKey", "world.chunk.storage.RegionFileCache$RegionFileKey");

		targets.put("aqh", "world.chunk.storage.RegionFile");

		targets.put("azr", "world.storage.ThreadedFileIOBase");
		targets.put("azr$WrapperIThreadedFileIO", "world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO");
		targets.put("azr$CompletionCallback", "world.storage.ThreadedFileIOBase$CompletionCallback");

		targets.put("aqk", "world.chunk.storage.AnvilChunkLoader");
		targets.put("aqk$ChunkFlush", "world.chunk.storage.AnvilChunkLoader$ChunkFlush");
		targets.put("aqk$WriteChunkStream", "world.chunk.storage.AnvilChunkLoader$WriteChunkStream");

		targets.put("ms", "world.gen.ChunkProviderServer");
		targets.put("ms$SimpleChunkList", "world.gen.ChunkProviderServer$SimpleChunkList");

		targets.put("qd", "util.LongHashMap");
		targets.put("qe", "util.LongHashMap$Entry");

		targets.put("ahy", "world.biome.BiomeCache");
		targets.put("ahy$RemoveOldEntries", "world.biome.BiomeCache$RemoveOldEntries");
		targets.put("ahz", "world.biome.BiomeCache$Block");

		targets.put("axl", "world.gen.layer.IntCache");

		targets.put("ahr", "world.ChunkCache");

		targets.put("mt", "world.WorldServer");
		targets.put("mv", "world.WorldServer$ServerBlockEventList");

		targets.put("aho", "world.SpawnerAnimals");

		// Obsfucation mapping - yay obsfucation!
		// RegionFileCache
		Map<String, String> map = new HashMap<String, String>();
		map.put("getChunkOutputStream(Ljava/io/File;II)Ljava/io/DataOutputStream;", "func_76552_d");
		map.put("clearRegionFileReferences()V", "func_76551_a");
		map.put("createOrLoadRegionFile(Ljava/io/File;II)Lorg/blockartistry/world/chunk/storage/RegionFile;",
				"func_76550_a");
		map.put("getChunkInputStream(Ljava/io/File;II)Ljava/io/DataInputStream;", "func_76549_c");
		obsRemap.put("RegionFileCache", map);

		// RegionFile
		map = new HashMap<String, String>();
		map.put("getChunkDataInputStream(II)Ljava/io/DataInputStream;", "func_76704_a");
		map.put("getChunkDataOutputStream(II)Ljava/io/DataOutputStream;", "func_76710_b");
		obsRemap.put("RegionFile", map);

		// ThreadedFileIOBase
		map = new HashMap<String, String>();
		map.put("waitForFinish()V", "func_75734_a");
		map.put("processQueue()V", "func_75736_b");
		map.put("queueIO(Lnet/minecraft/world/storage/IThreadedFileIO;)V", "func_75735_a");
		map.put("threadedIOInstance", "field_75741_a");
		obsRemap.put("ThreadedFileIOBase", map);

		// AnvilChunkLoader
		map = new HashMap<String, String>();
		map.put("chunkTick()V", "func_75817_a");
		map.put("writeNextIO()Z", "func_75814_c");
		map.put("saveExtraData()V", "func_75818_b");
		map.put("saveChunk(Lnet/minecraft/world/World;Lnet/minecraft/world/chunk/Chunk;)V", "func_75816_a");
		map.put("chunkSaveLocation", "field_75825_d");
		obsRemap.put("AnvilChunkLoader", map);

		// ChunkProviderServer
		map = new HashMap<String, String>();
		map.put("safeLoadChunk(II)Lnet/minecraft/world/chunk/Chunk;", "func_73239_e");
		map.put("unloadChunksIfNotNearSpawn(II)V", "func_73241_b");
		map.put("safeSaveExtraChunkData(Lnet/minecraft/world/chunk/Chunk;)V", "func_73243_a");
		map.put("safeSaveChunk(Lnet/minecraft/world/chunk/Chunk;)V", "func_73242_b");
		map.put("unloadAllChunks()V", "func_73240_a");
		map.put("currentChunkLoader", "field_73247_e");
		map.put("loadChunkOnProvideRequest", "field_73250_a"); // FastCraft for
																// some
																// reason...
		map.put("loadedChunks", "field_73245_g"); // ChickenChunks
		obsRemap.put("ChunkProviderServer", map);

		// LongHashMap
		map = new HashMap<String, String>();
		map.put("getNumHashElements()I", "func_76162_a");
		map.put("add(JLjava/lang/Object;)V", "func_76163_a");
		map.put("containsItem(J)Z", "func_76161_b");
		map.put("getValueByKey(J)Ljava/lang/Object;", "func_76164_a");
		map.put("remove(J)Ljava/lang/Object;", "func_76159_d");
		obsRemap.put("LongHashMap", map);

		// BiomeCache
		map.put("getBiomeGenAt(II)Lnet/minecraft/world/biome/BiomeGenBase;", "func_76837_b");
		map.put("cleanupCache()V", "func_76838_a");
		map.put("getCachedBiomes(II)[Lnet/minecraft/world/biome/BiomeGenBase;", "func_76839_e");
		map.put("getBiomeCacheBlock(II)Lnet/minecraft/world/biome/BiomeCache$Block;", "func_76840_a");
		obsRemap.put("BiomeCache", map);

		// IntCache
		map = new HashMap<String, String>();
		map.put("getIntCache(I)[I", "func_76445_a");
		map.put("resetIntCache()V", "func_76446_a");
		map.put("getCacheSizes()Ljava/lang/String;", "func_85144_b");
		obsRemap.put("IntCache", map);

		// ChunkCache
		map = new HashMap<String, String>();
		map.put("getSpecialBlockBrightness(Lnet/minecraft/world/EnumSkyBlock;III)I", "func_72812_b");
		map.put("getSkyBlockTypeBrightness(Lnet/minecraft/world/EnumSkyBlock;III)I", "func_72810_a");
		map.put("getHeight()I", "func_72800_K");
		map.put("getBlockMetadata(III)I", "func_72805_g");
		obsRemap.put("ChunkCache", map);

		// WorldServer
		map = new HashMap<String, String>();
		map.put("getEntityTracker()Lnet/minecraft/entity/EntityTracker;", "func_73039_n");
		map.put("createBonusChest()V", "func_73047_i");
		map.put("flush()V", "func_73041_k");
		map.put("getDefaultTeleporter()Lnet/minecraft/world/Teleporter;", "func_85176_s");
		map.put("createSpawnPosition(Lnet/minecraft/world/WorldSettings;)V", "func_73052_b");
		map.put("getEntrancePortalLocation()Lnet/minecraft/util/ChunkCoordinates;", "func_73054_j");
		map.put("resetRainAndThunder()V", "func_73051_P");
		map.put("saveAllChunks(ZLnet/minecraft/util/IProgressUpdate;)V", "func_73044_a");
		map.put("updateAllPlayersSleepingFlag()V", "func_72854_c");
		map.put("resetUpdateEntityTick()V", "func_82742_i");
		map.put("getPlayerManager()Lnet/minecraft/server/management/PlayerManager;", "func_73040_p");
		map.put("getEntityByID(I)Lnet/minecraft/entity/Entity;", "func_73045_a");
		map.put("areAllPlayersAsleep()Z", "func_73056_e");
		map.put("setEntityState(Lnet/minecraft/entity/Entity;B)V", "func_72960_a");
		map.put("updateEntityWithOptionalForce(Lnet/minecraft/entity/Entity;Z)V", "func_72866_a");
		map.put("saveLevel()V", "func_73042_a");
		map.put("wakeAllPlayers()V", "func_73053_d");
		map.put("saveChunkData()V", "func_104140_m");
		map.put("initialize(Lnet/minecraft/world/WorldSettings;)V", "func_72963_a");
		map.put("spawnRandomCreature(Lnet/minecraft/entity/EnumCreatureType;III)Lnet/minecraft/world/biome/BiomeGenBase$SpawnListEntry;",
				"func_73057_a");
		map.put("onEntityAdded(Lnet/minecraft/entity/Entity;)V", "func_72923_a");
		map.put("bonusChestContent", "field_73069_S");
		map.put("theChunkProviderServer", "field_73059_b");
		map.put("levelSaving", "field_73058_d");
		obsRemap.put("WorldServer", map);

		// SpawnerAnimals
		map = new HashMap<String, String>();
		map.put("canCreatureTypeSpawnAtLocation(Lnet/minecraft/entity/EnumCreatureType;Lnet/minecraft/world/World;III)Z",
				"func_77190_a");
		map.put("performWorldGenSpawning(Lnet/minecraft/world/World;Lnet/minecraft/world/biome/BiomeGenBase;IIIILjava/util/Random;)V",
				"func_77191_a");
		map.put("findChunksForSpawning(Lnet/minecraft/world/WorldServer;ZZZ)I", "func_77192_a");
		obsRemap.put("SpawnerAnimals", map);

		// Random excludes
		randomReplaceExclude.add("java");
		randomReplaceExclude.add("org/objectweb");
		randomReplaceExclude.add("org/apache");
		randomReplaceExclude.add("com/google");
		randomReplaceExclude.add("org/blockartistry/util/MathHelper");
	}

	private static boolean excludeFromRandomPatch(final String name) {
		final String theName = name.replace(".", "/");
		for (final String ex : randomReplaceExclude)
			if (theName.startsWith(ex))
				return true;
		return false;
	}

	private byte[] getClassBytes(final String clazz) {
		try {
			final String name = ("org.blockartistry." + clazz).replace('.', '/') + ".class";
			final InputStream stream = Transformer.class.getClassLoader().getResourceAsStream(name);
			final ByteArrayOutputStream baos = new ByteArrayOutputStream();
			final byte[] buffer = new byte[1024];
			int read = 0;
			while ((read = stream.read(buffer, 0, buffer.length)) != -1)
				baos.write(buffer, 0, read);
			baos.flush();
			return baos.toByteArray();
		} catch (final Exception e) {
			logger.error("Error getting class information", e);
		}

		return null;
	}

	private boolean verifyClassBytes(final byte[] bytes) {
		final StringWriter sw = new StringWriter();
		final PrintWriter pw = new PrintWriter(sw);
		CheckClassAdapter.verify(new ClassReader(bytes), false, pw);
		final String result = sw.toString();
		if (result.length() > 0)
			logger.error(result);
		return result.length() == 0;
	}

	@Override
	public byte[] transform(String name, String transformedName, byte[] basicClass) {

		final String src = targets.get(name);
		if (src != null) {
			final byte[] newBytes = getClassBytes(src);
			if (newBytes != null) {
				logger.debug("Loading '" + name + "' from '" + src + "'");
				final ClassReader reader = new ClassReader(newBytes);
				final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
				final RenameMapper mapper = new RenameMapper(TransformLoader.runtimeDeobEnabled ? obsRemap : null);
				final JiffyRemappingClassAdapter adapter = new JiffyRemappingClassAdapter(writer, mapper);
				reader.accept(adapter, ClassReader.EXPAND_FRAMES);
				final byte[] result = writer.toByteArray();
				if (verifyClassBytes(result)) {
					logger.debug("Load success '" + name + "'!");
					return replaceMath(name, replaceRandom(name, result));
				}
			} else {
				logger.warn("Unable to find classbytes for " + src);
			}
		} else if ("net.minecraft.util.MathHelper".equals(name) || "qh".equals(name)) {
			logger.debug("Transforming MathHelper...");
			return transformMathUtils(basicClass);
		} else if ("net.minecraft.block.BlockLeaves".equals(name) || "alt".equals(name)) {
			logger.debug("Transforming BlockLeaves...");
			return transformBlockLeaves(basicClass);
		} else if (!excludeFromRandomPatch(name)) {
			return replaceMath(name, replaceRandom(name, basicClass));
		}

		return basicClass;
	}

	private byte[] transformMathUtils(final byte[] classBytes) {

		String names[] = null;

		if (TransformLoader.runtimeDeobEnabled)
			names = new String[] { "func_76126_a", "func_76134_b" };
		else
			names = new String[] { "sin", "cos" };

		final String targetName[] = new String[] { "sin", "cos" };

		final ClassReader cr = new ClassReader(classBytes);
		final ClassNode cn = new ClassNode(ASM5);
		cr.accept(cn, 0);

		for (final MethodNode m : cn.methods) {
			int targetId = -1;
			if (m.name.equals(names[0]))
				targetId = 0;
			else if (m.name.equals(names[1]))
				targetId = 1;

			if (targetId != -1) {
				m.localVariables = null;
				m.instructions.clear();
				m.instructions.add(new VarInsnNode(FLOAD, 0));
				final String sig = "(F)F";
				m.instructions.add(new MethodInsnNode(INVOKESTATIC, "org/blockartistry/util/MathHelper",
						targetName[targetId], sig, false));
				m.instructions.add(new InsnNode(FRETURN));
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private byte[] transformBlockLeaves(final byte[] classBytes) {
		String names[] = null;

		if (TransformLoader.runtimeDeobEnabled)
			names = new String[] { "beginLeavesDecay" };
		else
			names = new String[] { "beginLeavesDecay" };

		final String targetName[] = new String[] { "beginLeavesDecay" };

		final ClassReader cr = new ClassReader(classBytes);
		final ClassNode cn = new ClassNode(ASM5);
		cr.accept(cn, 0);

		for (final MethodNode m : cn.methods) {
			if (m.name.equals(names[0])) {
				m.localVariables = null;
				m.instructions.clear();
				m.instructions.add(new VarInsnNode(ALOAD, 1));
				m.instructions.add(new VarInsnNode(ILOAD, 2));
				m.instructions.add(new VarInsnNode(ILOAD, 3));
				m.instructions.add(new VarInsnNode(ILOAD, 4));
				final String sig = "(Lnet/minecraft/world/World;III)V";
				m.instructions.add(new MethodInsnNode(INVOKESTATIC, "org/blockartistry/block/BlockLeaves",
						targetName[0], sig, false));
				m.instructions.add(new InsnNode(RETURN));
				break;
			}
		}

		ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
		cn.accept(cw);
		return cw.toByteArray();
	}

	private byte[] replaceRandom(final String name, final byte[] classBytes) {

		final String randomToReplace = "java/util/Random";
		final String newRandom = "org/blockartistry/util/XorShiftRandom";

		boolean madeUpdate = false;

		final ClassReader cr = new ClassReader(classBytes);
		final ClassNode cn = new ClassNode(ASM5);
		cr.accept(cn, 0);

		for (final MethodNode m : cn.methods) {
			final ListIterator<AbstractInsnNode> itr = m.instructions.iterator();
			boolean foundNew = false;
			while (itr.hasNext()) {
				final AbstractInsnNode node = itr.next();
				if (node.getOpcode() == NEW) {
					final TypeInsnNode theNew = (TypeInsnNode) node;
					if (randomToReplace.equals(theNew.desc)) {
						m.instructions.set(node, new TypeInsnNode(NEW, newRandom));
						madeUpdate = true;
						foundNew = true;
					}
				} else if (node.getOpcode() == INVOKESPECIAL) {
					final MethodInsnNode theInvoke = (MethodInsnNode) node;
					if (randomToReplace.equals(theInvoke.owner)) {
						if (foundNew) {
							m.instructions.set(node, new MethodInsnNode(INVOKESPECIAL, newRandom, theInvoke.name,
									theInvoke.desc, false));
							foundNew = false;
						}
					}
				}
			}
		}

		if (madeUpdate) {
			logger.debug("Replaced Random in " + name);
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);
			return cw.toByteArray();
		}
		return classBytes;
	}

	private byte[] replaceMath(final String name, final byte[] classBytes) {

		boolean madeUpdate = false;

		final ClassReader cr = new ClassReader(classBytes);
		final ClassNode cn = new ClassNode(ASM5);
		cr.accept(cn, 0);

		for (final MethodNode m : cn.methods) {
			final ListIterator<AbstractInsnNode> itr = m.instructions.iterator();
			while (itr.hasNext()) {
				final AbstractInsnNode node = itr.next();
				if (node.getOpcode() == INVOKESTATIC) {
					final MethodInsnNode theInvoke = (MethodInsnNode) node;
					if ("java/lang/Math".equals(theInvoke.owner)) {
						if ("sin".equals(theInvoke.name) || "cos".equals(theInvoke.name)
								|| "random".equals(theInvoke.name)) {
							madeUpdate = true;
							m.instructions.set(node, new MethodInsnNode(INVOKESTATIC,
									"org/blockartistry/util/MathHelper", theInvoke.name, theInvoke.desc, false));
						}
					}
				}
			}
		}

		if (madeUpdate) {
			logger.debug("Replaced Math in " + name);
			ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
			cn.accept(cw);
			return cw.toByteArray();
		}
		return classBytes;
	}
}
