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

package org.eclipse.jetty.websocket.core.extensions.deflate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.core.api.BadPayloadException;
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
    private static final int BUFFER_SIZE = 64 * 1024;

    private Deflater deflater;
    private Inflater inflater;

    public ByteBuffer deflate(ByteBuffer data)
    {
        int length = data.remaining();

        // prepare the uncompressed input
        deflater.reset();
        deflater.setInput(BufferUtil.toArray(data));
        deflater.finish();

        // prepare the output buffer
        int bufsize = Math.max(BUFFER_SIZE,length * 2);
        ByteBuffer buf = getBufferPool().acquire(bufsize,false);
        BufferUtil.clearToFill(buf);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Uncompressed length={} - {}",length,buf.position());
        }

        while (!deflater.finished())
        {
            byte out[] = new byte[BUFFER_SIZE];
            int len = deflater.deflate(out,0,out.length,Deflater.SYNC_FLUSH);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Deflater: finished={}, needsInput={}, len={} / input.len={}",deflater.finished(),deflater.needsInput(),len,length);
            }

            buf.put(out,0,len);
        }
        BufferUtil.flipToFlush(buf,0);

        /* Per the spec, it says that BFINAL 1 or 0 are allowed.
         * However, Java always uses BFINAL 1, where the browsers
         * Chrome and Safari fail to decompress when it encounters
         * BFINAL 1.
         * 
         * This hack will always set BFINAL 0
         */
        byte b0 = buf.get(0);
        if ((b0 & 1) != 0) // if BFINAL 1
        {
            buf.put(0,(b0 ^= 1)); // flip bit to BFINAL 0
        }
        return buf;
    }

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
            ByteBuffer uncompressed = inflate(data);
            frame.setPayload(uncompressed);
            nextIncoming(frame);
        }
        finally
        {
            // release original buffer (no longer needed)
            getBufferPool().release(data);
        }
    }

    public ByteBuffer inflate(ByteBuffer data)
    {
        if (LOG.isDebugEnabled())
        {
            LOG.debug("inflate: {}",BufferUtil.toDetailString(data));
            LOG.debug("raw data: {}",TypeUtil.toHexString(BufferUtil.toArray(data)));
        }

        // Set the data that is compressed to the inflater
        byte compressed[] = BufferUtil.toArray(data);
        inflater.reset();
        inflater.setInput(compressed,0,compressed.length);

        // Establish place for inflated data
        byte buf[] = new byte[BUFFER_SIZE];
        try
        {
            int inflated = inflater.inflate(buf);
            if (inflated == 0)
            {
                throw new DataFormatException("Insufficient compressed data");
            }

            ByteBuffer ret = ByteBuffer.wrap(buf,0,inflated);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("uncompressed={}",BufferUtil.toDetailString(ret));
            }

            return ret;
        }
        catch (DataFormatException e)
        {
            LOG.warn(e);
            throw new BadPayloadException(e);
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

        ByteBuffer data = frame.getPayload();
        try
        {
            // deflate data
            ByteBuffer buf = deflate(data);
            frame.setPayload(buf);
            frame.setRsv1(true);
            nextOutput(context,callback,frame);
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

        boolean nowrap = true;

        deflater = new Deflater(Deflater.BEST_COMPRESSION,nowrap);
        deflater.setStrategy(Deflater.DEFAULT_STRATEGY);
        inflater = new Inflater(nowrap);
    }

    @Override
    public String toString()
    {
        return String.format("DeflateFrameExtension[]");
    }
}