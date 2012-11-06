//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.extensions.compress;

import java.nio.ByteBuffer;

import javax.net.websocket.extensions.FrameHandler;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;

/**
 * Compress entire message (regardless of number of frames).
 */
public class CompressMessageHandler extends AbstractJettyFrameHandler
{
    private final CompressionMethod method;
    private final ByteBufferPool bufferPool;

    public CompressMessageHandler(FrameHandler nextHandler, CompressionMethod method, ByteBufferPool bufferPool)
    {
        super(nextHandler);
        this.method = method;
        this.bufferPool = bufferPool;
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        if (frame.getType().isControl())
        {
            // skip, cannot compress control frames.
            nextJettyHandler(frame);
            return;
        }

        ByteBuffer data = frame.getPayload();
        try
        {
            // deflate data
            method.compress().input(data);
            while (!method.compress().isDone())
            {
                ByteBuffer buf = method.compress().process();
                WebSocketFrame out = new WebSocketFrame(frame).setPayload(buf);
                out.setRsv1(true);
                if (!method.compress().isDone())
                {
                    out.setFin(false);
                    nextJettyHandler(out);
                }
                else
                {
                    nextJettyHandler(out);
                }
            }

            // reset only at end of message
            if (frame.isFin())
            {
                method.compress().end();
            }
        }
        finally
        {
            // free original data buffer
            bufferPool.release(data);
        }
    }

}