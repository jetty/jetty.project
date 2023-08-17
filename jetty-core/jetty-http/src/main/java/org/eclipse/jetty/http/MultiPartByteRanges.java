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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RuntimeIOException;
import org.eclipse.jetty.io.content.ContentSourceCompletableFuture;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>A {@link CompletableFuture} that is completed when a multipart/byteranges
 * has been parsed asynchronously from a {@link Content.Source}.</p>
 * <p>Once the parsing of the multipart/byteranges content completes successfully,
 * objects of this class are completed with a {@link MultiPartByteRanges.Parts}
 * object.</p>
 * <p>Typical usage:</p>
 * <pre>{@code
 * // Some headers that include Content-Type.
 * HttpFields headers = ...;
 * String boundary = MultiPart.extractBoundary(headers.get(HttpHeader.CONTENT_TYPE));
 *
 * // Some multipart/byteranges content.
 * Content.Source content = ...;
 *
 * // Create and configure MultiPartByteRanges.
 * MultiPartByteRanges byteRanges = new MultiPartByteRanges(boundary);
 *
 * // Parse the content.
 * byteRanges.parse(content)
 *     // When complete, use the parts.
 *     .thenAccept(parts -> ...);
 * }</pre>
 *
 * @see Parts
 */
public class MultiPartByteRanges
{
    private MultiPartByteRanges()
    {
    }

    /**
     * <p>An ordered list of {@link MultiPart.Part}s that can
     * be accessed by index, or iterated over.</p>
     */
    public static class Parts implements Iterable<MultiPart.Part>
    {
        private final String boundary;
        private final List<MultiPart.Part> parts;

        private Parts(String boundary, List<MultiPart.Part> parts)
        {
            this.boundary = boundary;
            this.parts = parts;
        }

        /**
         * @return the boundary string
         */
        public String getBoundary()
        {
            return boundary;
        }

        /**
         * <p>Returns the {@link MultiPart.Part}  at the given index, a number
         * between {@code 0} included and the value returned by {@link #size()}
         * excluded.</p>
         *
         * @param index the index of the {@code MultiPart.Part} to return
         * @return the {@code MultiPart.Part} at the given index
         */

        public MultiPart.Part get(int index)
        {
            return parts.get(index);
        }

        /**
         * @return the number of parts
         * @see #get(int)
         */
        public int size()
        {
            return parts.size();
        }

        @Override
        public Iterator<MultiPart.Part> iterator()
        {
            return parts.iterator();
        }
    }

    /**
     * <p>The multipart/byteranges specific content source.</p>
     *
     * @see MultiPart.AbstractContentSource
     */
    public static class ContentSource extends MultiPart.AbstractContentSource
    {
        public ContentSource(String boundary)
        {
            super(boundary);
        }

        @Override
        public boolean addPart(MultiPart.Part part)
        {
            if (part instanceof Part)
                return super.addPart(part);
            return false;
        }
    }

    /**
     * <p>A specialized {@link org.eclipse.jetty.io.content.InputStreamContentSource}
     * whose content is sliced by a byte range.</p>
     */
    public static class InputStreamContentSource extends org.eclipse.jetty.io.content.InputStreamContentSource
    {
        private long toRead;

        public InputStreamContentSource(InputStream inputStream, ByteRange byteRange) throws IOException
        {
            super(inputStream);
            inputStream.skipNBytes(byteRange.first());
            this.toRead = byteRange.getLength();
        }

        @Override
        protected int fillBufferFromInputStream(InputStream inputStream, ByteBuffer buffer) throws IOException
        {
            if (toRead == 0)
                return -1;
            int toReadInt = (int)Math.min(Integer.MAX_VALUE, toRead);
            int len = Math.min(toReadInt, buffer.capacity());
            int read = inputStream.read(buffer.array(), buffer.arrayOffset(), len);
            toRead -= read;
            return read;
        }
    }

    /**
     * <p>A specialized {@link org.eclipse.jetty.io.content.PathContentSource}
     * whose content is sliced by a byte range.</p>
     */
    public static class PathContentSource extends org.eclipse.jetty.io.content.PathContentSource
    {
        private final ByteRange byteRange;
        private long toRead;

        public PathContentSource(Path path, ByteRange byteRange)
        {
            super(path);
            this.byteRange = byteRange;
        }

