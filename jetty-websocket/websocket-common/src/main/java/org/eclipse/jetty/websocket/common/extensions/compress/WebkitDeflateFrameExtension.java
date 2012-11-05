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

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.extensions.ExtensionConfig;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.api.extensions.FrameHandler;
import org.eclipse.jetty.websocket.common.WebSocketFrame;
import org.eclipse.jetty.websocket.common.extensions.AbstractExtension;
import org.eclipse.jetty.websocket.common.extensions.FrameHandlerAdapter;

/**
 * Implementation of the <a href="https://tools.ietf.org/id/draft-tyoshino-hybi-websocket-perframe-deflate-05.txt">x-webkit-deflate-frame</a> extension seen out
 * in the wild.
 */
public class WebkitDeflateFrameExtension extends AbstractExtension
{
    private static class CompressFrameHandler extends FrameHandlerAdapter
    {
        private final CompressionMethod method;
        private final ByteBufferPool bufferPool;

        public CompressFrameHandler(DeflateCompressionMethod method, ByteBufferPool bufferPool)
        {
            this.method = method;
            this.bufferPool = bufferPool;
        }

        @Override
        public void handleFrame(Frame frame)
        {
            if (frame instanceof Frame.Control)
            {
                // skip, cannot compress control frames.
                nextHandler(frame);
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
                    }

                    nextHandler(out);
                }

                // reset on every frame.
                method.compress().end();
            }
            finally
            {
                // free original data buffer
                bufferPool.release(data);
            }
        }
    }

    private static class DecompressFrameHandler extends FrameHandlerAdapter
    {
        private final CompressionMethod method;
        private final ByteBufferPool bufferPool;

        public DecompressFrameHandler(DeflateCompressionMethod method, ByteBufferPool bufferPool)
        {
            this.method = method;
            this.bufferPool = bufferPool;

        }

        @Override
        public void handleFrame(Frame frame)
        {
            if ((frame instanceof Frame.Control) || !frame.isRsv1())
            {
                // Cannot modify incoming control frames or ones with RSV1 set.
                nextHandler(frame);
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
                    nextHandler(out);
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

    private static final Logger LOG = Log.getLogger(WebkitDeflateFrameExtension.class);

    private DeflateCompressionMethod method;

    @Override
    public FrameHandler createIncomingFrameHandler()
    {
        return new DecompressFrameHandler(method,getBufferPool());
    }

    @Override
    public FrameHandler createOutgoingFrameHandler()
    {
        return new CompressFrameHandler(method,getBufferPool());
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