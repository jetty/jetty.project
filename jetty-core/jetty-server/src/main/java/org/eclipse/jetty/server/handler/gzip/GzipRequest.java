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
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Components;
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
    private GzipTransformer gzipContentProcessor;
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
            gzipContentProcessor = new GzipTransformer(request);
        }

        int outputBufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        GzipResponse gzipResponse = new GzipResponse(this, response, _gzipHandler, _gzipHandler.getVary(), outputBufferSize, _gzipHandler.isSyncFlush());
        Callback cb = Callback.from(() -> destroy(gzipResponse), callback);
        super.process(this, gzipResponse, cb);
    }

    @Override
    public Content.Chunk read()
    {
        if (_inflateInput)
            return gzipContentProcessor.read();
        return super.read();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (_inflateInput)
            gzipContentProcessor.demand(demandCallback);
        else
            super.demand(demandCallback);
    }

    private void destroy(GzipResponse response)
    {
        // We need to do this to intercept the committing of the response
        // and possibly change headers in case write is never called.
        response.write(true, null, Callback.NOOP);

        if (_decoder != null)
        {
            _decoder.destroy();
            _decoder = null;
        }
    }

    private class GzipTransformer extends Content.Source.Transformer
    {
        private Content.Chunk _chunk;

        public GzipTransformer(Content.Source source)
        {
            super(source);
        }

        @Override
        protected Content.Chunk transform(Content.Chunk compressed)
        {
            try
            {
                if (_chunk == null)
                    _chunk = compressed;
                if (_chunk == null)
                    return null;
                if (_chunk instanceof Content.Chunk.Error)
                    return _chunk;
                if (_chunk.isLast() && !_chunk.hasRemaining())
                    return Content.Chunk.EOF;

                ByteBuffer decodedBuffer = _decoder.decode(_chunk);
                if (BufferUtil.hasContent(decodedBuffer))
                    return Content.Chunk.from(decodedBuffer, _chunk.isLast() && !_chunk.hasRemaining(), () -> _decoder.release(decodedBuffer));
                return _chunk.isLast() ? Content.Chunk.EOF : null;
            }
            finally
            {
                if (_chunk != null && !_chunk.hasRemaining())
                {
                    _chunk.release();
                    _chunk = null;
                }
            }
        }
    }

    private static class Decoder extends GZIPContentDecoder
    {
        private ByteBuffer _chunk;

        private Decoder(InflaterPool inflaterPool, ByteBufferPool bufferPool, int bufferSize)
        {
            super(inflaterPool, bufferPool, bufferSize);
        }

        public ByteBuffer decode(Content.Chunk content)
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
