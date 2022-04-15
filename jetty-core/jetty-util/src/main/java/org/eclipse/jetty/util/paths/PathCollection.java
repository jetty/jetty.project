//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Generic Loader for Multiple Path references.
 * <p>
 * This fits in the same space as the ResourceCollection of past Jetty versions.
 * </p>
 */
public class PathCollection extends ArrayList<Path> implements AutoCloseable
{
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
