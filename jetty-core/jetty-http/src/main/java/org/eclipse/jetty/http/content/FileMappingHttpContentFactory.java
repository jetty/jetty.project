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

package org.eclipse.jetty.http.content;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.IteratingNestedCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileMappingHttpContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(FileMappingHttpContentFactory.class);
    private static final int DEFAULT_MIN_FILE_SIZE = 16 * 1024;
    private static final int DEFAULT_MAX_BUFFER_SIZE = Integer.MAX_VALUE;

    private final HttpContent.Factory _factory;
    private final int _minFileSize;
    private final int _maxBufferSize;

    /**
     * Construct a {@link FileMappingHttpContentFactory} which can use file mapped buffers.
     * Uses a default value of {@value DEFAULT_MIN_FILE_SIZE} for the minimum size of an
     * {@link HttpContent} before trying to use a file mapped buffer.
     *
     * @param factory the wrapped {@link HttpContent.Factory} to use.
     */
    public FileMappingHttpContentFactory(HttpContent.Factory factory)
    {
        this(factory, DEFAULT_MIN_FILE_SIZE, DEFAULT_MAX_BUFFER_SIZE);
    }

    /**
     * Construct a {@link FileMappingHttpContentFactory} which can use file mapped buffers.
     *
     * @param factory the wrapped {@link HttpContent.Factory} to use.
     * @param minFileSize the minimum size of an {@link HttpContent} before trying to use a file mapped buffer.
     * @param maxBufferSize the maximum size of the memory mapped buffers
     */
    public FileMappingHttpContentFactory(HttpContent.Factory factory, int minFileSize, int maxBufferSize)
    {
        _factory = Objects.requireNonNull(factory);
        _minFileSize = minFileSize;
        _maxBufferSize = maxBufferSize;
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        HttpContent content = _factory.getContent(path);
        if (content != null)
        {
            try
            {
                long contentLength = content.getContentLengthValue();
                if (contentLength < _minFileSize)
                    return content;
                return contentLength <= _maxBufferSize ? new SingleBufferFileMappedHttpContent(content) : new MultiBufferFileMappedHttpContent(content, _maxBufferSize);
            }
            catch (IOException e)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error getting Mapped Buffer", e);
                // Fall through to return the content gotten from the factory.
            }
        }
        return content;
    }

    private static class SingleBufferFileMappedHttpContent extends HttpContent.Wrapper
    {
        private final ByteBuffer _buffer;
        private final HttpField _contentLength;
        private final HttpField _lastModified;
        private final Instant _lastModifiedInstant;

        private SingleBufferFileMappedHttpContent(HttpContent content) throws IOException
        {
            super(content);
            Path path = content.getResource().getPath();
            if (path == null)
                throw new IOException("Cannot memory map Content whose Resource is not backed by a Path: " + content.getResource());
            _buffer = BufferUtil.toMappedBuffer(path);
            if (_buffer == null)
                throw new IOException("Cannot memory map Content (not supported by underlying FileSystem): " + content.getResource());
            _contentLength = new HttpField(HttpHeader.CONTENT_LENGTH, Integer.toString(_buffer.remaining()));
            _lastModified = content.getLastModified();
            _lastModifiedInstant = content.getLastModifiedInstant();
        }

        @Override
        public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
        {
            try
            {
                sink.write(true, BufferUtil.slice(_buffer, (int)offset, (int)length), callback);
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public long getContentLengthValue()
        {
            return _buffer.remaining();
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedInstant;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }
    }

    private static class MultiBufferFileMappedHttpContent extends HttpContent.Wrapper
    {
        private final ByteBuffer[] _buffers;
        private final int maxBufferSize;
        private final HttpField _contentLength;
        private final long _contentLengthValue;
        private final HttpField _lastModified;
        private final Instant _lastModifiedInstant;

        private MultiBufferFileMappedHttpContent(HttpContent content, int maxBufferSize) throws IOException
        {
            super(content);
            this.maxBufferSize = maxBufferSize;
            Path path = content.getResource().getPath();
            if (path == null)
                throw new IOException("Cannot memory map Content whose Resource is not backed by a Path: " + content.getResource());

            long contentLength = content.getContentLengthValue();
            int bufferCount = Math.toIntExact(contentLength / maxBufferSize);
            if (contentLength % maxBufferSize != 0)
            {
                if (bufferCount == Integer.MAX_VALUE)
                    throw new IOException("Cannot memory map Content as that would require over Integer.MAX_VALUE buffers: " + content);
                bufferCount++;
            }
            _buffers = new ByteBuffer[bufferCount];
            long currentPos = 0L;
            long total = 0L;
            for (int i = 0; i < _buffers.length; i++)
            {
                long len = Math.min(contentLength - currentPos, maxBufferSize);
                _buffers[i] = BufferUtil.toMappedBuffer(path, currentPos, len);
                if (_buffers[i] == null)
                    throw new IOException("Cannot memory map Content (not supported by underlying FileSystem): " + content.getResource());
                currentPos += len;
                total += _buffers[i].remaining();
            }
            _contentLengthValue = total;
            _contentLength = new HttpField(HttpHeader.CONTENT_LENGTH, Long.toString(total));
            _lastModified = content.getLastModified();
            _lastModifiedInstant = content.getLastModifiedInstant();
        }

        @Override
        public void writeTo(Content.Sink sink, long offset, long length, Callback callback)
        {
            try
            {
                if (offset > getContentLengthValue())
                    throw new IllegalArgumentException("Offset outside of mapped file range");
                if (length > -1 && length + offset > getContentLengthValue())
                    throw new IllegalArgumentException("Offset / length outside of mapped file range");

                int beginIndex = Math.toIntExact(offset / maxBufferSize);
                int firstOffset = Math.toIntExact(offset % maxBufferSize);

                int endIndex = calculateEndIndex(offset, length);
                int lastLen = calculateLastLen(offset, length);
                new IteratingNestedCallback(callback)
                {
                    int index = beginIndex;
                    @Override
                    protected Action process()
                    {
                        if (index > endIndex)
                            return Action.SUCCEEDED;

                        ByteBuffer currentBuffer = _buffers[index];
                        int offset = index == beginIndex ? firstOffset : 0;
                        int len = index == endIndex ? lastLen : -1;
                        boolean last = index == endIndex;
                        index++;
                        sink.write(last, BufferUtil.slice(currentBuffer, offset, len), this);
                        return Action.SCHEDULED;
                    }
                }.iterate();
            }
            catch (Throwable x)
            {
                callback.failed(x);
            }
        }

        private int calculateLastLen(long offset, long length)
        {
            if (length == 0)
                return 0;
            int lastLen = length < 0 ? -1 : Math.toIntExact((length + offset) % maxBufferSize);
            if (Math.toIntExact((length + offset) / maxBufferSize) == _buffers.length)
                lastLen = -1;
            return lastLen;
        }

        private int calculateEndIndex(long offset, long length)
        {
            int endIndex = length < 0 ? (_buffers.length - 1) : Math.toIntExact((length + offset) / maxBufferSize);
            if (endIndex == _buffers.length)
                endIndex--;
            return endIndex;
        }

        @Override
        public HttpField getContentLength()
        {
            return _contentLength;
        }

        @Override
        public long getContentLengthValue()
        {
            return _contentLengthValue;
        }

        @Override
        public Instant getLastModifiedInstant()
        {
            return _lastModifiedInstant;
        }

        @Override
        public HttpField getLastModified()
        {
            return _lastModified;
        }
    }
}
