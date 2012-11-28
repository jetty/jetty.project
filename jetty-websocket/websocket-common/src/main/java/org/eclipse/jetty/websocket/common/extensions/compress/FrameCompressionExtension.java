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


import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Future;

import org.eclipse.jetty.websocket.api.WriteResult;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Implementation of the <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate-05.txt">x-webkit-deflate-frame</a> extension seen out
 * in the wild.
 */
public class FrameCompressionExtension extends AbstractExtension
{
    private DeflateCompressionMethod method;

    @Override
    public void incomingFrame(Frame frame)
    {
        if (frame.getType().isControl() || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones with RSV1 set.
            nextIncomingFrame(frame);
            return;
        }

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
                nextIncomingFrame(out);
            }

            // reset on every frame.
            // method.decompress().end();
        }
        finally
        {
            // release original buffer (no longer needed)
            getBufferPool().release(data);
        }
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     * <p>
     * Also known as the "COMP" framing header bit
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    /**
     * Indicate that this extensions is now responsible for TEXT Data Frame compliance to the WebSocket spec.
     */
    @Override
    public boolean isTextDataDecoder()
    {
        return true;
    }

    @Override
    public Future<WriteResult> outgoingFrame(Frame frame) throws IOException
    {
        if (frame.getType().isControl())
        {
            // skip, cannot compress control frames.
            return nextOutgoingFrame(frame);
        }

        Future<WriteResult> future = null;

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
                }

                future = nextOutgoingFrame(out);
            }

            // reset on every frame.
            method.compress().end();
        }
        finally
        {
            // free original data buffer
            getBufferPool().release(data);
        }

        return future;
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        method = new DeflateCompressionMethod();
    }

    @Override
    public String toString()
    {
        return this.getClass().getSimpleName() + "[]";
    }
}