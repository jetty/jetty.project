//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

/**
 * 
 */
package org.eclipse.jetty.plugins.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @author tbecker
 * 
 */
public class RepositoryParser {
	private final static List<String> EXCLUDES = Arrays.asList("..");

	public static List<String> parseLinksInDirectoryListing(String listing) {
		List<String> modules = new ArrayList<String>();
		List<String> lines = Arrays.asList(listing.split("\n"));
		for (String line : lines) {
			Pattern p = Pattern.compile(".*?<a href=\"[^>]+>(?=([^</]+)/).*");
			Matcher m = p.matcher(line);
			if (m.matches()) {
				if (!EXCLUDES.contains(m.group(1))) {
					modules.add(m.group(1));
				}
			}
		}
		return modules;
	}

	public static boolean isModuleAPlugin(String listing) {
		List<String> lines = Arrays.asList(listing.split("\n"));
		for (String line : lines) {
			Pattern p = Pattern.compile("-plugin\\.jar");
			Matcher m = p.matcher(line);
			if (m.find()) {
				return true;
			}
		}
		return false;
	}

}
