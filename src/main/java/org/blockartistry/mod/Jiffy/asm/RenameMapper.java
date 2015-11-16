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

import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

import org.objectweb.asm.commons.Remapper;

public class RenameMapper extends Remapper {

	private final Map<String, Map<String, String>> methodNameMap;
	private final Map<String, String> classPatch;

	public RenameMapper(Map<String, Map<String, String>> methodMap) {
		this(methodMap, null);
	}
	
	public RenameMapper(Map<String, Map<String, String>> methodMap, Map<String, String> classPatch) {
		this.methodNameMap = methodMap;
		
		this.classPatch = new HashMap<String, String>();
		if(classPatch != null) {
			for(final Entry<String, String> e: classPatch.entrySet())
				this.classPatch.put(e.getKey(), ("org.blockartistry." + e.getValue()).replace('.',  '/'));
		}
	}

	protected String resolveItemName(final String owner, final String item, final String desc) {
		if (methodNameMap == null)
			return item;

		final int lastSlash = owner.lastIndexOf('/');
		final String className = owner.substring(lastSlash + 1);

		final Map<String, String> itemMap = methodNameMap.get(className);
		if (itemMap == null)
			return item;

		final String tempName = desc != null ? (item + desc) : item;
		final String newName = itemMap.get(tempName);
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