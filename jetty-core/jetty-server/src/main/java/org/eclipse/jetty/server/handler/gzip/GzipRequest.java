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
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipRequest extends Request.Wrapper
{
    // TODO: use InflaterPool from somewhere.
    private static final InflaterPool __inflaterPool = new InflaterPool(-1, true);

    private final boolean _inflateInput;
    private final Decoder _decoder;
    private final GzipTransformer _gzipTransformer;
    private final int _inflateBufferSize;
    private final GzipHandler _gzipHandler;
    private final HttpFields _fields;

    public GzipRequest(Request request, GzipHandler gzipHandler, boolean inflateInput, HttpFields fields)
    {
        super(request);
        _gzipHandler = gzipHandler;
        _inflateInput = inflateInput;
        _inflateBufferSize = gzipHandler.getInflateBufferSize();
        _fields = fields;

        if (inflateInput)
        {
            Components components = request.getComponents();
            _decoder = new Decoder(__inflaterPool, components.getByteBufferPool(), _inflateBufferSize);
            _gzipTransformer = new GzipTransformer(request);
        }
        else
        {
            _decoder = null;
            _gzipTransformer = null;
        }
    }

    @Override
    public HttpFields getHeaders()
    {
        if (_fields == null)
            return super.getHeaders();
        return _fields;
    }

    @Override
    public Content.Chunk read()
    {
        if (_inflateInput)
            return _gzipTransformer.read();
        return super.read();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (_inflateInput)
            _gzipTransformer.demand(demandCallback);
        else
            super.demand(demandCallback);
    }

    void destroy(GzipResponse response)
    {
        if (_decoder != null)
            _decoder.destroy();
    }

    private class GzipTransformer extends ContentSourceTransformer
    {
        private Content.Chunk _chunk;

        public GzipTransformer(Content.Source source)
        {
            super(source);
        }

        @Override
        protected Content.Chunk transform(Content.Chunk inputChunk)
        {
            boolean retain = _chunk == null;
            if (_chunk == null)
                _chunk = inputChunk;
            if (_chunk == null)
                return null;
            if (_chunk instanceof Content.Chunk.Error)
                return _chunk;
            if (_chunk.isLast() && !_chunk.hasRemaining())
                return Content.Chunk.EOF;

            // Retain the input chunk because its ByteBuffer will be referenced by the Inflater.
            if (retain)
                _chunk.retain();
            ByteBuffer decodedBuffer = _decoder.decode(_chunk);

            if (BufferUtil.hasContent(decodedBuffer))
            {
                // The decoded ByteBuffer is a transformed "copy" of the
                // compressed one, so it has its own reference counter.
                return Content.Chunk.from(decodedBuffer, _chunk.isLast() && !_chunk.hasRemaining(), _decoder::release);
            }
            else
            {
                // Could not decode more from this chunk, release it.
                Content.Chunk result = _chunk.isLast() ? Content.Chunk.EOF : null;
                _chunk.release();
                _chunk = null;
                return result;
            }
        }
    }

    private static class Decoder extends GZIPContentDecoder
    {
        private ByteBuffer _decoded;

        private Decoder(InflaterPool inflaterPool, ByteBufferPool bufferPool, int bufferSize)
        {
            super(inflaterPool, bufferPool, bufferSize);
        }

        public ByteBuffer decode(Content.Chunk content)
        {
            decodeChunks(content.getByteBuffer());
            ByteBuffer chunk = _decoded;
            _decoded = null;
            return chunk;
        }

        @Override
        protected boolean decodedChunk(ByteBuffer decoded)
        {
            _decoded = decoded;
            return true;
        }

        @Override
        public void decodeChunks(ByteBuffer compressed)
        {
            _decoded = null;
            super.decodeChunks(compressed);
        }
    }
}
