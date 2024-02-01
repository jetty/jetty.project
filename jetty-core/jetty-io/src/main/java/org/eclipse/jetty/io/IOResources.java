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
import java.nio.file.Path;
import java.util.function.Predicate;

import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.io.content.InputStreamContentSource;
import org.eclipse.jetty.io.content.PathContentSource;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingCallback;
import org.eclipse.jetty.util.resource.MemoryResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Common IO operations for {@link Resource} content.
 */
public class IOResources
{
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

    public static void copy(Resource resource, ByteBufferPool bufferPool, int bufferSize, boolean direct, Content.Sink sink, Callback callback)
    {
        copy(asContentSource(resource, bufferPool, bufferSize, direct), sink, callback);
    }

    public static void copy(Content.Source source, Content.Sink sink, Callback callback)
    {
        copy(source, sink, x -> false, callback);
    }

    public static void copy(Content.Source source, Content.Sink sink, Predicate<Throwable> onTransientError, Callback callback)
    {
        new ContentCopierIteratingCallback(source, sink, onTransientError, callback).iterate();
    }

    private static class ContentCopierIteratingCallback extends IteratingCallback
    {
        private final Content.Source source;
        private final Content.Sink sink;
        private final Predicate<Throwable> onTransientError;
        private final Callback callback;

        public ContentCopierIteratingCallback(Content.Source source, Content.Sink target, Predicate<Throwable> onTransientError, Callback callback)
        {
            this.source = source;
            this.sink = target;
            this.onTransientError = onTransientError;
            this.callback = callback;
        }

        @Override
        protected Action process() throws Throwable
        {
            Content.Chunk chunk = source.read();
            if (chunk == null)
            {
                source.demand(this::succeeded);
                return Action.SCHEDULED;
            }
            if (Content.Chunk.isFailure(chunk, false))
            {
                Throwable failure = chunk.getFailure();
                if (onTransientError.test(failure))
                    throw new IOException(failure);
            }
            if (Content.Chunk.isFailure(chunk, true))
                throw new IOException(chunk.getFailure());

            if (chunk.hasRemaining())
            {
                ByteBuffer byteBuffer = chunk.getByteBuffer();
                sink.write(chunk.isLast(), byteBuffer, Callback.from(chunk::release, this));
                return Action.SCHEDULED;
            }

            chunk.release();
            return chunk.isLast() ? Action.SUCCEEDED : Action.SCHEDULED;
        }

        @Override
        protected void onCompleteSuccess()
        {
            callback.succeeded();
        }

        @Override
        protected void onCompleteFailure(Throwable x)
        {
            source.fail(x);
            callback.failed(x);
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
