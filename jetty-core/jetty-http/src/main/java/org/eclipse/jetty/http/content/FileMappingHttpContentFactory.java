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
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.AutoLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class FileMappingHttpContentFactory implements HttpContent.Factory
{
    private static final Logger LOG = LoggerFactory.getLogger(FileMappingHttpContentFactory.class);
    private static final int DEFAULT_MIN_FILE_SIZE = 16 * 1024;

    private final HttpContent.Factory _factory;
    private final int _minFileSize;

    /**
     * Construct a {@link FileMappingHttpContentFactory} which can use file mapped buffers.
     * Uses a default value of {@value DEFAULT_MIN_FILE_SIZE} for the minimum size of an
     * {@link HttpContent} before trying to use a file mapped buffer.
     *
     * @param factory the wrapped {@link HttpContent.Factory} to use.
     */
    public FileMappingHttpContentFactory(HttpContent.Factory factory)
    {
        this(factory, DEFAULT_MIN_FILE_SIZE);
    }

    /**
     * Construct a {@link FileMappingHttpContentFactory} which can use file mapped buffers.
     *
     * @param factory the wrapped {@link HttpContent.Factory} to use.
     * @param minFileSize the minimum size of an {@link HttpContent} before trying to use a file mapped buffer.
     */
    public FileMappingHttpContentFactory(HttpContent.Factory factory, int minFileSize)
    {
        _factory = Objects.requireNonNull(factory);
        _minFileSize = minFileSize;
    }

    @Override
    public HttpContent getContent(String path) throws IOException
    {
        HttpContent content = _factory.getContent(path);
        if (content != null)
        {
            long contentLength = content.getContentLengthValue();
            if (contentLength > _minFileSize && contentLength < Integer.MAX_VALUE)
                return new FileMappedHttpContent(content);
        }
        return content;
    }

    private static class FileMappedHttpContent extends HttpContent.Wrapper
    {
        private final AutoLock _lock = new AutoLock();
        private final HttpContent _content;
        private volatile ByteBuffer _buffer;
        private volatile Throwable _failure;

        public FileMappedHttpContent(HttpContent content)
        {
            super(content);
            this._content = content;
        }

        @Override
        public void writeTo(HttpContent.Sink sink, Callback callback)
        {
            ByteBuffer buffer = _buffer;
            if (buffer != null)
            {
                sink.write(false, buffer.asReadOnlyBuffer(), callback);
                return;
            }

            try (AutoLock lock = _lock.lock())
            {
                if (_buffer == null)
                    lockedInitMappedByteBuffer();
                if (_buffer == null)
                    callback.failed(_failure);
                else
                    sink.write(false, _buffer.asReadOnlyBuffer(), callback);
            }
        }

        @Override
        public long getBytesOccupied()
        {
            ByteBuffer buffer = _buffer;
            if (buffer != null)
                return buffer.remaining();

            try (AutoLock lock = _lock.lock())
            {
                if (_buffer == null)
                    lockedInitMappedByteBuffer();
                return (_buffer == null) ? super.getBytesOccupied() : _buffer.remaining();
            }
        }

        private void lockedInitMappedByteBuffer()
        {
            assert _lock.isHeldByCurrentThread();

            try
            {
                _buffer = BufferUtil.toMappedBuffer(_content.getResource().getPath());
            }
            catch (Throwable t)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Error getting Mapped Buffer", t);
                _failure = t;
            }
        }
    }
}
