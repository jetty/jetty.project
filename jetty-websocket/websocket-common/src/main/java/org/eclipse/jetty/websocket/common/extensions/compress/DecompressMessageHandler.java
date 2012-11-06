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
 * Decompress entire message (regardless of number of frames)
 */
public class DecompressMessageHandler extends AbstractJettyFrameHandler
{
    private CompressionMethod method;
    private final ByteBufferPool bufferPool;

    public DecompressMessageHandler(FrameHandler nextHandler, CompressionMethod method, ByteBufferPool bufferPool)
    {
        super(nextHandler);
        this.method = method;
        this.bufferPool = bufferPool;
    }

    @Override
    public void handleJettyFrame(WebSocketFrame frame)
    {
        if (frame.getType().isControl() || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones with RSV1 set.
            nextJettyHandler(frame);
            return;
        }

        ByteBuffer data = frame.getPayload();
        try
        {
            method.decompress().input(data);
            while (!method.decompress().isDone())
            {
                ByteBuffer uncompressed = method.decompress().process();
                if (uncompressed == null)
                {
                    continue;
                }
                WebSocketFrame out = new WebSocketFrame(frame).setPayload(uncompressed);
                if (!method.decompress().isDone())
                {
                    out.setFin(false);
                }
                out.setRsv1(false); // Unset RSV1 on decompressed frame
                nextJettyHandler(out);
            }

            // reset only at the end of a message.
            if (frame.isFin())
            {
                method.decompress().end();
            }
        }
        finally
        {
            // release original buffer (no longer needed)
            bufferPool.release(data);
        }
    }
}