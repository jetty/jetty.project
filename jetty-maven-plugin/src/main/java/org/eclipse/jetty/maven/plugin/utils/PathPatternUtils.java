//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin.utils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;

public final class PathPatternUtils
{
    private PathPatternUtils()
    {

    }

    /**
     * checks whether given path is permitted (matched) by include patterns and exclude patterns
     *
     * @param path - path
     * @param includes - includes
     * @param excludes - excludes
     * @return true if is permitted
     */
    public static boolean isPermitted(String path, Collection<String> includes, Collection<String> excludes)
    {
        includes = includes == null ? new ArrayList<>() : includes;
        boolean included = includes.isEmpty() || isMatched(path, includes, false);

        excludes = excludes == null ? new ArrayList<>() : excludes;
        boolean excluded = !excludes.isEmpty() && isMatched(path, excludes, false);
        return included && !excluded;
    }

    private static boolean isMatched(String path, Collection<String> includes, boolean caseSensitive)
    {
        for (String pattern : includes)
        {
            if (org.codehaus.plexus.util.SelectorUtils.matchPath(pattern, path, caseSensitive))
            {
                return true;
            }
        }
        return false;
    }

    /**
     * will replace file separators ('/' or '\') with '/' in path pattern
     *
     * @param pattern collection
     * @return normalized collection of patterns
     */
    public static Collection<String> normalizePathPattern(Collection<String> pattern)
    {
        return CollectionUtils.nullToEmpty(pattern).stream()
            .map(PathPatternUtils::normalizePathPattern)
            .collect(Collectors.toList());
    }

    /**
     * Will replace
     */
    public static String normalizePathPattern(String pattern)
    {
        return StringUtil.replace(pattern, "\\", "/");
    }

    /**
     * adds "/**" at the end of path pattern
     *
     * @param pattern collection
     * @return updated collection of patterns
     */
    public static Collection<String> addFolderContentPathPattern(Collection<String> pattern)
    {
        return CollectionUtils.nullToEmpty(pattern).stream()
            .map(e -> e + "/**")
            .collect(Collectors.toList());
    }

    /**
     * Retrieves relative path for two resources
     *
     * @param root - parent resource
     * @param child - child resource
     * @return relative path
     */
    public static String relativePath(Resource root, Resource child)
    {
        if (root == null || child == null)
        {
            throw new IllegalArgumentException("'root' or 'child' is null");
        }
        try
        {
            Path rootPath = root.getFile().toPath().normalize();
            Path childPath = child.getFile().toPath().normalize();
            if (!childPath.startsWith(rootPath))
            {
                throw new IllegalArgumentException("Given 'child' does not belong 'root' directory");
            }
            String relative = rootPath.relativize(childPath).toString();
            return normalizePathPattern(relative);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Failed to get relative path", e);
        }
    }
}
