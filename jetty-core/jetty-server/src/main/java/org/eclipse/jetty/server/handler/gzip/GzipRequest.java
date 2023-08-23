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

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;
import java.util.ListIterator;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipRequest extends Request.Wrapper
{
    private static final HttpField X_CE_GZIP = new PreEncodedHttpField("X-Content-Encoding", "gzip");

    // TODO: use InflaterPool from somewhere.
    private static final InflaterPool __inflaterPool = new InflaterPool(-1, true);

    private final HttpFields _fields;
    private Decoder _decoder;
    private GzipTransformer _gzipTransformer;

    public GzipRequest(Request request, int inflateBufferSize)
    {
        super(request);
        _fields = updateRequestFields(request, inflateBufferSize > 0);

        if (inflateBufferSize > 0)
        {
            Components components = getComponents();
            _decoder = new Decoder(__inflaterPool, components.getByteBufferPool(), inflateBufferSize);
            _gzipTransformer = new GzipTransformer(getWrapped());
        }
    }

    private HttpFields updateRequestFields(Request request, boolean inflatable)
    {
        HttpFields fields = request.getHeaders();
        HttpFields.Mutable newFields = HttpFields.build(fields);
        boolean contentEncodingSeen = false;

        // iterate in reverse to see last content encoding first
        for (ListIterator<HttpField> i = newFields.listIterator(newFields.size()); i.hasPrevious();)
        {
            HttpField field = i.previous();

            HttpHeader header = field.getHeader();
            if (header == null)
                continue;

            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    if (inflatable && !contentEncodingSeen)
                    {
                        contentEncodingSeen = true;

                        if (field.getValue().equalsIgnoreCase("gzip"))
                        {
                            i.set(X_CE_GZIP);
                        }
                        else if (field.containsLast("gzip"))
                        {
                            String v = field.getValue();
                            v = v.substring(0, v.lastIndexOf(','));
                            i.set(new HttpField(HttpHeader.CONTENT_ENCODING, v));
                            i.add(X_CE_GZIP);
                        }
                    }
                }
                case IF_MATCH, IF_NONE_MATCH ->
                {
                    String etags = field.getValue();
                    String etagsNoSuffix = CompressedContentFormat.GZIP.stripSuffixes(etags);
                    if (!etagsNoSuffix.equals(etags))
                    {
                        i.set(new HttpField(field.getHeader(), etagsNoSuffix));
                        request.setAttribute(GzipHandler.GZIP_HANDLER_ETAGS, etags);
                    }
                }
                case CONTENT_LENGTH ->
                {
                    if (inflatable)
                        i.set(new HttpField("X-Content-Length", field.getValue()));
                }
            }
        }
        return newFields.asImmutable();
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
        if (_gzipTransformer != null)
            return _gzipTransformer.read();
        return super.read();
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        if (_gzipTransformer != null)
            _gzipTransformer.demand(demandCallback);
        else
            super.demand(demandCallback);
    }

    void destroy()
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
            if (Content.Chunk.isFailure(_chunk))
                return _chunk;
            if (_chunk.isLast() && !_chunk.hasRemaining())
                return Content.Chunk.EOF;

            // Retain the input chunk because its ByteBuffer will be referenced by the Inflater.
            if (retain)
                _chunk.retain();
            RetainableByteBuffer decodedBuffer = _decoder.decode(_chunk);

            if (decodedBuffer != null && decodedBuffer.hasRemaining())
            {
                // The decoded ByteBuffer is a transformed "copy" of the
                // compressed one, so it has its own reference counter.
                return Content.Chunk.from(decodedBuffer.getByteBuffer(), _chunk.isLast() && !_chunk.hasRemaining(), decodedBuffer::release);
            }
            else
            {
                if (decodedBuffer != null)
                    decodedBuffer.release();
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
        private RetainableByteBuffer _decoded;

        private Decoder(InflaterPool inflaterPool, ByteBufferPool bufferPool, int bufferSize)
        {
            super(inflaterPool, bufferPool, bufferSize);
        }

        public RetainableByteBuffer decode(Content.Chunk chunk)
        {
            decodeChunks(chunk.getByteBuffer());
            RetainableByteBuffer decoded = _decoded;
            _decoded = null;
            return decoded;
        }

        @Override
        protected boolean decodedChunk(RetainableByteBuffer decoded)
        {
            // Retain the chunk because it is stored for later use.
            decoded.retain();
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
