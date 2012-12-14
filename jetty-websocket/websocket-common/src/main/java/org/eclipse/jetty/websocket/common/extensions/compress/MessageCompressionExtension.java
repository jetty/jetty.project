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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.WriteCallback;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;

/**
 * Per Message Compression extension for WebSocket.
 * <p>
 * Attempts to follow <a href="https://tools.ietf.org/html/draft-ietf-hybi-permessage-compression-01">draft-ietf-hybi-permessage-compression-01</a>
 */
public class MessageCompressionExtension extends AbstractExtension
{
    private static final Logger LOG = Log.getLogger(MessageCompressionExtension.class);

    private CompressionMethod method;

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
            nextIncomingFrame(out);
        }

        // reset only at the end of a message.
        if (frame.isFin())
        {
            method.decompress().end();
        }
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     */
    @Override
    public boolean isRsv1User()
    {
        return true;
    }

    @Override
    public boolean isTextDataDecoder()
    {
        // this extension is responsible for text data frames
        return true;
    }

    @Override
    public void outgoingFrame(Frame frame, WriteCallback callback)
    {
        if (frame.getType().isControl())
        {
            // skip, cannot compress control frames.
            nextOutgoingFrame(frame,callback);
            return;
        }

        ByteBuffer data = frame.getPayload();
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
                // no callback for start/middle frames
                nextOutgoingFrame(out,null);
            }
            else
            {
                // pass through callback to last frame
                nextOutgoingFrame(out,callback);
            }
        }

        // reset only at end of message
        if (frame.isFin())
        {
            method.compress().end();
        }
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        String methodOptions = config.getParameter("method","deflate");
        LOG.debug("Method requested: {}",methodOptions);

        method = new DeflateCompressionMethod();
    }

    @Override
    public String toString()
    {
        return String.format("%s[method=%s]",this.getClass().getSimpleName(),method);
    }
}
