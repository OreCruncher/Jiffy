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

	private static final boolean DO_NBT = true;
	private static final Logger logger = LogManager.getLogger();

	private static Map<String, String> targets = new HashMap<String, String>();
	private static Map<String, Map<String, String>> obsRemap = new HashMap<String, Map<String,String>>();
	
	static {

		targets.put("net.minecraft.world.chunk.storage.RegionFileCache", "world.chunk.storage.RegionFileCache");
		targets.put("net.minecraft.world.chunk.storage.RegionFile", "world.chunk.storage.RegionFile");
		targets.put("net.minecraft.world.chunk.storage.ChunkBuffer", "world.chunk.storage.ChunkBuffer");
		targets.put("net.minecraft.world.chunk.storage.RegionFileLRU", "world.chunk.storage.RegionFileLRU");
		targets.put("net.minecraft.world.chunk.storage.RegionFileKey", "world.chunk.storage.RegionFileKey");
		targets.put("net.minecraft.world.chunk.storage.ChunkOutputStream", "world.chunk.storage.ChunkOutputStream");
		targets.put("net.minecraft.world.chunk.storage.ChunkInputStream", "world.chunk.storage.ChunkInputStream");
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", "world.chunk.storage.AnvilChunkLoader");
		targets.put("net.minecraft.world.chunk.storage.ByteArrayInputStreamNonAsync", "world.chunk.storage.ByteArrayInputStreamNonAsync");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase", "world.storage.ThreadedFileIOBase");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$Factory", "world.storage.ThreadedFileIOBase$Factory");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$IThreadedFileIORunnableWrapper", "world.storage.ThreadedFileIOBase$IThreadedFileIORunnableWrapper");
		
		targets.put("aqj", "world.chunk.storage.RegionFileCache");
		targets.put("aqh", "world.chunk.storage.RegionFile");
		targets.put("azr", "world.storage.ThreadedFileIOBase");
		targets.put("aqk", "world.chunk.storage.AnvilChunkLoader");
		
		if (DO_NBT) {
			targets.put("net.minecraft.nbt.CompressedStreamTools", "nbt.CompressedStreamTools");
			targets.put("net.minecraft.nbt.NBTSizeTracker", "nbt.NBTSizeTracker");
			targets.put("net.minecraft.nbt.NBTFactory", "nbt.NBTFactory");
			targets.put("net.minecraft.nbt.NBTBase", "nbt.NBTBase");
			targets.put("net.minecraft.nbt.NBTPrimitive", "nbt.NBTPrimitive");
			targets.put("net.minecraft.nbt.NBTTagByte", "nbt.NBTTagByte");
			targets.put("net.minecraft.nbt.NBTTagByteArray", "nbt.NBTTagByteArray");
			targets.put("net.minecraft.nbt.NBTTagCompound", "nbt.NBTTagCompound");
			targets.put("net.minecraft.nbt.NBTTagDouble", "nbt.NBTTagDouble");
			targets.put("net.minecraft.nbt.NBTTagEnd", "nbt.NBTTagEnd");
			targets.put("net.minecraft.nbt.NBTTagFloat", "nbt.NBTTagFloat");
			targets.put("net.minecraft.nbt.NBTTagInt", "nbt.NBTTagInt");
			targets.put("net.minecraft.nbt.NBTTagIntArray", "nbt.NBTTagIntArray");
			targets.put("net.minecraft.nbt.NBTTagList", "nbt.NBTTagList");
			targets.put("net.minecraft.nbt.NBTTagLong", "nbt.NBTTagLong");
			targets.put("net.minecraft.nbt.NBTTagShort", "nbt.NBTTagShort");
			targets.put("net.minecraft.nbt.NBTTagString", "nbt.NBTTagString");

			targets.put("ds", "nbt.NBTSizeTracker");
			targets.put("dy", "nbt.NBTBase");
			targets.put("dg", "nbt.NBTTagByte");
			targets.put("df", "nbt.NBTTagByteArray");
			targets.put("dh", "nbt.NBTTagCompound");
			targets.put("dk", "nbt.NBTTagDouble");
			targets.put("dl", "nbt.NBTTagEnd");
			targets.put("dm", "nbt.NBTTagFloat");
			targets.put("dp", "nbt.NBTTagInt");
			targets.put("dn", "nbt.NBTTagIntArray");
			targets.put("dq", "nbt.NBTTagList");
			targets.put("dr", "nbt.NBTTagLong");
			targets.put("dw", "nbt.NBTTagShort");
			targets.put("dx", "nbt.NBTTagString");
		}
		
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

		if (DO_NBT) {
			// CompressedStreamTools
			map = new HashMap<String, String>();
			map.put("safeWrite(Lorg/blockartistry/nbt/NBTTagCompound;Ljava/io/File;)V", "func_74793_a");
			map.put("write(Lorg/blockartistry/nbt/NBTTagCompound;Ljava/io/File;)V", "func_74795_b");
			map.put("write(Lorg/blockartistry/nbt/NBTTagCompound;Ljava/io/DataOutput;)V", "func_74800_a");
			map.put("read(Ljava/io/DataInputStream;)Lorg/blockartistry/nbt/NBTTagCompound;", "func_74794_a");
			map.put("writeCompressed(Lorg/blockartistry/nbt/NBTTagCompound;Ljava/io/OutputStream;)V", "func_74799_a");
			map.put("read(Ljava/io/File;)Lorg/blockartistry/nbt/NBTTagCompound;", "func_74797_a");
			map.put("readCompressed(Ljava/io/InputStream;)Lorg/blockartistry/nbt/NBTTagCompound;", "func_74796_a");
			map.put("compress(Lorg/blockartistry/nbt/NBTTagCompound;)[B", "func_74798_a");
			obsRemap.put("CompressedStreamTools", map);

			// NBTBase

			// NBTSizeTracker
			// Nothing

			// NBTTagByte

			// NBTTagByteArray

			// NBTTagCompound
			map = new HashMap<String, String>();
			map.put("getBoolean(Ljava/lang/String;)Z", "func_74767_n");
			map.put("getByteArray(Ljava/lang/String;)[B", "func_74770_j");
			map.put("getTagList(Ljava/lang/String;I)Lorg/blockartistry/nbt/NBTTagList;", "func_150295_c");
			map.put("setIntArray(Ljava/lang/String;[I)V", "func_74783_a");
			map.put("getTag(Ljava/lang/String;)Lorg/blockartistry/nbt/NBTBase;", "func_74781_a");
			map.put("setByteArray(Ljava/lang/String;[B)V", "func_74773_a");
			map.put("setTag(Ljava/lang/String;Lorg/blockartistry/nbt/NBTBase;)V", "func_74782_a");
			map.put("getIntArray(Ljava/lang/String;)[I", "func_74759_k");
			map.put("getFloat(Ljava/lang/String;)F", "func_74760_g");
			map.put("setString(Ljava/lang/String;Ljava/lang/String;)V", "func_74778_a");
			map.put("getByte(Ljava/lang/String;)B", "func_74771_c");
			map.put("getCompoundTag(Ljava/lang/String;)Lorg/blockartistry/NBTTagCompound;", "func_74775_l");
			map.put("getString(Ljava/lang/String;)Ljava/lang/String;", "func_74779_i");
			map.put("getShort(Ljava/lang/String;)S", "func_74765_d");
			map.put("getLong(Ljava/lang/String;)J", "func_74763_f");
			map.put("hasKey(Ljava/lang/String;)Z", "func_74764_b");
			map.put("getDouble(Ljava/lang/String;)D", "func_74769_h");
			map.put("hasKey(Ljava/lang/String;I)Z", "func_150297_b");
			map.put("setFloat(Ljava/lang/String;F)V", "func_74776_a");
			map.put("getInteger(Ljava/lang/String;)I", "func_74762_e");
			map.put("copy()Lorg/blockartistry/nbt/NBTBase;", "func_74737_b");
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
			map.put("copy()Lorg/blockartistry/nbt/NBTBase;", "func_74737_b");
			obsRemap.put("NBTTagCompound", map);

			// NBTTagDouble

			// NBTTagEnd

			// NBTTagFloat


			// NBTTagInt

			// NBTTagIntArray

			// NBTTagList
			map = new HashMap<String, String>();
			map.put("getCompoundTagAt(I)Lorg/blockartistry/nbt/NBTTagCompound;", "func_150305_b");
			map.put("removeTag(I)Lorg/blockartistry/nbt/NBTBase;", "func_74744_a");
			map.put("tagCount()I", "func_74745_c");
			map.put("appendTag(Lorg/blockartistry/nbt/NBTBase;)V", "func_74742_a");
			map.put("getStringTagAt(I)Ljava/lang/String;", "func_150307_f");
			map.put("write(Ljava/io/DataOutput;)V", "func_74734_a");
			map.put("copy()Lorg/blockartistry/nbt/NBTBase;", "func_74737_b");
			obsRemap.put("NBTTagList", map);

			// NBTTagLong

			// NBTTagShort

			// NBTTagString
		}
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
			if(newBytes != null) {
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
			}
		}

		return basicClass;
	}
}
