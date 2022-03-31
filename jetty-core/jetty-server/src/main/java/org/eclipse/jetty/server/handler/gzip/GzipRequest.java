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
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipRequest extends Request.WrapperProcessor
{
    // TODO: use InflaterPool from somewhere.
    private static final InflaterPool __inflaterPool = new InflaterPool(-1, true);

    private final boolean _inflateInput;
    private Decoder _decoder;
    private ByteBuffer _chunk;
    private Content _content;
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
        }

        int outputBufferSize = request.getConnectionMetaData().getHttpConfiguration().getOutputBufferSize();
        GzipResponse gzipResponse = new GzipResponse(this, response, _gzipHandler, _gzipHandler.getVary(), outputBufferSize, _gzipHandler.isSyncFlush());
        Callback cb = Callback.from(this::destroy, callback);
        super.process(this, gzipResponse, cb);
    }

    @Override
    public Content readContent()
    {
        // TODO: use ContentProcessor (see #7403).
        if (!_inflateInput)
            return super.readContent();

        if (_content == null)
            _content = super.readContent();
        if (_content == null)
            return null;
        if (_content.isSpecial())
            return _content;

        DecodedContent content = new DecodedContent();
        if (_content.isEmpty())
        {
            _content.release();
            _content = null;
        }
        return content;
    }

    public void destroy()
    {
        if (_decoder != null)
        {
            _decoder.destroy();
            _decoder = null;
        }
    }

    private class DecodedContent implements Content
    {
        final ByteBuffer decodedContent;

        protected DecodedContent()
        {
            _decoder.decodeChunks(_content.getByteBuffer());
            decodedContent = _chunk;
        }

        @Override
        public ByteBuffer getByteBuffer()
        {
            return decodedContent;
        }

        @Override
        public void release()
        {
            _decoder.release(decodedContent);
        }
    }

    private class Decoder extends GZIPContentDecoder
    {
        private Decoder(InflaterPool inflaterPool, ByteBufferPool bufferPool, int bufferSize)
        {
            super(inflaterPool, bufferPool, bufferSize);
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