        @Override
        protected SeekableByteChannel open() throws IOException
        {
            SeekableByteChannel channel = super.open();
            channel.position(byteRange.first());
            toRead = byteRange.getLength();
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
            return read == byteRange.getLength();
        }
    }

    /**
     * <p>A {@link MultiPart.Part} whose content is a byte range of a file.</p>
     */
    public static class Part extends MultiPart.Part
    {
        private final Resource resource;
        private final ByteRange byteRange;

        public Part(String contentType, Resource resource, ByteRange byteRange, long contentLength)
        {
            this(HttpFields.build().put(HttpHeader.CONTENT_TYPE, contentType)
                .put(HttpHeader.CONTENT_RANGE, byteRange.toHeaderValue(contentLength)), resource, byteRange);
        }

        public Part(HttpFields headers, Resource resource, ByteRange byteRange)
        {
            super(null, null, headers);
            this.resource = resource;
            this.byteRange = byteRange;
        }

        @Override
        public Content.Source newContentSource()
        {
            Path path = resource.getPath();
            if (path != null)
                return new PathContentSource(path, byteRange);

            try
            {
                return new InputStreamContentSource(resource.newInputStream(), byteRange);
            }
            catch (IOException e)
            {
                throw new RuntimeIOException(e);
            }
        }
    }

    public static class Parser
    {
        private final PartsListener listener = new PartsListener();
        private final MultiPart.Parser parser;
        private Parts parts;

        public Parser(String boundary)
        {
            parser = new MultiPart.Parser(boundary, listener);
        }

        public CompletableFuture<MultiPartByteRanges.Parts> parse(Content.Source content)
        {
            ContentSourceCompletableFuture<MultiPartByteRanges.Parts> futureParts = new ContentSourceCompletableFuture<>(content)
            {
                @Override
                protected MultiPartByteRanges.Parts parse(Content.Chunk chunk) throws Throwable
                {
                    if (listener.isFailed())
                        throw listener.failure;
                    parser.parse(chunk);
                    if (listener.isFailed())
                        throw listener.failure;
                    return parts;
                }

                @Override
                public boolean completeExceptionally(Throwable failure)
                {
                    boolean failed = super.completeExceptionally(failure);
                    if (failed)
                        listener.fail(failure);
                    return failed;
                }
            };
            futureParts.parse();
            return futureParts;
        }

        /**
         * @return the boundary string
         */
        public String getBoundary()
        {
            return parser.getBoundary();
        }

        private class PartsListener extends MultiPart.AbstractPartsListener
        {
            private final AutoLock lock = new AutoLock();
            private final List<Content.Chunk> partChunks = new ArrayList<>();
            private final List<MultiPart.Part> parts = new ArrayList<>();
            private Throwable failure;

            private boolean isFailed()
            {
                try (AutoLock ignored = lock.lock())
                {
                    return failure != null;
                }
            }

            @Override
            public void onPartContent(Content.Chunk chunk)
            {
                try (AutoLock ignored = lock.lock())
                {
                    // Retain the chunk because it is stored for later use.
                    chunk.retain();
                    partChunks.add(chunk);
                }
            }

            @Override
            public void onPart(String name, String fileName, HttpFields headers)
            {
                try (AutoLock ignored = lock.lock())
                {
                    parts.add(new MultiPart.ChunksPart(name, fileName, headers, List.copyOf(partChunks)));
                    partChunks.forEach(Content.Chunk::release);
                    partChunks.clear();
                }
            }

            @Override
            public void onComplete()
            {
                super.onComplete();
                List<MultiPart.Part> copy;
                try (AutoLock ignored = lock.lock())
                {
                    copy = List.copyOf(parts);
                    Parser.this.parts = new Parts(getBoundary(), copy);
                }
            }

            @Override
            public void onFailure(Throwable failure)
            {
                fail(failure);
            }

            private void fail(Throwable cause)
            {
                List<MultiPart.Part> partsToFail;
                try (AutoLock ignored = lock.lock())
                {
                    if (failure != null)
                        return;
                    failure = cause;
                    partsToFail = List.copyOf(parts);
                    parts.clear();
                    partChunks.forEach(Content.Chunk::release);
                    partChunks.clear();
                }
                partsToFail.forEach(p -> p.fail(cause));
            }
        }
    }
}
