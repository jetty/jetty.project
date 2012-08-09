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
