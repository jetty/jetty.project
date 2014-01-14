//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.mux.op;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.mux.MuxControlBlock;
import org.eclipse.jetty.websocket.mux.MuxOp;

public class MuxAddChannelRequest implements MuxControlBlock
{
    public static final byte IDENTITY_ENCODING = (byte)0x00;
    public static final byte DELTA_ENCODING = (byte)0x01;

    private long channelId = -1;
    private byte encoding;
    private ByteBuffer handshake;
    private byte rsv;

    public long getChannelId()
    {
        return channelId;
    }

    public byte getEncoding()
    {
        return encoding;
    }

    public ByteBuffer getHandshake()
    {
        return handshake;
    }

    public long getHandshakeSize()
    {
        if (handshake == null)
        {
            return 0;
        }
        return handshake.remaining();
    }

    @Override
    public int getOpCode()
    {
        return MuxOp.ADD_CHANNEL_REQUEST;
    }

    public byte getRsv()
    {
        return rsv;
    }

    public boolean isDeltaEncoded()
    {
        return (encoding == DELTA_ENCODING);
    }

    public boolean isIdentityEncoded()
    {
        return (encoding == IDENTITY_ENCODING);
    }

    public void setChannelId(long channelId)
    {
        this.channelId = channelId;
    }

    public void setEncoding(byte enc)
    {
        this.encoding = enc;
    }

    public void setHandshake(ByteBuffer handshake)
    {
        if (handshake == null)
        {
            this.handshake = null;
        }
        else
        {
            this.handshake = handshake.slice();
        }
    }

    public void setHandshake(String rawstring)
    {
        setHandshake(BufferUtil.toBuffer(rawstring, StandardCharsets.UTF_8));
    }

    public void setRsv(byte rsv)
    {
        this.rsv = rsv;
    }
}
