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

package org.eclipse.jetty.util.paths;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;

/**
 * Generic Loader for Multiple Path references.
 * <p>
 * This fits in the same space as the ResourceCollection of past Jetty versions.
 * </p>
 */
public final class PathCollection implements AutoCloseable
{
    private final List<Path> paths = new ArrayList<>();

    private PathCollection(Collection<Path> paths)
    {
        addAll(paths);
        // TODO: reject empty path collection?
    }

    private PathCollection(Path... paths)
    {
        addAll(List.of(paths));
        // TODO: reject empty path collection?
    }

    public PathCollection(Collection<Path> pathListA, Collection<Path> pathListB)
    {
        addAll(pathListA);
        addAll(pathListB);
        // TODO: reject empty path collection?
    }

    public static PathCollection from(Path... paths)
    {
        return new PathCollection(paths);
    }

    public static PathCollection from(Collection<Path> pathList, Path... paths)
    {
        return new PathCollection(pathList, List.of(paths));
    }

    public static PathCollection from(Collection<Path> pathListA, Collection<Path> pathListB)
    {
        return new PathCollection(pathListA, pathListB);
    }

    public static PathCollection from(Stream<Path> paths)
    {
        return new PathCollection(paths.collect(Collectors.toList()));
    }

    /**
     * Parse a delimited String of resource references and
     * return a PathCollection it represents.
     *
     * <p>
     * Supports glob references that end in {@code /*} or {@code \*}.
     * Glob references will only iterate through the level specified and will not traverse
     * found directories within the glob reference.
     * </p>
     *
     * @param resources the comma {@code ,} or semicolon {@code ;} delimited
     * String of path references.
     * @param globDirs true to return directories in addition to files at the level of the glob
     * @return the PathCollection parsed from input string.
     */
    public static PathCollection fromList(String resources, boolean globDirs) throws IOException
    {
        PathCollection paths = new PathCollection();
        if (StringUtil.isBlank(resources))
        {
            return paths;
        }

        StringTokenizer tokenizer = new StringTokenizer(resources, StringUtil.DEFAULT_DELIMS);
        while (tokenizer.hasMoreTokens())
        {
            String token = tokenizer.nextToken().trim();

            // Is this a glob reference?
            if (token.endsWith("/*") || token.endsWith("\\*"))
            {
                String dir = token.substring(0, token.length() - 2);

                Path path = Paths.get(dir);
                if (Files.isDirectory(path))
                {
                    // To obtain the list of entries
                    Files.list(path).sorted(PathCollators.byName(true)).forEach((entry) ->
                    {
                        if (!Files.isDirectory(entry) || globDirs)
                        {
                            paths.paths.add(paths.toSmartPath(entry));
                        }
                    });
                }
            }
            else
            {
                // Simple reference, add as-is
                paths.paths.add(paths.toSmartPath(Paths.get(token)));
            }
        }

        return paths;
    }

    @Override
    public void close()
    {
        RuntimeException error = null;

        for (Path path : paths)
        {
            try
            {
                ZipFsPool.release(path);
            }
            catch (IOException e)
            {
                if (error == null)
                    error = new RuntimeException("Unable to release some paths");
                error.addSuppressed(e);
            }
        }
        if (error != null)
            throw error;
    }

    /**
     * Find all files (up to 300 levels deep) in the path collection that match the provided {@link BiPredicate}
     * of &lt;{@link Path}, {@link BasicFileAttributes}&gt;
     *
     * @param pathPredicate the predicate to evaluate across all paths in this collection
     * @return the stream of hits for the find operation.
     */
    public Stream<Path> find(BiPredicate<Path, BasicFileAttributes> pathPredicate)
    {
        Stream<Path> ret = Stream.of();

        for (Path path : paths)
        {
            try
            {
                Stream<Path> stream = Files.find(path, 300, pathPredicate);
                ret = Stream.concat(ret, stream);
            }
            catch (IOException e)
            {
                e.printStackTrace();
            }
        }
        return ret;
    }

    public boolean isEmpty()
    {
        return paths.isEmpty();
    }

    public Stream<Path> stream()
    {
        return paths.stream();
    }

    /**
     * Resolve all paths across the path collection that matches the provided other.
     *
     * @param other the other path to resolve against, assumes a String in unix format, that is already URI decoded from URI space.
     * @param pathPredicate the predicate of {@code Path} to evaluate for valid hit.
     * @return the list of {@link Path} objects that satisfy the combination of {@code other} and {@code Predicate<Path>}
     */
    public List<Path> resolveAll(String other, Predicate<Path> pathPredicate)
    {
        List<Path> ret = new ArrayList<>();
        for (Path path : paths)
        {
            Path dest = path.resolve(other);
            if (dest.startsWith(path) && pathPredicate.test(dest))
                ret.add(dest);
        }
        return ret;
    }

    /**
     * Resolve the first path that matches the predicate.
     *
     * @param other the other path to resolve against, assumes a String in unix format, that is already URI decoded from URI space.
     * @param pathPredicate the predicate of {@code Path} to evaluate for first valid hit.
     */
    public Path resolveFirst(String other, Predicate<Path> pathPredicate)
    {
        for (Path path : paths)
        {
            Path dest = path.resolve(other);
            if (dest.startsWith(path) && pathPredicate.test(dest))
                return dest;
        }
        return null;
    }

    /**
     * Resolve the first path that exists against the collection of paths.
     *
     * @param other the other path to resolve against, assumes a String in unix format, that is already URI decoded from URI space.
     * @see Path#resolve(String)
     */
    public Path resolveFirstExisting(String other)
    {
        return resolveFirst(other, Files::exists);
    }

    public int size()
    {
        return paths.size();
    }

    private void addAll(Collection<Path> c)
    {
        for (Path p : c)
        {
            this.paths.add(toSmartPath(p));
        }
    }

    private Path toSmartPath(Path path)
    {
        if (Files.isDirectory(path))
            return path;

        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".jar"))
        {
            try
            {
                FileSystem fs = ZipFsPool.acquire(path);
                return fs.getPath("/");
            }
            catch (IOException e)
            {
                throw new IllegalStateException("ZipFs failure: " + path, e);
            }
        }

        throw new IllegalArgumentException("Unsupported Path: " + path);
    }
}
