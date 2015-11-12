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

import org.blockartistry.nbt.NBTFactory;
import org.blockartistry.nbt.NBTPrimitive;
import org.blockartistry.nbt.NBTTagCompound;
import org.blockartistry.world.chunk.storage.AnvilChunkLoader;
import org.blockartistry.world.chunk.storage.ByteArrayInputStreamNonAsync;
import org.blockartistry.world.chunk.storage.ChunkBuffer;
import org.blockartistry.world.chunk.storage.ChunkInputStream;
import org.blockartistry.world.chunk.storage.ChunkOutputStream;
import org.blockartistry.world.chunk.storage.RegionFile;
import org.blockartistry.world.chunk.storage.RegionFileCache;
import org.blockartistry.world.chunk.storage.RegionFileLRU;
import org.blockartistry.world.storage.ThreadedFileIOBase;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.RemappingClassAdapter;
import org.objectweb.asm.util.CheckClassAdapter;

import net.minecraft.launchwrapper.IClassTransformer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.blockartistry.nbt.*;

public class Transformer implements IClassTransformer {

	private static final boolean DO_NBT = true;
	private static final Logger logger = LogManager.getLogger();

	private static Map<String, Class<?>> targets = new HashMap<String, Class<?>>();
	
	static {

		targets.put("net.minecraft.world.chunk.storage.RegionFileCache", RegionFileCache.class);
		targets.put("net.minecraft.world.chunk.storage.RegionFile", RegionFile.class);
		targets.put("net.minecraft.world.chunk.storage.ChunkBuffer", ChunkBuffer.class);
		targets.put("net.minecraft.world.chunk.storage.RegionFileLRU", RegionFileLRU.class);
		targets.put("net.minecraft.world.chunk.storage.RegionFileLRU$RegionFileKey", RegionFileLRU.RegionFileKey.class);
		targets.put("net.minecraft.world.chunk.storage.ChunkOutputStream", ChunkOutputStream.class);
		targets.put("net.minecraft.world.chunk.storage.ChunkInputStream", ChunkInputStream.class);
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", AnvilChunkLoader.class);
		targets.put("net.minecraft.world.chunk.storage.ByteArrayInputStreamNonAsync", ByteArrayInputStreamNonAsync.class);
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase", ThreadedFileIOBase.class);
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$Factory", ThreadedFileIOBase.Factory.class);
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$IThreadedFileIORunnableWrapper", ThreadedFileIOBase.IThreadedFileIORunnableWrapper.class);
		
		targets.put("aqj", RegionFileCache.class);
		targets.put("aqh", RegionFile.class);
		targets.put("azr", ThreadedFileIOBase.class);
		targets.put("aqk", AnvilChunkLoader.class);

		if (DO_NBT) {
			targets.put("net.minecraft.nbt.CompressedStreamTools", CompressedStreamTools.class);
			targets.put("net.minecraft.nbt.NBTSizeTracker", NBTSizeTracker.class);
			targets.put("net.minecraft.nbt.NBTFactory", NBTFactory.class);
			targets.put("net.minecraft.nbt.NBTBase", NBTBase.class);
			targets.put("net.minecraft.nbt.NBTPrimitive", NBTPrimitive.class);
			targets.put("net.minecraft.nbt.NBTTagByte", NBTTagByte.class);
			targets.put("net.minecraft.nbt.NBTTagByteArray", NBTTagByteArray.class);
			targets.put("net.minecraft.nbt.NBTTagCompound", NBTTagCompound.class);
			targets.put("net.minecraft.nbt.NBTTagDouble", NBTTagDouble.class);
			targets.put("net.minecraft.nbt.NBTTagEnd", NBTTagEnd.class);
			targets.put("net.minecraft.nbt.NBTTagFloat", NBTTagFloat.class);
			targets.put("net.minecraft.nbt.NBTTagInt", NBTTagInt.class);
			targets.put("net.minecraft.nbt.NBTTagIntArray", NBTTagIntArray.class);
			targets.put("net.minecraft.nbt.NBTTagList", NBTTagList.class);
			targets.put("net.minecraft.nbt.NBTTagLong", NBTTagLong.class);
			targets.put("net.minecraft.nbt.NBTTagShort", NBTTagShort.class);
			targets.put("net.minecraft.nbt.NBTTagString", NBTTagString.class);

			targets.put("ds", NBTSizeTracker.class);
			targets.put("dy", NBTBase.class);
			targets.put("dg", NBTTagByte.class);
			targets.put("df", NBTTagByteArray.class);
			targets.put("dh", NBTTagCompound.class);
			targets.put("dk", NBTTagDouble.class);
			targets.put("dl", NBTTagEnd.class);
			targets.put("dm", NBTTagFloat.class);
			targets.put("dp", NBTTagInt.class);
			targets.put("dn", NBTTagIntArray.class);
			targets.put("dq", NBTTagList.class);
			targets.put("dr", NBTTagLong.class);
			targets.put("dw", NBTTagShort.class);
			targets.put("dx", NBTTagString.class);
		}
	}

	private byte[] getClassBytes(final Class<?> clazz) {
		try {
			final String name = clazz.getName().replace('.', '/') + ".class";
			InputStream stream = clazz.getClassLoader().getResourceAsStream(name);
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

		Class<?> src = targets.get(name);
		if (src != null) {
			final byte[] newBytes = getClassBytes(src);
			if(newBytes != null) {
				final ClassReader reader = new ClassReader(newBytes);
				final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
				final RenameMapper mapper = new RenameMapper();
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
