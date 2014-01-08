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

package org.eclipse.jetty.websocket.mux;

import org.eclipse.jetty.websocket.common.OpCode;
import org.eclipse.jetty.websocket.common.frames.DataFrame;

public class MuxedFrame extends DataFrame
{
    private long channelId = -1;

    public MuxedFrame()
    {
        super(OpCode.BINARY);
    }

    public MuxedFrame(MuxedFrame frame)
    {
        super(frame);
        this.channelId = frame.channelId;
    }

    public long getChannelId()
    {
        return channelId;
    }

    @Override
    public void reset()
    {
        super.reset();
        this.channelId = -1;
    }

    public void setChannelId(long channelId)
    {
        this.channelId = channelId;
    }

    @Override
    public String toString()
    {
        StringBuilder b = new StringBuilder();
        b.append(OpCode.name(getOpCode()));
        b.append('[');
        b.append("channel=").append(channelId);
        b.append(",len=").append(getPayloadLength());
        b.append(",fin=").append(isFin());
        b.append(",rsv=");
        b.append(isRsv1()?'1':'.');
        b.append(isRsv2()?'1':'.');
        b.append(isRsv3()?'1':'.');
        b.append(']');
        return b.toString();
    }

    public void setOp(byte opcode)
    {
        // TODO Auto-generated method stub
        
    }
}
