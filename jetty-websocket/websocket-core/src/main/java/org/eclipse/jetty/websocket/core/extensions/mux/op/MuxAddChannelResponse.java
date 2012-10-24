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

package org.eclipse.jetty.websocket.core.extensions.mux.op;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.core.extensions.mux.MuxControlBlock;
import org.eclipse.jetty.websocket.core.extensions.mux.MuxOp;

public class MuxAddChannelResponse implements MuxControlBlock
{
    private long channelId;
    private byte enc;
    private byte rsv;
    private boolean failed = false;
    private ByteBuffer handshake;

    public long getChannelId()
    {
        return channelId;
    }

    public byte getEnc()
    {
        return enc;
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
        return MuxOp.ADD_CHANNEL_RESPONSE;
    }

    public byte getRsv()
    {
        return rsv;
    }

    public boolean isFailed()
    {
        return failed;
    }

    public void setChannelId(long channelId)
    {
        this.channelId = channelId;
    }

    public void setEnc(byte enc)
    {
        this.enc = enc;
    }

    public void setFailed(boolean failed)
    {
        this.failed = failed;
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

    public void setRsv(byte rsv)
    {
        this.rsv = rsv;
    }
}
