//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.server.handler.gzip;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.GZIPContentDecoder;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.server.HttpInput;
import org.eclipse.jetty.server.HttpInput.Content;
import org.eclipse.jetty.util.component.Destroyable;

/**
 * An HttpInput Interceptor that inflates GZIP encoded request content.
 */
public class GzipHttpInputInterceptor implements HttpInput.Interceptor, Destroyable
{
    private final Decoder _decoder;
    private ByteBuffer _chunk;

    public GzipHttpInputInterceptor(ByteBufferPool pool, int bufferSize)
    {
        _decoder = new Decoder(pool, bufferSize);
    }

    @Override
    public Content readFrom(Content content)
    {
        _decoder.decodeChunks(content.getByteBuffer());
        final ByteBuffer chunk = _chunk;

        if (chunk == null)
            return null;

        return new Content(chunk)
        {
            @Override
            public void succeeded()
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
        private Decoder(ByteBufferPool pool, int bufferSize)
        {
            super(pool, bufferSize);
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
