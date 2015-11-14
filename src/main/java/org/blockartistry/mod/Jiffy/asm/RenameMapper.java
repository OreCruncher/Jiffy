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

import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.commons.Remapper;

public class RenameMapper extends Remapper {

	private final Map<String, Map<String, String>> methodNameMap;

	private static final Logger logger = LogManager.getLogger();
	public RenameMapper() {
		this.methodNameMap = null;
	}

	public RenameMapper(Map<String, Map<String, String>> methodMap) {
		this.methodNameMap = methodMap;
	}

	protected String resolveItemName(final String owner, final String item, final String desc) {
		if (methodNameMap == null)
			return item;

		final boolean printDBG = owner.contains("ThreadedFileIOBase");

		if (printDBG) {
			logger.info("============================= YOO HOO ==========================");
			logger.info(String.format("owner: %s\n", owner));
			logger.info(String.format("item: %s\n", item));
			logger.info(String.format("desc: %s\n", desc));
			logger.info("=============================   END   ==========================");
		}

		final int lastSlash = owner.lastIndexOf('/');
		final String className = owner.substring(lastSlash + 1);

		if (printDBG)
			logger.info(String.format("CLASSNAME: '%s'", className));
		final Map<String, String> itemMap = methodNameMap.get(className);
		if (itemMap == null)
			return item;

		final String tempName = desc != null ? (item + desc) : item;

		if (printDBG)
			logger.info(String.format("TEMPNAME: '%s'", tempName));

		final String newName = itemMap.get(tempName);
		if (printDBG)
			logger.info(String.format("NEWNAME: '%s'", newName == null ? "NULL" : newName));

		return newName == null ? item : newName;
	}

	@Override
	public String mapMethodName(String owner, String name, String desc) {
		return resolveItemName(owner, name, desc);
	}

	@Override
	public String mapFieldName(String owner, String name, String desc) {
		return resolveItemName(owner, name, null);
	}

	@Override
	public String mapDesc(final String desc) {
		return fix(desc);
	}

	@Override
	public String mapType(String type) {
		return fix(type);
	}

	@Override
	public String map(String key) {
		return fix(key);
	}

	private String fix(String s) {
		if (s != null) {
			s = s.replaceAll("org/blockartistry/", "net/minecraft/");
		}
		return s;
	}
}