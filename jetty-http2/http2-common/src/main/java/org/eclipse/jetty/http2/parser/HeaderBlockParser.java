//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http2.parser;

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http2.hpack.HpackDecoder;
import org.eclipse.jetty.http2.hpack.HpackException.SessionException;
import org.eclipse.jetty.http2.hpack.HpackException.StreamException;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;

public class HeaderBlockParser
{
    private final ByteBufferPool byteBufferPool;
    private final HpackDecoder hpackDecoder;
    private ByteBuffer blockBuffer;

    public HeaderBlockParser(ByteBufferPool byteBufferPool, HpackDecoder hpackDecoder)
    {
        this.byteBufferPool = byteBufferPool;
        this.hpackDecoder = hpackDecoder;
    }

    public MetaData parse(ByteBuffer buffer, int blockLength)
    {
        // We must wait for the all the bytes of the header block to arrive.
        // If they are not all available, accumulate them.
        // When all are available, decode them.

        int accumulated = blockBuffer == null ? 0 : blockBuffer.position();
        int remaining = blockLength - accumulated;

        if (buffer.remaining() < remaining)
        {
            if (blockBuffer == null)
            {
                blockBuffer = byteBufferPool.acquire(blockLength, false);
                BufferUtil.clearToFill(blockBuffer);
            }
            blockBuffer.put(buffer);
            return null;
        }
        else
        {
            int limit = buffer.limit();
            buffer.limit(buffer.position() + remaining);
            ByteBuffer toDecode;
            if (blockBuffer != null)
            {
                blockBuffer.put(buffer);
                BufferUtil.flipToFlush(blockBuffer, 0);
                toDecode = blockBuffer;
            }
            else
            {
                toDecode = buffer;
            }

            try
            {
               MetaData metadata = hpackDecoder.decode(toDecode);
               
               if (metadata instanceof MetaData.Request)
               {
                   // TODO this must be an initial HEADERs frame received by the server OR
                   // TODO a push promise received by the client.
                   // TODO this must not be a trailers frame
               }
               else if (metadata instanceof MetaData.Response)
               {
                   // TODO this must be an initial HEADERs frame received by the client 
                   // TODO this must not be a trailers frame
               }
               else
               {
                   // TODO this must be a trailers frame
               }
               
               return metadata;
            }
            catch(StreamException ex)
            {
                // TODO reset the stream
                throw new BadMessageException("TODO");
            }
            catch(SessionException ex)
            {
                // TODO reset the session
                throw new BadMessageException("TODO");
            }
            finally
            {
                buffer.limit(limit);

                if (blockBuffer != null)
                {
                    byteBufferPool.release(blockBuffer);
                    blockBuffer = null;
                }
            }
        }
    }
}
