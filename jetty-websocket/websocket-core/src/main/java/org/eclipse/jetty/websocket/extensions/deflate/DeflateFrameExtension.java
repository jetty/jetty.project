// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.extensions.deflate;

import java.nio.ByteBuffer;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.api.BadPayloadException;
import org.eclipse.jetty.websocket.api.Extension;
import org.eclipse.jetty.websocket.api.MessageTooLargeException;
import org.eclipse.jetty.websocket.api.ProtocolException;
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

    // TODO: bring this method into some sort of ProtocolEnforcement class to share with Parser
    private void assertSanePayloadLength(WebSocketFrame frame, int len)
    {
        LOG.debug("Payload Length: " + len);
        // Since we use ByteBuffer so often, having lengths over Integer.MAX_VALUE is really impossible.
        if (len > Integer.MAX_VALUE)
        {
            // OMG! Sanity Check! DO NOT WANT! Won't anyone think of the memory!
            throw new MessageTooLargeException("[int-sane!] cannot handle payload lengths larger than " + Integer.MAX_VALUE);
        }
        getPolicy().assertValidPayloadLength(len);

        switch (frame.getOpCode())
        {
            case CLOSE:
                if (len == 1)
                {
                    throw new ProtocolException("Invalid close frame payload length, [" + len + "]");
                }
                // fall thru
            case PING:
            case PONG:
                if (len > WebSocketFrame.MAX_CONTROL_PAYLOAD)
                {
                    throw new ProtocolException("Invalid control frame payload length, [" + len + "] cannot exceed ["
                            + WebSocketFrame.MAX_CONTROL_PAYLOAD + "]");
                }
                break;
        }
    }

    @Override
    public void incoming(WebSocketFrame frame)
    {
        if (frame.getOpCode().isControlFrame() || !frame.isRsv1())
        {
            // Cannot modify incoming control frames or ones with RSV1 set.
            super.incoming(frame);
            return;
        }

        ByteBuffer data = frame.getPayload();
        // first 1 to 8 bytes contains post-inflated payload size.
        int uncompressedLength = readUncompresseLength(frame,data);

        // Set the data that is compressed to the inflater
        inflater.setInput(BufferUtil.toArray(frame.getPayload()));

        // Establish place for inflated data
        byte buf[] = new byte[uncompressedLength];
        try
        {
            int left = buf.length;
            while (inflater.getRemaining() > 0)
            {
                // TODO: worry about the ByteBuffer.array here??
                int inflated = inflater.inflate(buf,0,left);
                if (inflated == 0)
                {
                    throw new DataFormatException("insufficient data");
                }
                left -= inflated;
            }

            frame.setPayload(buf);

            nextIncoming(frame);
        }
        catch (DataFormatException e)
        {
            LOG.warn(e);
            throw new BadPayloadException(e);
        }
        finally
        {
            // release original buffer (no longer needed)
            getBufferPool().release(data);
        }
    }

    @Override
    public void output(WebSocketFrame frame)
    {
        if (frame.getOpCode().isControlFrame())
        {
            // skip, cannot compress control frames.
            super.output(frame);
            return;
        }

        if (frame.getPayloadLength() < minLength)
        {
            // skip, frame too small to care compressing it.
            super.output(frame);
            return;
        }

        ByteBuffer data = frame.getPayload();
        int length = frame.getPayloadLength();

        // prepare the uncompressed input
        deflater.reset();
        deflater.setInput(BufferUtil.toArray(data));
        deflater.finish();

        // prepare the output buffer
        byte out[] = new byte[length];
        int out_offset = 0;

        // write the uncompressed length
        if (length > 0xFF_FF)
        {
            out[out_offset++] = 0x7F;
            out[out_offset++] = (byte)0;
            out[out_offset++] = (byte)0;
            out[out_offset++] = (byte)0;
            out[out_offset++] = (byte)0;
            out[out_offset++] = (byte)((length >> 24) & 0xff);
            out[out_offset++] = (byte)((length >> 16) & 0xff);
            out[out_offset++] = (byte)((length >> 8) & 0xff);
            out[out_offset++] = (byte)(length & 0xff);
        }
        else if (length >= 0x7E)
        {
            out[out_offset++] = 0x7E;
            out[out_offset++] = (byte)(length >> 8);
            out[out_offset++] = (byte)(length & 0xff);
        }
        else
        {
            out[out_offset++] = (byte)(length & 0x7f);
        }

        deflater.deflate(out,out_offset,length - out_offset);

        frame.setPayload(out);
        frame.setRsv1(deflater.finished());
        nextOutput(frame);

        // free original data buffer
        getBufferPool().release(data);
    }

    private int readUncompresseLength(WebSocketFrame frame, ByteBuffer data)
    {
        int length = data.get();
        int bytes = 0;
        if (length == 0x7F)
        {
            // length 8 bytes (extended payload length)
            length = 0;
            bytes = 8;
        }
        else if (length == 0x7F)
        {
            // length 2 bytes (extended payload length)
            length = 0;
            bytes = 2;
        }

        while (bytes > 0)
        {
            byte b = data.get();
            length |= (b & 0xFF) << (8 * bytes);
        }

        assertSanePayloadLength(frame,length);

        return length;
    }

    @Override
    public void setConfig(ExtensionConfig config)
    {
        super.setConfig(config);

        minLength = config.getParameter("minLength",minLength);

        deflater = new Deflater();
        inflater = new Inflater();
    }
}
