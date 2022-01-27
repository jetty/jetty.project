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

package org.eclipse.jetty.client.util;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.NoSuchElementException;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A {@link ContentProvider} for files using JDK 7's {@code java.nio.file} APIs.</p>
 * <p>It is possible to specify, at the constructor, a buffer size used to read
 * content from the stream, by default 4096 bytes.
 * If a {@link ByteBufferPool} is provided via {@link #setByteBufferPool(ByteBufferPool)},
 * the buffer will be allocated from that pool, otherwise one buffer will be
 * allocated and used to read the file.</p>
 *
 * @deprecated use {@link PathRequestContent} instead.
 */
@Deprecated
public class PathContentProvider extends AbstractTypedContentProvider
{
    private static final Logger LOG = LoggerFactory.getLogger(PathContentProvider.class);

    private final Path filePath;
    private final long fileSize;
    private final int bufferSize;
    private ByteBufferPool bufferPool;
    private boolean useDirectByteBuffers = true;

    public PathContentProvider(Path filePath) throws IOException
    {
        this(filePath, 4096);
    }

    public PathContentProvider(Path filePath, int bufferSize) throws IOException
    {
        this("application/octet-stream", filePath, bufferSize);
    }

    public PathContentProvider(String contentType, Path filePath) throws IOException
    {
        this(contentType, filePath, 4096);
    }

    public PathContentProvider(String contentType, Path filePath, int bufferSize) throws IOException
    {
        super(contentType);
        if (!Files.isRegularFile(filePath))
            throw new NoSuchFileException(filePath.toString());
        if (!Files.isReadable(filePath))
            throw new AccessDeniedException(filePath.toString());
        this.filePath = filePath;
        this.fileSize = Files.size(filePath);
        this.bufferSize = bufferSize;
    }

    @Override
    public long getLength()
    {
        return fileSize;
    }

    @Override
    public boolean isReproducible()
    {
        return true;
    }

    public ByteBufferPool getByteBufferPool()
    {
        return bufferPool;
    }

    public void setByteBufferPool(ByteBufferPool byteBufferPool)
    {
        this.bufferPool = byteBufferPool;
    }

    public boolean isUseDirectByteBuffers()
    {
        return useDirectByteBuffers;
    }

    public void setUseDirectByteBuffers(boolean useDirectByteBuffers)
    {
        this.useDirectByteBuffers = useDirectByteBuffers;
    }

    @Override
    public Iterator<ByteBuffer> iterator()
    {
        return new PathIterator();
    }

    private class PathIterator implements Iterator<ByteBuffer>, Closeable
    {
        private ByteBuffer buffer;
        private SeekableByteChannel channel;
        private long position;

        @Override
        public boolean hasNext()
        {
            return position < getLength();
        }

        @Override
        public ByteBuffer next()
        {
            try
            {
                if (channel == null)
                {
                    buffer = bufferPool == null
                        ? BufferUtil.allocate(bufferSize, isUseDirectByteBuffers())
                        : bufferPool.acquire(bufferSize, isUseDirectByteBuffers());
                    channel = Files.newByteChannel(filePath, StandardOpenOption.READ);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Opened file {}", filePath);
                }

                buffer.clear();
                int read = channel.read(buffer);
                if (read < 0)
                    throw new NoSuchElementException();

                if (LOG.isDebugEnabled())
                    LOG.debug("Read {} bytes from {}", read, filePath);

                position += read;

                buffer.flip();
                return buffer;
            }
            catch (NoSuchElementException x)
            {
                close();
                throw x;
            }
            catch (Throwable x)
            {
                close();
                throw (NoSuchElementException)new NoSuchElementException().initCause(x);
            }
        }

        @Override
        public void close()
        {
            try
            {
                if (bufferPool != null && buffer != null)
                    bufferPool.release(buffer);
                if (channel != null)
                    channel.close();
            }
            catch (Throwable x)
            {
                LOG.trace("IGNORED", x);
            }
        }
    }
}
