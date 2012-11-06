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
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractJettyFrameHandler;

/**
 * Handler for Decompress of individual Data frames.
 */
public class DecompressFrameHandler extends AbstractJettyFrameHandler
{
    private static final Logger LOG = Log.getLogger(DecompressFrameHandler.class);
    private final CompressionMethod method;
    private final ByteBufferPool bufferPool;

    public DecompressFrameHandler(FrameHandler incoming, DeflateCompressionMethod method, ByteBufferPool bufferPool)
    {
        super(incoming);
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

        LOG.debug("Decompressing Frame: {}",frame);

        ByteBuffer data = frame.getPayload();
        try
        {
            method.decompress().input(data);
            while (!method.decompress().isDone())
            {
                ByteBuffer uncompressed = method.decompress().process();
                WebSocketFrame out = new WebSocketFrame(frame).setPayload(uncompressed);
                if (!method.decompress().isDone())
                {
                    out.setFin(false);
                }
                out.setRsv1(false); // Unset RSV1 on decompressed frame
                nextJettyHandler(out);
            }

            // reset on every frame.
            method.decompress().end();
        }
        finally
        {
            // release original buffer (no longer needed)
            bufferPool.release(data);
        }
    }
}