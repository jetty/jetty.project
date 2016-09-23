//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.server.HttpInput.Interceptor;

public class GzipHttpInputInterceptor extends HttpInput.NestedInterceptor
{
    class Decoder extends GZIPContentDecoder
    {
        private boolean wakeup;
        
        public Decoder(ByteBufferPool pool, int bufferSize)
        {
            super(pool,bufferSize);
        }

        @Override
        protected void decodedChunk(final ByteBuffer chunk)
        {
            boolean woken = getNext().addContent(new Content(chunk)
            {
                @Override
                public void succeeded()
                {
                    release(chunk);
                }              
            });
            wakeup |= woken;
        }

        @Override
        public void decodeChunks(ByteBuffer compressed)
        {
            wakeup=false;
            super.decodeChunks(compressed);
        }
        
        public boolean isWakeup()
        {
            return wakeup;
        }
        
    }
    
    private final Decoder _decoder;
    
    public GzipHttpInputInterceptor(Interceptor next,ByteBufferPool pool, int bufferSize)
    {
        super(next);
        _decoder = new Decoder(pool,bufferSize);
    }

    @Override
    public boolean addContent(Content content)
    {
        _decoder.decodeChunks(content.getByteBuffer());
        boolean wakeup = _decoder.isWakeup(); 
        
        // If there is still content remaining, this is unusual
        // but let the HttpInput consumer deal with it
        if (content.hasContent())
            wakeup = getNext().addContent(content) | wakeup;
        else
            // Otherwise succeed it
            content.succeeded();
        return wakeup;
    }
    
}
