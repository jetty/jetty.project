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

package org.eclipse.jetty.ee9.handler.gzip;

import java.nio.ByteBuffer;

import org.eclipse.jetty.ee9.handler.HttpInput;
import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.compression.InflaterPool;

/**
 * An HttpInput Interceptor that inflates GZIP encoded request content.
 */
public class GzipHttpInputInterceptor implements HttpInput.Interceptor, Destroyable
{
    private final Decoder _decoder;
    private ByteBuffer _chunk;

    public GzipHttpInputInterceptor(InflaterPool inflaterPool, ByteBufferPool pool, int bufferSize)
    {
        _decoder = new Decoder(inflaterPool, pool, bufferSize);
    }

    @Override
    public Content readFrom(Content content)
    {
        if (content.isSpecial())
            return content;

        _decoder.decodeChunks(content.getByteBuffer());
        final ByteBuffer chunk = _chunk;

        if (chunk == null)
            return null;

        return new Content.Buffer(chunk)
        {
            @Override
            public void release()
            {
                _decoder.release(chunk);
            }
        };
    }

    @Override
    public void destroy()
    {
        _decoder.destroy();
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
