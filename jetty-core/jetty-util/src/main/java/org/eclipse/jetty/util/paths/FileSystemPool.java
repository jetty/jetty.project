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
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A pool of {@code zipfs} based {@code java.nio.file.FileSystem} objects to allow reference
 * counting of open FileSystem and proper closure when the last reference count is decremented.
 */
public class FileSystemPool
{
    private static final FileSystemPool INSTANCE = new FileSystemPool();

    public static FileSystem acquire(Path jarfile) throws IOException
    {
        return INSTANCE.acquireZipFs(jarfile, "runtime");
    }

    public static boolean release(Path jarfile) throws IOException
    {
        return INSTANCE.releaseZipFs(jarfile);
    }

    protected static Map<Path, FileSystemRefCount> getCache()
    {
        return INSTANCE.cache;
    }

    public static class FileSystemRefCount
    {
        private Path rootPath;
        private FileSystem fileSystem;
        private final AtomicInteger count = new AtomicInteger(0);
    }

    private final Map<Path, FileSystemRefCount> cache = new ConcurrentHashMap<>();

    public FileSystem acquireZipFs(Path jarFile, String multiRelease) throws IOException
    {
        FileSystemRefCount refCount = cache.get(jarFile);
        if (refCount == null)
        {
            Map<String, String> env = new HashMap<>();
            env.put("multi-release", multiRelease);

            URI uri = URI.create("jar:" + jarFile.toUri().toASCIIString());
            refCount = new FileSystemRefCount();
            refCount.fileSystem = FileSystems.newFileSystem(uri, env);
            refCount.rootPath = refCount.fileSystem.getPath("/");
            cache.put(jarFile, refCount);
        }

        refCount.count.incrementAndGet();
        return refCount.fileSystem;
    }

    public boolean releaseZipFs(Path jarFile) throws IOException
    {
        Path key = jarFile;
        FileSystemRefCount refCount = cache.get(jarFile);
        if (refCount == null)
        {
            FileSystem jarFs = jarFile.getFileSystem();
            // find via zipfs FileSystem type only
            if ("jar".equals(jarFs.provider().getScheme()))
            {
                for (Map.Entry<Path, FileSystemRefCount> entry : cache.entrySet())
                {
                    FileSystem fs = entry.getValue().fileSystem;
                    if (fs.equals(jarFs))
                    {
                        key = entry.getKey();
                        refCount = entry.getValue();
                        break;
                    }
                }
            }
        }

        if (refCount == null)
            return true;

        int count = refCount.count.decrementAndGet();
        if (count <= 0)
        {
            cache.remove(key);
            // Close the filesystem
            refCount.fileSystem.close();
            return true;
        }

        return false;
    }
}
