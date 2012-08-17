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

package org.eclipse.jetty.websocket.extensions.deflate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.protocol.ExtensionConfig;
import org.eclipse.jetty.websocket.protocol.WebSocketFrame;

/**
 * @TODO Implement proposed deflate frame draft
 */
public class DeflateFrameExtension extends Extension
{
    private static final Logger LOG = Log.getLogger(DeflateFrameExtension.class);

    private int minLength = 8;
    private Deflater deflater;
    private Inflater inflater;

    private void assertSanePayloadLength(int len)
    {
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (len > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new MessageTooLargeException("[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        getPolicy().assertValidPayloadLength(len);
    }

    public ByteBuffer deflate(ByteBuffer data)
    {
        int length = data.remaining();

        // prepare the uncompressed input
        deflater.reset();
        deflater.setInput(BufferUtil.toArray(data));
        deflater.finish();

        // prepare the output buffer
        ByteBuffer buf = getBufferPool().acquire(length,false);
        BufferUtil.clearToFill(buf);

        // write the uncompressed length
        if (length > 0xFF_FF)
        {
            buf.put((byte)0x7F);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)0x00);
            buf.put((byte)((length >> 24) & 0xFF));
            buf.put((byte)((length >> 16) & 0xFF));
            buf.put((byte)((length >> 8) & 0xFF));
            buf.put((byte)(length & 0xFF));
        }
        else if (length >= 0x7E)
        {
            buf.put((byte)0x7E);
            buf.put((byte)(length >> 8));
            buf.put((byte)(length & 0xFF));
        }
        else
        {
            buf.put((byte)(length & 0x7F));
        }

        if (LOG.isDebugEnabled())
        {
            LOG.debug("Uncompressed length={} - {}",length,buf.position());
        }

        while (!deflater.finished())
        {
            byte out[] = new byte[length];
            int len = deflater.deflate(out,0,length,Deflater.FULL_FLUSH);

            if (LOG.isDebugEnabled())
            {
                LOG.debug("Deflater: finished={}, needsInput={}, len={} / input.len={}",deflater.finished(),deflater.needsInput(),len,length);
            }

            buf.put(out,0,len);
        }
        BufferUtil.flipToFlush(buf,0);
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
        }
        // first 1 to 8 bytes contains post-inflated payload size.
        int uncompressedLength = readUncompresseLength(data);

        if (LOG.isDebugEnabled())
        {
            LOG.debug("uncompressedLength={}, data={}",uncompressedLength,BufferUtil.toDetailString(data));
        }

        // Set the data that is compressed to the inflater
        byte compressed[] = BufferUtil.toArray(data);
        inflater.reset();
        inflater.setInput(compressed,0,compressed.length);

        // Establish place for inflated data
        byte buf[] = new byte[uncompressedLength];
        try
        {
            int inflated = inflater.inflate(buf);
            if (inflated == 0)
            {
                throw new DataFormatException("Insufficient compressed data");
            }

            ByteBuffer ret = ByteBuffer.wrap(buf);

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

    @Override
    public <C> void output(C context, Callback<C> callback, WebSocketFrame frame) throws IOException
    {
        if (frame.isControlFrame())
        {
            // skip, cannot compress control frames.
            nextOutput(context,callback,frame);
            return;
        }

        if (frame.getPayloadLength() < minLength)
        {
            // skip, frame too small to care compressing it.
            nextOutput(context,callback,frame);
            return;
        }

        ByteBuffer data = frame.getPayload();
        try
        {
            // deflate data
            ByteBuffer buf = deflate(data);
            frame.setPayload(buf);
            frame.setRsv1(deflater.finished());
            nextOutput(context,callback,frame);
        }
        finally
        {
            // free original data buffer
            getBufferPool().release(data);
        }
    }

    /**
     * Read the uncompressed length indicator in the frame.
     * <p>
     * Will modify the position of the buffer.
     * 
     * @param data
     * @return
     */
    public int readUncompresseLength(ByteBuffer data)
    {
        int length = data.get();
        int bytes = 0;
        if (length == 127) // 0x7F
        {
            // length 8 bytes (extended payload length)
            length = 0;
            bytes = 8;
        }
        else if (length == 126) // 0x7E
        {
            // length 2 bytes (extended payload length)
            length = 0;
            bytes = 2;
        }

        while (bytes > 0)
        {
            --bytes;
            byte b = data.get();
            length |= (b & 0xFF) << (8 * bytes);
        }

        assertSanePayloadLength(length);

        return length;
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        minLength = config.getParameter("minLength",minLength);

        deflater = new Deflater(Deflater.BEST_COMPRESSION);
        deflater.setStrategy(Deflater.DEFAULT_STRATEGY);
        inflater = new Inflater();
    }

    @Override
    public String toString()
    {
        return String.format("DeflateFrameExtension[minLength=%d]",minLength);
    }

    /**
     * Indicates use of RSV1 flag for indicating deflation is in use.
     */
    @Override
    public boolean useRsv1()
    {
        return true;
    }
}
