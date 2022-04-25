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
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;

/**
 * Generic Loader for Multiple Path references.
 * <p>
 * This fits in the same space as the ResourceCollection of past Jetty versions.
 * </p>
 */
public class PathCollection extends ArrayList<Path> implements AutoCloseable
{
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
                    Files.list(path)
                        .sorted(PathCollators.byName(true))
                        .forEach((entry) ->
                        {
                            if (!Files.isDirectory(entry) || globDirs)
                            {
                                paths.add(entry);
                            }
                        });
                }
            }
            else
            {
                // Simple reference, add as-is
                paths.add(Paths.get(token));
            }
        }

        return paths;
    }

    public PathCollection()
    {
    }

    public PathCollection(Path... paths)
    {
        clear();
        addAll(List.of(paths));
    }

    public void setPaths(Path... paths)
    {
        clear();
        addAll(List.of(paths));
    }

    private Path toSmartPath(Path path)
    {
        if (Files.isDirectory(path))
            return path;

        if (Files.isRegularFile(path) && path.getFileName().toString().toLowerCase(Locale.ENGLISH).endsWith(".jar"))
        {
            try
            {
                FileSystem fs = FileSystemPool.acquire(path);
                return fs.getPath("/");
            }
            catch (IOException e)
            {
                throw new IllegalStateException("ZipFs failure: " + path, e);
            }
        }

        throw new IllegalArgumentException("Unsupported Path: " + path);
    }

    @Override
    public boolean add(Path element)
    {
        return super.add(toSmartPath(element));
    }

    @Override
    public void add(int index, Path element)
    {
        super.add(index, toSmartPath(element));
    }

    @Override
    public boolean addAll(Collection<? extends Path> c)
    {
        boolean ret = false;
        for (Path p : c)
        {
            ret |= add(p);
        }
        return ret;
    }

    @Override
    public boolean addAll(int index, Collection<? extends Path> c)
    {
        int idx = index;
        for (Path p : c)
        {
            add(idx++, p);
        }
        return true;
    }

    @Override
    public void close() throws Exception
    {
        clear();
    }

    @Override
    public void trimToSize()
    {
        throw new UnsupportedOperationException("Not supported by pathCollection");
    }

    @Override
    public Path set(int index, Path element)
    {
        Path old = super.set(index, toSmartPath(element));
        try
        {
            FileSystemPool.release(old);
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to release old path: " + old);
        }
        return old;
    }

    @Override
    public void clear()
    {
        RuntimeException error = null;

        for (Path path : this)
        {
            try
            {
                FileSystemPool.release(path);
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
     * Resolve the first path that exists against the
     */
    public Path resolveFirstExisting(String other)
    {
        return resolveFirst(other, Files::exists);
    }

    public Path resolveFirst(String other, Predicate<Path> pathPredicate)
    {
        for (Path path : this)
        {
            Path dest = path.resolve(other);
            if (dest.startsWith(path) && pathPredicate.test(dest))
                return dest;
        }
        return null;
    }

    public List<Path> resolveAll(String other, Predicate<Path> pathPredicate)
    {
        List<Path> ret = new ArrayList<>();
        for (Path path : this)
        {
            Path dest = path.resolve(other);
            if (dest.startsWith(path) && pathPredicate.test(dest))
                ret.add(dest);
        }
        return ret;
    }

    public Stream<Path> find(BiPredicate<Path, BasicFileAttributes> pathPredicate)
    {
        Stream<Path> ret = Stream.of();

        for (Path path : this)
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
}
