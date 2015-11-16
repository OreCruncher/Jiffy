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

import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.RemappingClassAdapter;
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
	
	static {

		targets.put("net.minecraft.world.chunk.storage.RegionFileCache", "world.chunk.storage.RegionFileCache");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileLoader", "world.chunk.storage.RegionFileCache$RegionFileLoader");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileEviction", "world.chunk.storage.RegionFileCache$RegionFileEviction");
		targets.put("net.minecraft.world.chunk.storage.RegionFileCache$RegionFileKey", "world.chunk.storage.RegionFileCache$RegionFileKey");

		targets.put("net.minecraft.world.chunk.storage.RegionFile", "world.chunk.storage.RegionFile");

		targets.put("net.minecraft.world.chunk.storage.ChunkBuffer", "world.chunk.storage.ChunkBuffer");
		targets.put("net.minecraft.world.chunk.storage.ChunkOutputStream", "world.chunk.storage.ChunkOutputStream");
		targets.put("net.minecraft.world.chunk.storage.ChunkInputStream", "world.chunk.storage.ChunkInputStream");
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", "world.chunk.storage.AnvilChunkLoader");
		targets.put("net.minecraft.world.chunk.storage.AttachableByteArrayInputStream",
				"world.chunk.storage.AttachableByteArrayInputStream");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase", "world.storage.ThreadedFileIOBase");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO", "world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO");

		targets.put("aqj", "world.chunk.storage.RegionFileCache");
		targets.put("aqj$RegionFileLoader", "world.chunk.storage.RegionFileCache$RegionFileLoader");
		targets.put("aqj$RegionFileEviction", "world.chunk.storage.RegionFileCache$RegionFileEviction");
		targets.put("aqj$RegionFileKey", "world.chunk.storage.RegionFileCache$RegionFileKey");
		targets.put("aqh", "world.chunk.storage.RegionFile");
		targets.put("azr", "world.storage.ThreadedFileIOBase");
		targets.put("azr$WrapperIThreadedFileIO", "world.storage.ThreadedFileIOBase$WrapperIThreadedFileIO");
		targets.put("aqk", "world.chunk.storage.AnvilChunkLoader");

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
		
		/////////////////
		//
		// NBT Classes
		//
		/////////////////
		
		targets.put("net.minecraft.nbt.NBTHelper", "nbt.NBTHelper");
		targets.put("net.minecraft.nbt.NBTTagByteArray", "nbt.NBTTagByteArray");
		targets.put("net.minecraft.nbt.NBTTagCompound", "nbt.NBTTagCompound");
		targets.put("net.minecraft.nbt.NBTTagIntArray", "nbt.NBTTagIntArray");
		targets.put("net.minecraft.nbt.NBTTagList", "nbt.NBTTagList");
		
		targets.put("df", "nbt.NBTTagByteArray");
		targets.put("dh", "nbt.NBTTagCompound");
		targets.put("dn", "nbt.NBTTagIntArray");
		targets.put("dq", "nbt.NBTTagList");
		
		// NBTTagCompound
		map = new HashMap<String, String>();
		map.put("getBoolean(Ljava/lang/String;)Z", "func_74767_n");
		map.put("getByteArray(Ljava/lang/String;)[B", "func_74770_j");
		map.put("getTagList(Ljava/lang/String;I)Lorg/blockartistry/nbt/NBTTagList;", "func_150295_c");
		map.put("setIntArray(Ljava/lang/String;[I)V", "func_74783_a");
		map.put("getTag(Ljava/lang/String;)Lnet/minecraft/nbt/NBTBase;", "func_74781_a");
		map.put("setByteArray(Ljava/lang/String;[B)V", "func_74773_a");
		map.put("setTag(Ljava/lang/String;Lnet/minecraft/nbt/NBTBase;)V", "func_74782_a");
		map.put("getIntArray(Ljava/lang/String;)[I", "func_74759_k");
		map.put("getFloat(Ljava/lang/String;)F", "func_74760_g");
		map.put("setString(Ljava/lang/String;Ljava/lang/String;)V", "func_74778_a");
		map.put("getByte(Ljava/lang/String;)B", "func_74771_c");
		map.put("getCompoundTag(Ljava/lang/String;)Lorg/blockartistry/nbt/NBTTagCompound;", "func_74775_l");
		map.put("getString(Ljava/lang/String;)Ljava/lang/String;", "func_74779_i");
		map.put("getShort(Ljava/lang/String;)S", "func_74765_d");
		map.put("getLong(Ljava/lang/String;)J", "func_74763_f");
		map.put("hasKey(Ljava/lang/String;)Z", "func_74764_b");
		map.put("getDouble(Ljava/lang/String;)D", "func_74769_h");
		map.put("hasKey(Ljava/lang/String;I)Z", "func_150297_b");
		map.put("setFloat(Ljava/lang/String;F)V", "func_74776_a");
		map.put("getInteger(Ljava/lang/String;)I", "func_74762_e");
		map.put("setInteger(Ljava/lang/String;I)V", "func_74768_a");
		map.put("removeTag(Ljava/lang/String;)V", "func_82580_o");
		map.put("setLong(Ljava/lang/String;J)V", "func_74772_a");
		map.put("setByte(Ljava/lang/String;B)V", "func_74774_a");
		map.put("setBoolean(Ljava/lang/String;Z)V", "func_74757_a");
		map.put("createCrashReport(Ljava/lang/String;ILjava/lang/ClassCastException;)Lnet/minecraft/crash/CrashReport;",
				"func_82581_a");
		map.put("setDouble(Ljava/lang/String;D)V", "func_74780_a");
		map.put("hasNoTags()Z", "func_82582_d");
		map.put("setShort(Ljava/lang/String;S)V", "func_74777_a");
		map.put("copy()net/minecraft/nbt/NBTBase;", "func_74737_b");
		map.put("getId()B", "func_74732_a");
		obsRemap.put("NBTTagCompound", map);
		obsRemap.put("dh", map);

		// NBTTagList
		map = new HashMap<String, String>();
		map.put("getCompoundTagAt(I)Lorg/blockartistry/nbt/NBTTagCompound;", "func_150305_b");
		map.put("removeTag(I)Lnet/minecraft/nbt/NBTBase;", "func_74744_a");
		map.put("tagCount()I", "func_74745_c");
		map.put("appendTag(Lnet/minecraft/nbt/NBTBase;)V", "func_74742_a");
		map.put("getStringTagAt(I)Ljava/lang/String;", "func_150307_f");
		map.put("write(Ljava/io/DataOutput;)V", "func_74734_a");
		map.put("copy()Lnet/minecraft/nbt/NBTBase;", "func_74737_b");
		map.put("getId()B", "func_74732_a");
		obsRemap.put("NBTTagList", map);
		obsRemap.put("dq", map);
		
		// NBTTagByteArray
		map = new HashMap<String, String>();
		map.put("copy()Lnet/minecraft/nbt/NBTBase;", "func_74737_b");
		map.put("getId()B", "func_74732_a");
		obsRemap.put("NBTTagByteArray", map);
		obsRemap.put("df", map);
		
		// NBTTagIntArray
		map = new HashMap<String, String>();
		map.put("copy()net/minecraft/nbt/NBTBase;", "func_74737_b");
		map.put("getId()B", "func_74732_a");
		obsRemap.put("NBTTagIntArray", map);
		obsRemap.put("dn", map);
	}

	private byte[] getClassBytes(final String clazz) {
		try {
			final String name = ("org.blockartistry." + clazz).replace('.', '/') + ".class";
			InputStream stream = Transformer.class.getClassLoader().getResourceAsStream(name);
			final byte[] result = new byte[stream.available()];
			stream.read(result);
			return result;
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
				logger.info("Transforming '" + name + "' to '" + src + "'");
				final ClassReader reader = new ClassReader(newBytes);
				final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
				final RenameMapper mapper = new RenameMapper(TransformLoader.runtimeDeobEnabled ? obsRemap : null);
				final RemappingClassAdapter adapter = new RemappingClassAdapter(writer, mapper);
				reader.accept(adapter, ClassReader.EXPAND_FRAMES);
				final byte[] result = writer.toByteArray();
				if (verifyClassBytes(result)) {
					logger.info("Transform success '" + name + "'");
					return result;
				}
			} else {
				logger.warn("Unable to find classbytes for " + src);
			}
		}

		return basicClass;
	}
}
