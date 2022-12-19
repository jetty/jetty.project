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
import java.util.regex.Pattern;

import org.eclipse.jetty.http.CompressedContentFormat;
import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ContentSourceTransformer;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.compression.InflaterPool;

public class GzipRequest extends Request.Wrapper
{
    private static final HttpField X_CE_GZIP = new PreEncodedHttpField("X-Content-Encoding", "gzip");
    private static final Pattern COMMA_GZIP = Pattern.compile(".*, *gzip");

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
        HttpFields.Mutable newFields = HttpFields.build(fields.size());
        for (HttpField field : fields)
        {
            HttpHeader header = field.getHeader();
            if (header == null)
            {
                newFields.add(field);
                continue;
            }

            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    if (inflatable)
                    {
                        if (field.getValue().equalsIgnoreCase("gzip"))
                        {
                            newFields.add(X_CE_GZIP);
                            continue;
                        }

                        if (COMMA_GZIP.matcher(field.getValue()).matches())
                        {
                            String v = field.getValue();
                            v = v.substring(0, v.lastIndexOf(','));
                            newFields.add(X_CE_GZIP);
                            newFields.add(new HttpField(HttpHeader.CONTENT_ENCODING, v));
                            continue;
                        }
                    }
                    newFields.add(field);
                }
                case IF_MATCH, IF_NONE_MATCH ->
                {
                    String etags = field.getValue();
                    String etagsNoSuffix = CompressedContentFormat.GZIP.stripSuffixes(etags);
                    if (!etagsNoSuffix.equals(etags))
                    {
                        newFields.add(new HttpField(field.getHeader(), etagsNoSuffix));
                        request.setAttribute(GzipHandler.GZIP_HANDLER_ETAGS, etags);
                        continue;
                    }
                    newFields.add(field);
                }
                case CONTENT_LENGTH -> newFields.add(inflatable ? new HttpField("X-Content-Length", field.getValue()) : field);
                default -> newFields.add(field);
            }
        }
        fields = newFields.takeAsImmutable();
        return fields;
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
            if (_chunk instanceof Content.Chunk.Error)
                return _chunk;
            if (_chunk.isLast() && !_chunk.hasRemaining())
                return Content.Chunk.EOF;

            // Retain the input chunk because its ByteBuffer will be referenced by the Inflater.
            if (retain && _chunk.canRetain())
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
                _decoder.release(decodedBuffer);
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

        public ByteBuffer decode(Content.Chunk chunk)
        {
            decodeChunks(chunk.getByteBuffer());
            ByteBuffer decoded = _decoded;
            _decoded = null;
            return decoded;
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
