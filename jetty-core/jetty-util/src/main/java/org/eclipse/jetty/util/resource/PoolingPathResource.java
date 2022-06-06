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

package org.eclipse.jetty.util.resource;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.FileSystems;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java NIO Path Resource with file system pooling. All {@link FileSystem} implementations must use this class
 * except for the default one (returned by {@link FileSystems#getDefault()}).
 */
public class PoolingPathResource extends PathResource
{
    private static final Logger LOG = LoggerFactory.getLogger(PathResource.class);

    private static final Map<String, ?> EMPTY_ENV = new HashMap<>();
    private static final Map<FileSystem, AtomicInteger> POOL = new HashMap<>();
    private static final AutoLock POOL_LOCK = new AutoLock();

    private boolean closed;

    PoolingPathResource(URI uri) throws IOException
    {
        super(createFsIfNeeded(uri), true);
    }

    private static URI createFsIfNeeded(URI uri)
    {
        if (!uri.isAbsolute())
            throw new IllegalArgumentException("not an absolute uri: " + uri);
        if (PathResource.ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT)))
            throw new IllegalArgumentException("not an allowed scheme: " + uri);

        try (AutoLock ignore = POOL_LOCK.lock())
        {
            try
            {
                FileSystem fileSystem = Paths.get(uri).getFileSystem();
                retain(fileSystem);
            }
            catch (FileSystemNotFoundException fsnfe)
            {
                try
                {
                    FileSystem fileSystem = FileSystems.newFileSystem(uri, EMPTY_ENV);
                    retain(fileSystem);
                }
                catch (FileSystemAlreadyExistsException fsaee)
                {
                    FileSystem fileSystem = Paths.get(uri).getFileSystem();
                    retain(fileSystem);
                }
                catch (IOException ex)
                {
                    throw new IllegalArgumentException(ex);
                }
            }
            return uri;
        }
    }

    @Override
    public void close()
    {
        try (AutoLock ignore = POOL_LOCK.lock())
        {
            if (!closed)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("closing {}", this);
                closed = true;
                FileSystem fileSystem = Paths.get(getURI()).getFileSystem();
                release(fileSystem);
                super.close();
            }
        }
    }

    private static void retain(FileSystem fileSystem)
    {
        POOL.compute(fileSystem, (k, v) ->
        {
            if (v == null)
            {
                v = new AtomicInteger(1);
                if (LOG.isDebugEnabled())
                    LOG.debug("pooling new FS {}", fileSystem);
            }
            else
            {
                int count = v.incrementAndGet();
                if (LOG.isDebugEnabled())
                    LOG.debug("incremented ref counter to {} for FS {}", count, fileSystem);
            }
            return v;
        });
    }

    private void release(FileSystem fileSystem)
    {
        POOL.compute(fileSystem, (k, v) ->
        {
            if (v == null)
                return null;
            int count = v.decrementAndGet();
            if (count == 0)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("ref counter reached 0, closing pooled FS {}", fileSystem);
                IO.close(k);
                return null;
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("decremented ref counter to {} for FS {}", count, fileSystem);
            }
            return v;
        });
    }
}
