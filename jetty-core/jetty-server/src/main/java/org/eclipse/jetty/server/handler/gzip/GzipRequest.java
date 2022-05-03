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

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.server.ContentProcessor;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipRequest extends Request.WrapperProcessor
{
    // TODO: use InflaterPool from somewhere.
    private static final InflaterPool __inflaterPool = new InflaterPool(-1, true);

    private final boolean _inflateInput;
    private Decoder _decoder;
    private GzipContentProcessor gzipContentProcessor;
    private final int _inflateBufferSize;
    private final GzipHandler _gzipHandler;
    private final HttpFields _fields;

    public GzipRequest(Request wrapped, GzipHandler gzipHandler, boolean inflateInput, HttpFields fields)
    {
        super(wrapped);
        _gzipHandler = gzipHandler;
        _inflateInput = inflateInput;
        _inflateBufferSize = gzipHandler.getInflateBufferSize();
        _fields = fields;
    }

    @Override
    public HttpFields getHeaders()
    {
        if (_fields == null)
            return super.getHeaders();
        return _fields;
    }

    @Override
    public void process(Request request, Response response, Callback callback) throws Exception
    {
        if (_inflateInput)
        {
            Components components = request.getComponents();
            _decoder = new Decoder(__inflaterPool, components.getByteBufferPool(), _inflateBufferSize);
            gzipContentProcessor = new GzipContentProcessor(request);
        }

        int outputBufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        GzipResponse gzipResponse = new GzipResponse(this, response, _gzipHandler, _gzipHandler.getVary(), outputBufferSize, _gzipHandler.isSyncFlush());
        Callback cb = Callback.from(() -> destroy(gzipResponse), callback);
        super.process(this, gzipResponse, cb);
    }

    @Override
    public void demandContent(Runnable onContentAvailable)
    {
        if (_inflateInput)
            gzipContentProcessor.demandContent(onContentAvailable);
        else
            super.demandContent(onContentAvailable);
    }

    @Override
    public Content readContent()
    {
        if (_inflateInput)
            return gzipContentProcessor.readContent();
        return super.readContent();
    }

    private void destroy(GzipResponse response)
    {
        // We need to do this to intercept the committing of the response
        // and possibly change headers in case write is never called.
        response.write(true, Callback.NOOP);

        if (_decoder != null)
        {
            _decoder.destroy();
            _decoder = null;
        }
    }

    private class GzipContentProcessor extends ContentProcessor
    {
        private Content _content;

        public GzipContentProcessor(Content.Reader reader)
        {
            super(reader);
        }

        @Override
        protected Content process(Content content)
        {
            try
            {
                if (_content == null)
                    _content = content;
                if (_content == null)
                    return null;
                if (_content.isSpecial())
                    return _content;

                ByteBuffer decodedBuffer = _decoder.decode(_content);
                if (BufferUtil.hasContent(decodedBuffer))
                    return new DecodedContent(decodedBuffer, _content.isLast());
                return null;
            }
            finally
            {
                if (_content != null && !_content.hasRemaining())
                {
                    _content.release();
                    _content = null;
                }
            }
        }
    }

    private class DecodedContent implements Content
    {
        final ByteBuffer _decodedContent;
        final boolean _isLast;

        protected DecodedContent(ByteBuffer content, boolean isLast)
        {
            _decodedContent = content;
            _isLast = isLast;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return _decodedContent;
        }

        @Override
        public boolean isLast()
        {
            return _isLast;
        }

        @Override
        public void release()
        {
            _decoder.release(_decodedContent);
        }
    }

    private static class Decoder extends GZIPContentDecoder
    {
        private ByteBuffer _chunk;

        private Decoder(InflaterPool inflaterPool, ByteBufferPool bufferPool, int bufferSize)
        {
            super(inflaterPool, bufferPool, bufferSize);
        }

        public ByteBuffer decode(Content content)
        {
            decodeChunks(content.getByteBuffer());
            ByteBuffer chunk = _chunk;
            _chunk = null;
            return chunk;
        }

        @Override
        protected boolean decodedChunk(final ByteBuffer chunk)
        {
            _chunk = chunk;
            return true;
        }

        @Override
        public void decodeChunks(ByteBuffer compressed)
        {
            _chunk = null;
            super.decodeChunks(compressed);
        }
    }
}
