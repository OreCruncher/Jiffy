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

	private static final Logger logger = LogManager.getLogger();

	private static Map<String, String> targets = new HashMap<String, String>();
	private static Map<String, Map<String, String>> obsRemap = new HashMap<String, Map<String, String>>();

	static {

		targets.put("net.minecraft.world.chunk.storage.RegionFileCache", "world.chunk.storage.RegionFileCache");
		targets.put("net.minecraft.world.chunk.storage.RegionFile", "world.chunk.storage.RegionFile");
		targets.put("net.minecraft.world.chunk.storage.ChunkBuffer", "world.chunk.storage.ChunkBuffer");
		targets.put("net.minecraft.world.chunk.storage.RegionFileLRU", "world.chunk.storage.RegionFileLRU");
		targets.put("net.minecraft.world.chunk.storage.RegionFileKey", "world.chunk.storage.RegionFileKey");
		targets.put("net.minecraft.world.chunk.storage.ChunkOutputStream", "world.chunk.storage.ChunkOutputStream");
		targets.put("net.minecraft.world.chunk.storage.ChunkInputStream", "world.chunk.storage.ChunkInputStream");
		targets.put("net.minecraft.world.chunk.storage.AnvilChunkLoader", "world.chunk.storage.AnvilChunkLoader");
		targets.put("net.minecraft.world.chunk.storage.ByteArrayInputStreamNonAsync",
				"world.chunk.storage.ByteArrayInputStreamNonAsync");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase", "world.storage.ThreadedFileIOBase");
		targets.put("net.minecraft.world.storage.IThreadedFileIOWrapper", "world.storage.IThreadedFileIOWrapper");
		targets.put("net.minecraft.world.storage.ThreadedFileIOBase$Factory", "world.storage.ThreadedFileIOBase$Factory");

		targets.put("aqj", "world.chunk.storage.RegionFileCache");
		targets.put("aqh", "world.chunk.storage.RegionFile");
		targets.put("azr", "world.storage.ThreadedFileIOBase");
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
