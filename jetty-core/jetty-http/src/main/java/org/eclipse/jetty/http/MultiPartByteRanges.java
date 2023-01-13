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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.AutoLock;

/**
 * <p>A {@link CompletableFuture} that is completed when a multipart/byteranges
 * content has been parsed asynchronously from a {@link Content.Source} via
 * {@link #parse(Content.Source)}.</p>
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
public class MultiPartByteRanges extends CompletableFuture<MultiPartByteRanges.Parts>
{
    private final PartsListener listener = new PartsListener();
    private final MultiPart.Parser parser;

    public MultiPartByteRanges(String boundary)
    {
        this.parser = new MultiPart.Parser(boundary, listener);
    }

    /**
     * @return the boundary string
     */
    public String getBoundary()
    {
        return parser.getBoundary();
    }

    @Override
    public boolean completeExceptionally(Throwable failure)
    {
        listener.fail(failure);
        return super.completeExceptionally(failure);
    }

    /**
     * <p>Parses the given multipart/byteranges content.</p>
     * <p>Returns this {@code MultiPartByteRanges} object,
     * so that it can be used in the typical "fluent" style
     * of {@link CompletableFuture}.</p>
     *
     * @param content the multipart/byteranges content to parse
     * @return this {@code MultiPartByteRanges} object
     */
    public MultiPartByteRanges parse(Content.Source content)
    {
        new Runnable()
        {
            @Override
            public void run()
            {
                while (true)
                {
                    Content.Chunk chunk = content.read();
                    if (chunk == null)
                    {
                        content.demand(this);
                        return;
                    }
                    if (chunk instanceof Content.Chunk.Error error)
                    {
                        listener.onFailure(error.getCause());
                        return;
                    }
                    parse(chunk);
                    chunk.release();
                    if (chunk.isLast() || isDone())
                        return;
                }
            }
        }.run();
        return this;
    }

    private void parse(Content.Chunk chunk)
    {
        if (listener.isFailed())
            return;
        parser.parse(chunk);
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
     * <p>A specialized {@link org.eclipse.jetty.io.content.PathContentSource}
     * whose content is sliced by a byte range.</p>
     */
    public static class PathContentSource extends org.eclipse.jetty.io.content.PathContentSource
    {
        private final ByteRange byteRange;

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
            return channel;
        }

        @Override
        protected int read(SeekableByteChannel channel, ByteBuffer byteBuffer) throws IOException
        {
            int read = super.read(channel, byteBuffer);
            if (read < 0)
                return read;

            read = (int)Math.min(read, byteRange.getLength());
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
        private final PathContentSource content;

        public Part(String contentType, Path path, ByteRange byteRange, long contentLength)
        {
            this(HttpFields.build().put(HttpHeader.CONTENT_TYPE, contentType)
                .put(HttpHeader.CONTENT_RANGE, byteRange.toHeaderValue(contentLength)), path, byteRange);
        }

        public Part(HttpFields headers, Path path, ByteRange byteRange)
        {
            super(null, null, headers);
            content = new PathContentSource(path, byteRange);
        }

        @Override
        public Content.Source getContent()
        {
            return content;
        }
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
            // Retain the chunk because it is stored for later use.
            if (chunk.canRetain())
                chunk.retain();
            partChunks.add(chunk);
        }

        @Override
        public void onPart(String name, String fileName, HttpFields headers)
        {
            parts.add(new MultiPart.ChunksPart(name, fileName, headers, List.copyOf(partChunks)));
            partChunks.clear();
        }

        @Override
        public void onComplete()
        {
            super.onComplete();
            complete(new Parts(getBoundary(), parts));
        }

        @Override
        public void onFailure(Throwable failure)
        {
            super.onFailure(failure);
            completeExceptionally(failure);
        }

        private void fail(Throwable cause)
        {
            List<MultiPart.Part> toFail;
            try (AutoLock ignored = lock.lock())
            {
                if (failure != null)
                    return;
                failure = cause;
                toFail = new ArrayList<>(parts);
                parts.clear();
            }
            toFail.forEach(part -> part.getContent().fail(cause));
        }
    }
}
