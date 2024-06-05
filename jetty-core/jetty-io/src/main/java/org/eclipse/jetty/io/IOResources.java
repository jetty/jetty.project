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
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.eclipse.jetty.util.resource.MemoryResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Common IO operations for {@link Resource} content.
 */
public class IOResources
{
    /**
     * <p>Reads the contents of a Resource into a RetainableByteBuffer.</p>
     * <p>The resource must not be a directory, must exists and there must be
     * a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link Resource#newInputStream()} is used as a fallback.</p>
     *
     * @param resource the resource to be read.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param direct the directness of the buffers.
     * @return a {@link RetainableByteBuffer} containing the resource's contents.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static RetainableByteBuffer toRetainableByteBuffer(Resource resource, ByteBufferPool bufferPool, boolean direct) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

        // Optimize for MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
            return RetainableByteBuffer.wrap(ByteBuffer.wrap(memoryResource.getBytes()));

        long longLength = resource.length();
        if (longLength > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Resource length exceeds 2 GiB: " + resource);
        int length = (int)longLength;

        bufferPool = bufferPool == null ? ByteBufferPool.NON_POOLING : bufferPool;

        // Optimize for PathResource.
        Path path = resource.getPath();
        if (path != null)
        {
            RetainableByteBuffer retainableByteBuffer = bufferPool.acquire(length, direct);
            try (SeekableByteChannel seekableByteChannel = Files.newByteChannel(path))
            {
                long totalRead = 0L;
                ByteBuffer byteBuffer = retainableByteBuffer.getByteBuffer();
                int pos = BufferUtil.flipToFill(byteBuffer);
                while (totalRead < length)
                {
                    int read = seekableByteChannel.read(byteBuffer);
                    if (read == -1)
                        break;
                    totalRead += read;
                }
                BufferUtil.flipToFlush(byteBuffer, pos);
                return retainableByteBuffer;
            }
            catch (IOException e)
            {
                retainableByteBuffer.release();
                throw new RuntimeIOException(e);
            }
        }

        // Fallback to InputStream.
        try (InputStream inputStream = resource.newInputStream())
        {
            if (inputStream == null)
                throw new IllegalArgumentException("Resource does not support InputStream: " + resource);

            ByteBufferAggregator aggregator = new ByteBufferAggregator(bufferPool, direct, length > -1 ? length : 4096, length > -1 ? length : Integer.MAX_VALUE);
            byte[] byteArray = new byte[4096];
            while (true)
            {
                int read = inputStream.read(byteArray);
                if (read == -1)
                    break;
                aggregator.aggregate(ByteBuffer.wrap(byteArray, 0, read));
            }
            return aggregator.takeRetainableByteBuffer();
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * <p>Gets a {@link Content.Source} with the contents of a resource.</p>
     * <p>The resource must not be a directory, must exists and there must be
     * a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link Resource#newInputStream()} is used as a fallback.</p>
     *
     * @param resource the resource from which to get a {@link Content.Source}.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @return the {@link Content.Source}.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static Content.Source asContentSource(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct) throws IllegalArgumentException
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
     * <p>Gets a {@link Content.Source} with a range of the contents of a resource.</p>
     * <p>The resource must not be a directory, must exists and there must be
     * a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link Resource#newInputStream()} is used as a fallback.</p>
     *
     * @param resource the resource from which to get a {@link Content.Source}.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @param first the first byte from which to read from.
     * @param length the length of the content to read.
     * @return the {@link Content.Source}.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static Content.Source asContentSource(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct, long first, long length) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

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

        // Try an optimization for MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
            return new ByteBufferContentSource(ByteBuffer.wrap(memoryResource.getBytes()));

        // Fallback to InputStream.
        try
        {
            InputStream inputStream = resource.newInputStream();
            if (inputStream == null)
                throw new IllegalArgumentException("Resource does not support InputStream: " + resource);
            RangedInputStreamContentSource contentSource = new RangedInputStreamContentSource(inputStream, bufferPool, first, length);
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
     * <p>Gets an {@link InputStream} with the contents of a resource.</p>
     * <p>The resource must not be a directory, must exist and must return non-null to {@link Resource#newInputStream()}.</p>
     *
     * @param resource the resource from which to get an {@link InputStream}.
     * @return the {@link InputStream}.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or {@link Resource#newInputStream()} returns null.
     */
    public static InputStream asInputStream(Resource resource) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);
        try
        {
            InputStream inputStream = resource.newInputStream();
            if (inputStream == null)
                throw new IllegalArgumentException("Resource does not support InputStream: " + resource);
            return inputStream;
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
    }

    /**
     * <p>Performs an asynchronous copy of the contents of a resource to a sink, using the given buffer pool and buffer characteristics.</p>
     * <p>The resource must not be a directory, must exist and there must be a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link #asContentSource(Resource, ByteBufferPool, int, boolean)} is used as a fallback to perform the
     * {@link Content#copy(Content.Source, Content.Sink, Callback) copy}.</p>
     *
     * @param resource the resource to copy from.
     * @param sink the sink to copy to.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @param callback the callback to notify when the copy is done.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static void copy(Resource resource, Content.Sink sink, ByteBufferPool bufferPool, int bufferSize, boolean direct, Callback callback) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

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

    /**
     * <p>Performs an asynchronous copy of a subset of the contents of a resource to a sink, using the given buffer pool and buffer characteristics.</p>
     * <p>The resource must not be a directory, must exist and there must be a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link #asContentSource(Resource, ByteBufferPool, int, boolean, long, long)} is used as a fallback to perform the
     * {@link Content#copy(Content.Source, Content.Sink, Callback) copy}.</p>
     *
     * @param resource the resource to copy from.
     * @param sink the sink to copy to.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param bufferSize the size of the buffer to be used for the copy. Any value &lt; 1 means use a default value.
     * @param direct the directness of the buffers, this parameter is ignored if {@code bufferSize} is &lt; 1.
     * @param first the first byte of the resource to start from.
     * @param length the length of the resource's contents to copy.
     * @param callback the callback to notify when the copy is done.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static void copy(Resource resource, Content.Sink sink, ByteBufferPool bufferPool, int bufferSize, boolean direct, long first, long length, Callback callback) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

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
            if (first > -1)
                channel.position(first);
            this.sink = sink;
            this.pool = pool == null ? ByteBufferPool.NON_POOLING : pool;
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
            boolean eof = false;
            while (byteBuffer.hasRemaining() && !eof)
            {
                int read = channel.read(byteBuffer);
                if (read == -1)
                    eof = true;
                else if (remainingLength >= 0)
                    remainingLength -= read;
            }
            BufferUtil.flipToFlush(byteBuffer, 0);
            terminated = eof || remainingLength == 0;
            sink.write(terminated, byteBuffer, this);
            return Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            if (retainableByteBuffer != null)
                retainableByteBuffer.release();
            IO.close(channel);
            super.onCompleteSuccess();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            if (retainableByteBuffer != null)
                retainableByteBuffer.release();
            IO.close(channel);
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
            if (first > -1)
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
            if (read > -1)
            {
                toRead -= read;
                byteBuffer.position(read);
            }
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
            int len = toReadInt > -1 ? Math.min(toReadInt, buffer.length) : buffer.length;
            int read = inputStream.read(buffer, 0, len);
            toRead -= read;
            return read;
        }
    }
}
