//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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
    
    private static final char GLOB_CHARS[] = "*?".toCharArray();
    private static final char SYNTAXED_GLOB_CHARS[] = "{}[]|:".toCharArray();
    private static final Path EMPTY_PATH = new File(".").toPath();

    /**
     * Convert a pattern to a Path object.
     * 
     * @param pattern
     *            the raw pattern (can contain "glob:" or "regex:" syntax indicator)
     * @return the Path version of the pattern provided.
     */
    private static Path asPath(String pattern)
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

    public static PathMatcher getMatcher(String pattern)
    {
        FileSystem fs = FileSystems.getDefault();

        // If using FileSystem.getPathMatcher() with "glob:" or "regex:"
        // use FileSystem default pattern behavior
        if (pattern.startsWith("glob:") || pattern.startsWith("regex:"))
        {
            StartLog.debug("Using Standard " + fs.getClass().getName() + " pattern: " + pattern);
            return fs.getPathMatcher(pattern);
        }

        // If the pattern starts with a root path then its assumed to
        // be a full system path
        for (Path root : fs.getRootDirectories())
        {
            StartLog.debug("root: " + root);
            if (pattern.startsWith(root.toString()))
            {
                String pat = "glob:" + pattern;
                StartLog.debug("Using absolute path pattern: " + pat);
                return fs.getPathMatcher(pat);
            }
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
     * @param pattern
     *            the pattern to test
     * @return the Path representing the search root for the pattern provided.
     */
    public static Path getSearchRoot(final String pattern)
    {
        Path path = asPath(pattern);
        Path test = path.getRoot();

        boolean isSyntaxed = pattern.startsWith("glob:") || pattern.startsWith("regex:");

        int len = path.getNameCount();
        for (int i = 0; i < len; i++)
        {
            Path part = path.getName(i);
            if (isGlob(part.toString(),isSyntaxed))
            {
                // found a glob part, return prior parts now
                break;
            }

            // is this the last entry?
            if (i == (len - 1))
            {
                // always return prior entries
                break;
            }

            if (test == null)
            {
                test = part;
            }
            else
            {
                test = test.resolve(part);
            }
        }

        if (test == null)
        {
            return EMPTY_PATH;
        }
        return test;
    }

    /**
     * Tests if provided pattern is an absolute reference (or not)
     * 
     * @param pattern
     *            the pattern to test
     * @return true if pattern is an absolute reference.
     */
    public static boolean isAbsolute(final String pattern)
    {
        return asPath(pattern).isAbsolute();
    }

    /**
     * Determine if part is a glob pattern.
     * 
     * @param part
     *            the string to check
     * @param syntaxed
     *            true if overall pattern is syntaxed with <code>"glob:"</code> or <code>"regex:"</code>
     * @return true if part has glob characters
     */
    private static boolean isGlob(String part, boolean syntaxed)
    {
        int len = part.length();
        for (int i = 0; i < len; i++)
        {
            char c = part.charAt(i);
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
        }
        return false;
    }
}
