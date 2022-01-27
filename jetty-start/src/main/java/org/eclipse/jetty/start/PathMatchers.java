//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;

/**
 * Common PathMatcher implementations.
 */
public class PathMatchers
{
    private static class NonHiddenMatcher implements PathMatcher
    {
        @Override
        public boolean matches(Path path)
        {
            try
            {
                return !Files.isHidden(path);
            }
            catch (IOException e)
            {
                StartLog.debug(e);
                return false;
            }
        }
    }

    private static final char[] GLOB_CHARS = "*?".toCharArray();
    private static final char[] SYNTAXED_GLOB_CHARS = "{}[]|:".toCharArray();
    private static final Path EMPTY_PATH = new File(".").toPath();

    /**
     * Convert a pattern to a Path object.
     *
     * @param pattern the raw pattern (can contain "glob:" or "regex:" syntax indicator)
     * @return the Path version of the pattern provided.
     */
    private static Path asPath(final String pattern)
    {
        String test = pattern;
        if (test.startsWith("glob:"))
        {
            test = test.substring("glob:".length());
        }
        else if (test.startsWith("regex:"))
        {
            test = test.substring("regex:".length());
        }
        return new File(test).toPath();
    }

    public static PathMatcher getMatcher(final String rawpattern)
    {
        FileSystem fs = FileSystems.getDefault();

        String pattern = rawpattern;

        // Strip trailing slash (if present)
        int lastchar = pattern.charAt(pattern.length() - 1);
        if (lastchar == '/' || lastchar == '\\')
        {
            pattern = pattern.substring(0, pattern.length() - 1);
        }

        // If using FileSystem.getPathMatcher() with "glob:" or "regex:"
        // use FileSystem default pattern behavior
        if (pattern.startsWith("glob:") || pattern.startsWith("regex:"))
        {
            StartLog.debug("Using Standard " + fs.getClass().getName() + " pattern: " + pattern);
            return fs.getPathMatcher(pattern);
        }

        // If the pattern starts with a root path then its assumed to
        // be a full system path
        if (isAbsolute(pattern))
        {
            String pat = "glob:" + pattern;
            StartLog.debug("Using absolute path pattern: " + pat);
            return fs.getPathMatcher(pat);
        }

        // Doesn't start with filesystem root, then assume the pattern
        // is a relative file path pattern.
        String pat = "glob:**/" + pattern;
        StartLog.debug("Using relative path pattern: " + pat);
        return fs.getPathMatcher(pat);
    }

    public static PathMatcher getNonHidden()
    {
        return new NonHiddenMatcher();
    }

    /**
     * Provide the non-glob / non-regex prefix on the pattern as a Path reference.
     *
     * @param pattern the pattern to test
     * @return the Path representing the search root for the pattern provided.
     */
    public static Path getSearchRoot(final String pattern)
    {
        StringBuilder root = new StringBuilder();

        int start = 0;
        boolean syntaxed = false;
        if (pattern.startsWith("glob:"))
        {
            start = "glob:".length();
            syntaxed = true;
        }
        else if (pattern.startsWith("regex:"))
        {
            start = "regex:".length();
            syntaxed = true;
        }
        int len = pattern.length();
        int lastSep = 0;
        for (int i = start; i < len; i++)
        {
            int cp = pattern.codePointAt(i);
            if (cp < 127)
            {
                char c = (char)cp;

                // unix path case
                if (c == '/')
                {
                    root.append(c);
                    lastSep = root.length();
                }
                else if (c == '\\')
                {
                    root.append("\\");
                    lastSep = root.length();

                    // possible escaped sequence.
                    // only really interested in windows escape sequences "\\"
                    int count = countChars(pattern, i + 1, '\\');
                    if (count > 0)
                    {
                        // skip extra slashes
                        i += count;
                    }
                }
                else
                {
                    if (isGlob(c, syntaxed))
                    {
                        break;
                    }
                    root.append(c);
                }
            }
            else
            {
                root.appendCodePoint(cp);
            }
        }

        String rootPath = root.substring(0, lastSep);
        if (rootPath.length() <= 0)
        {
            return EMPTY_PATH;
        }

        return asPath(rootPath);
    }

    private static int countChars(String pattern, int offset, char c)
    {
        int count = 0;
        int len = pattern.length();
        for (int i = offset; i < len; i++)
        {
            if (pattern.charAt(i) == c)
            {
                count++;
            }
            else
            {
                break;
            }
        }
        return count;
    }

    /**
     * Tests if provided pattern is an absolute reference (or not)
     *
     * @param pattern the pattern to test
     * @return true if pattern is an absolute reference.
     */
    public static boolean isAbsolute(final String pattern)
    {
        Path searchRoot = getSearchRoot(pattern);
        if (searchRoot == EMPTY_PATH)
        {
            return false;
        }
        return searchRoot.isAbsolute();
    }

    /**
     * Determine if part is a glob pattern.
     *
     * @param part the string to check
     * @param syntaxed true if overall pattern is syntaxed with <code>"glob:"</code> or <code>"regex:"</code>
     * @return true if part has glob characters
     */
    private static boolean isGlob(char c, boolean syntaxed)
    {
        for (char g : GLOB_CHARS)
        {
            if (c == g)
            {
                return true;
            }
        }
        if (syntaxed)
        {
            for (char g : SYNTAXED_GLOB_CHARS)
            {
                if (c == g)
                {
                    return true;
                }
            }
        }
        return false;
    }
}
