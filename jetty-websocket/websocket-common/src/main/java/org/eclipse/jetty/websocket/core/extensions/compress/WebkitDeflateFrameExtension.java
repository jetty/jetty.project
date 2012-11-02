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

package org.eclipse.jetty.websocket.core.extensions.compress;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.Extension;
import org.eclipse.jetty.websocket.core.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.core.protocol.WebSocketFrame;

/**
 * Implementation of the <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate-05.txt">x-webkit-deflate-frame</a> extension seen out
 * in the wild.
 */
public class WebkitDeflateFrameExtension extends Extension
{
    private static final Logger LOG = Log.getLogger(WebkitDeflateFrameExtension.class);

    private DeflateCompressionMethod method;

    @Override
    public void incoming(WebSocketFrame frame)
    {
        if (frame.isControlFrame() || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones with RSV1 set.
            super.incoming(frame);
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
                WebSocketFrame out = new WebSocketFrame(frame,uncompressed);
                if (!method.decompress().isDone())
                {
                    out.setFin(false);
                }
                nextIncoming(out);
            }

            // reset on every frame.
            method.decompress().end();
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
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
    {
        if (frame.isControlFrame())
        {
            // skip, cannot compress control frames.
            nextOutput(context,callback,frame);
            return;
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("output({}, {}, {})",context,callback,frame);
        }

        ByteBuffer data = frame.getPayload();
        try
        {
            // deflate data
            method.compress().input(data);
            while (!method.compress().isDone())
            {
                ByteBuffer buf = method.compress().process();
                WebSocketFrame out = new WebSocketFrame(frame,buf);
                out.setRsv1(true);
                if (!method.compress().isDone())
                {
                    out.setFin(false);
                    nextOutputNoCallback(out);
                }
                else
                {
                    nextOutput(context,callback,out);
                }
            }

            // reset on every frame.
            method.compress().end();
        }
        finally
        {
            // free original data buffer
            getBufferPool().release(data);
        }
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