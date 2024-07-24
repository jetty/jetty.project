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
     * @param bufferPool the {@link ByteBufferPool.Sized} to get buffers from. null means allocate new buffers as needed.
     * @return a {@link RetainableByteBuffer} containing the resource's contents.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static RetainableByteBuffer toRetainableByteBuffer(Resource resource, ByteBufferPool.Sized bufferPool) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

        // Optimize for MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
            return RetainableByteBuffer.wrap(ByteBuffer.wrap(memoryResource.getBytes()));

        long longLength = resource.length();

        bufferPool = bufferPool == null ? ByteBufferPool.SIZED_NON_POOLING : bufferPool;

        // Optimize for PathResource.
        Path path = resource.getPath();
        if (path != null && longLength < Integer.MAX_VALUE)
        {
            // TODO convert to a Dynamic once HttpContent uses writeTo semantics
            RetainableByteBuffer retainableByteBuffer = bufferPool.acquire((int)longLength);
            try (SeekableByteChannel seekableByteChannel = Files.newByteChannel(path))
            {
                long totalRead = 0L;
                ByteBuffer byteBuffer = retainableByteBuffer.getByteBuffer();
                int pos = BufferUtil.flipToFill(byteBuffer);
                while (totalRead < longLength)
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
        RetainableByteBuffer buffer = null;
        try (InputStream inputStream = resource.newInputStream())
        {
            if (inputStream == null)
                throw new IllegalArgumentException("Resource does not support InputStream: " + resource);

            RetainableByteBuffer.DynamicCapacity retainableByteBuffer = new RetainableByteBuffer.DynamicCapacity(bufferPool, bufferPool.isDirect(), longLength);
            while (true)
            {
                if (buffer == null)
                    buffer = bufferPool.acquire(false);
                int read = inputStream.read(buffer.getByteBuffer().array());
                if (read == -1)
                    break;
                buffer.getByteBuffer().limit(read);
                retainableByteBuffer.append(buffer);
                if (buffer.isRetained())
                {
                    buffer.release();
                    buffer = null;
                }
            }
            return retainableByteBuffer;
        }
        catch (IOException e)
        {
            throw new RuntimeIOException(e);
        }
        finally
        {
            if (buffer != null)
                buffer.release();
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
     * @param bufferPool the {@link ByteBufferPool.Sized} to get buffers from. null means allocate new buffers as needed.
     * @param offset the offset byte from which to read from.
     * @param length the length of the content to read, -1 for the full length.
     * @return the {@link Content.Source}.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static Content.Source asContentSource(Resource resource, ByteBufferPool.Sized bufferPool, long offset, long length) throws IllegalArgumentException
    {
        if (resource.isDirectory() || !resource.exists())
            throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

        // Try using the resource's path if possible, as the nio API is async and helps to avoid buffer copies.
        Path path = resource.getPath();
        if (path != null)
            return Content.Source.from(bufferPool, path, offset, length);

        // Try an optimization for MemoryResource.
        if (resource instanceof MemoryResource memoryResource)
            return Content.Source.from(BufferUtil.slice(ByteBuffer.wrap(memoryResource.getBytes()), (int)offset, (int)length));

        // Fallback to InputStream.
        try
        {
            InputStream inputStream = resource.newInputStream();
            if (inputStream == null)
                throw new IllegalArgumentException("Resource does not support InputStream: " + resource);
            return Content.Source.from(bufferPool, inputStream, offset, length);
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
     * <p>Performs an asynchronous copy of a subset of the contents of a resource to a sink, using the given buffer pool and buffer characteristics.</p>
     * <p>The resource must not be a directory, must exist and there must be a way to access its contents.</p>
     * <p>Multiple optimized methods are used to access the resource's contents but if they all fail,
     * {@link #asContentSource(Resource, ByteBufferPool.Sized, long, long)} is used as a fallback to perform the
     * {@link Content#copy(Content.Source, Content.Sink, Callback) copy}.</p>
     *
     * @param resource the resource to copy from.
     * @param sink the sink to copy to.
     * @param bufferPool the {@link ByteBufferPool} to get buffers from. null means allocate new buffers as needed.
     * @param offset the offset byte of the resource to start from.
     * @param length the length of the resource's contents to copy, -1 for the full length.
     * @param callback the callback to notify when the copy is done.
     * @throws IllegalArgumentException if the resource is a directory or does not exist or there is no way to access its contents.
     */
    public static void copy(Resource resource, Content.Sink sink, ByteBufferPool.Sized bufferPool, long offset, long length, Callback callback) throws IllegalArgumentException
    {
        try
        {
            if (resource.isDirectory() || !resource.exists())
                throw new IllegalArgumentException("Resource must exist and cannot be a directory: " + resource);

            // Save a Content.Source allocation for resources with a Path.
            Path path = resource.getPath();
            if (path != null)
            {
                new PathToSinkCopier(path, sink, bufferPool, offset, length, callback).iterate();
                return;
            }

            // Directly write the byte array if the resource is a MemoryResource.
            if (resource instanceof MemoryResource memoryResource)
            {
                ByteBuffer byteBuffer = BufferUtil.slice(ByteBuffer.wrap(memoryResource.getBytes()), Math.toIntExact(offset), Math.toIntExact(length));
                sink.write(true, byteBuffer, callback);
                return;
            }

            // Fallback to Content.Source.
            Content.Source source = asContentSource(resource, bufferPool, offset, length);
            Content.copy(source, sink, callback);
        }
        catch (Throwable x)
        {
            callback.failed(x);
        }
    }

    private static class PathToSinkCopier extends IteratingNestedCallback
    {
        private final SeekableByteChannel channel;
        private final Content.Sink sink;
        private final ByteBufferPool.Sized pool;
        private long remainingLength;
        private RetainableByteBuffer retainableByteBuffer;
        private boolean terminated;

        public PathToSinkCopier(Path path, Content.Sink sink, ByteBufferPool.Sized pool, long offset, long length, Callback callback) throws IOException
        {
            super(callback);
            this.sink = sink;
            this.pool = pool == null ? ByteBufferPool.SIZED_NON_POOLING : pool;
            this.remainingLength = length;
            this.channel = Files.newByteChannel(path);
            skipToOffset(channel, offset, length, this.pool);
        }

        private static void skipToOffset(SeekableByteChannel channel, long offset, long length, ByteBufferPool.Sized pool)
        {
            if (offset > 0L && length != 0L)
            {
                RetainableByteBuffer.Mutable byteBuffer = pool.acquire(1);
                try
                {
                    channel.position(offset - 1);
                    if (channel.read(byteBuffer.getByteBuffer().limit(1)) == -1)
                        throw new IllegalArgumentException("Offset out of range");
                }
                catch (IOException e)
                {
                    throw new RuntimeIOException(e);
                }
                finally
                {
                    byteBuffer.release();
                }
            }
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
                retainableByteBuffer = pool.acquire();

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
}
