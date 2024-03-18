//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.io.content.PathContentSource;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.resource.MemoryResource;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common IO operations for {@link Resource} content.
 */
public class IOResources
{
    /**
     * Gets a {@link Content.Source} with the contents of a resource, if possible.
     * Non-existent and directory resources have no content, so calling this method on such resource
     * throws {@link IllegalArgumentException}.
     *
     * @param resource the resource from which to get a {@link Content.Source}.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @return the {@link Content.Source}.
     */
    public static Content.Source asContentSource(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct)
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

        // Try to find an optimized content source.
        Path path = resource.getPath();
        if (path != null)
        {
            PathContentSource pathContentSource = new PathContentSource(path, bufferPool);
            if (bufferSize > 0)
            {
                pathContentSource.setBufferSize(bufferSize);
                pathContentSource.setUseDirectByteBuffers(direct);
            }
            return pathContentSource;
        }
        if (resource instanceof MemoryResource memoryResource)
        {
            byte[] bytes = memoryResource.getBytes();
            return new ByteBufferContentSource(ByteBuffer.wrap(bytes));
        }

        // Fallback to wrapping InputStream.
        try
        {
            return new InputStreamContentSource(resource.newInputStream());
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Gets a {@link Content.Source} with a range of the contents of a resource, if possible.
     * Non-existent and directory resources have no content, so calling this method on such resource
     * throws {@link IllegalArgumentException}.
     *
     * @param resource the resource from which to get a {@link Content.Source}.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @param first the first byte from which to read from.
     * @param length the length of the content to read.
     * @return the {@link Content.Source}.
     */
    public static Content.Source asContentSource(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct, long first, long length)
    {
        // Try using the resource's path if possible, as the nio API is async and helps to avoid buffer copies.
        Path path = resource.getPath();
        if (path != null)
        {
            RangedPathContentSource contentSource = new RangedPathContentSource(path, bufferPool, first, length);
            if (bufferSize > 0)
            {
                contentSource.setBufferSize(bufferSize);
                contentSource.setUseDirectByteBuffers(direct);
            }
            return contentSource;
        }

        // TODO MemoryResource could be optimized too

        // Fallback to InputStream.
        try
        {
            RangedInputStreamContentSource contentSource = new RangedInputStreamContentSource(resource.newInputStream(), bufferPool, first, length);
            if (bufferSize > 0)
            {
                contentSource.setBufferSize(bufferSize);
                contentSource.setUseDirectByteBuffers(direct);
            }
            return contentSource;
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Gets an {@link InputStream} with the contents of a resource, if possible.
     * Non-existent and directory resources do not have an associated stream, so calling this method on such resource
     * throws {@link IllegalArgumentException}.
     *
     * @param resource the resource from which to get an {@link InputStream}.
     * @return the {@link InputStream}.
     */
    public static InputStream asInputStream(Resource resource)
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);
        try
        {
            return resource.newInputStream();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * Performs an asynchronous copy of the contents of a resource to a sink, using the given buffer pool and buffer characteristics.
     *
     * @param resource the resource to copy from.
     * @param sink the sink to copy to.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @param callback the callback to notify when the copy is done.
     */
    public static void copy(Resource resource, Content.Sink sink, ByteBufferPool bufferPool, int bufferSize, boolean direct, Callback callback)
    {
        // Save a Content.Source allocation for resources with a Path.
        Path path = resource.getPath();
        if (path != null)
        {
            try
            {
                new PathToSinkCopier(path, sink, bufferPool, bufferSize, direct, callback).iterate();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
            return;
        }

        // Directly write the byte array if the resource is a MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
        {
            byte[] bytes = memoryResource.getBytes();
            sink.write(true, ByteBuffer.wrap(bytes), callback);
            return;
        }

        // Fallback to Content.Source.
        Content.Source source = asContentSource(resource, bufferPool, bufferSize, direct);
        Content.copy(source, sink, callback);
    }

    public static void copy(Resource resource, Content.Sink sink, ByteBufferPool bufferPool, int bufferSize, boolean direct, long first, long length, Callback callback)
    {
        // Save a Content.Source allocation for resources with a Path.
        Path path = resource.getPath();
        if (path != null)
        {
            try
            {
                new PathToSinkCopier(path, sink, bufferPool, bufferSize, direct, first, length, callback).iterate();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
            return;
        }

        // Directly write the byte array if the resource is a MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
        {
            byte[] bytes = memoryResource.getBytes();
            ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
            if (first >= 0)
                byteBuffer.position((int)first);
            if (length >= 0)
                byteBuffer.limit((int)(byteBuffer.position() + length));
            sink.write(true, byteBuffer, callback);
            return;
        }

        // Fallback to Content.Source.
        Content.Source source = asContentSource(resource, bufferPool, bufferSize, direct, first, length);
        Content.copy(source, sink, callback);
    }

    private static class PathToSinkCopier extends IteratingNestedCallback
    {
        private static final Logger LOG = LoggerFactory.getLogger(PathToSinkCopier.class);

        private final SeekableByteChannel channel;
        private final Content.Sink sink;
        private final ByteBufferPool pool;
        private final int bufferSize;
        private final boolean direct;
        private long remainingLength;
        private RetainableByteBuffer retainableByteBuffer;
        private boolean terminated;

        public PathToSinkCopier(Path path, Content.Sink sink, ByteBufferPool pool, int bufferSize, boolean direct, Callback callback) throws IOException
        {
            this(path, sink, pool, bufferSize, direct, -1L, -1L, callback);
        }

        public PathToSinkCopier(Path path, Content.Sink sink, ByteBufferPool pool, int bufferSize, boolean direct, long first, long length, Callback callback) throws IOException
        {
            super(callback);
            this.channel = Files.newByteChannel(path);
            channel.position(first);
            this.sink = sink;
            this.pool = pool == null ? new ByteBufferPool.NonPooling() : pool;
            this.bufferSize = bufferSize <= 0 ? 4096 : bufferSize;
            this.direct = direct;
            this.remainingLength = length;
        }

        @Override
        public InvocationType getInvocationType()
        {
            return InvocationType.NON_BLOCKING;
        }

        @Override
        protected Action process() throws Throwable
        {
            if (terminated)
                return Action.SUCCEEDED;

            if (retainableByteBuffer == null)
                retainableByteBuffer = pool.acquire(bufferSize, direct);

            ByteBuffer byteBuffer = retainableByteBuffer.getByteBuffer();
            BufferUtil.clearToFill(byteBuffer);
            if (remainingLength >= 0 && remainingLength < Integer.MAX_VALUE)
                byteBuffer.limit((int)Math.min(byteBuffer.capacity(), remainingLength));
            int read = channel.read(byteBuffer);
            BufferUtil.flipToFlush(byteBuffer, 0);
            remainingLength -= byteBuffer.remaining();
            terminated = read == -1 || remainingLength == 0;
            sink.write(terminated, byteBuffer, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            if (retainableByteBuffer != null)
                retainableByteBuffer.release();
            try
            {
                channel.close();
            }
            catch (IOException e)
            {
                if (LOG.isTraceEnabled())
                    LOG.trace("", e);
            }
            super.onCompleteSuccess();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (retainableByteBuffer != null)
                retainableByteBuffer.release();
            try
            {
                channel.close();
            }
            catch (IOException e)
            {
                ExceptionUtil.addSuppressedIfNotAssociated(x, e);
            }
            super.onCompleteFailure(x);
        }
    }

    /**
     * <p>A specialized {@link PathContentSource}
     * whose content is sliced by a byte range.</p>
     */
    private static class RangedPathContentSource extends PathContentSource
    {
        private final long first;
        private final long length;
        private long toRead;

        public RangedPathContentSource(Path path, ByteBufferPool bufferPool, long first, long length)
        {
            super(path, bufferPool);
            // TODO perform sanity checks on first and length?
            this.first = first;
            this.length = length;
        }

        @Override
        protected SeekableByteChannel open() throws IOException
        {
            SeekableByteChannel channel = super.open();
            channel.position(first);
            toRead = length;
            return channel;
        }

        @Override
        protected int read(SeekableByteChannel channel, ByteBuffer byteBuffer) throws IOException
        {
            int read = super.read(channel, byteBuffer);
            if (read <= 0)
                return read;

            read = (int)Math.min(read, toRead);
            toRead -= read;
            byteBuffer.position(read);
            return read;
        }

        @Override
        protected boolean isReadComplete(long read)
        {
            return read == length;
        }
    }

    /**
     * <p>A specialized {@link InputStreamContentSource}
     * whose content is sliced by a byte range.</p>
     */
    private static class RangedInputStreamContentSource extends InputStreamContentSource
    {
        private long toRead;

        public RangedInputStreamContentSource(InputStream inputStream, ByteBufferPool bufferPool, long first, long length) throws IOException
        {
            super(inputStream, bufferPool);
            inputStream.skipNBytes(first);
            // TODO perform sanity checks on length?
            this.toRead = length;
        }

        @Override
        protected int fillBufferFromInputStream(InputStream inputStream, byte[] buffer) throws IOException
        {
            if (toRead == 0)
                return -1;
            int toReadInt = (int)Math.min(Integer.MAX_VALUE, toRead);
            int len = Math.min(toReadInt, buffer.length);
            int read = inputStream.read(buffer, 0, len);
            toRead -= read;
            return read;
        }
    }
}
