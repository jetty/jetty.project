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

import org.eclipse.jetty.websocket.mux.MuxControlBlock;
import org.eclipse.jetty.websocket.mux.MuxOp;

public class MuxFlowControl implements MuxControlBlock
{
    private long channelId;
    private byte rsv;
    private long sendQuotaSize;

    public long getChannelId()
    {
        return channelId;
    }

    @Override
    public int getOpCode()
    {
        return MuxOp.FLOW_CONTROL;
    }

    public byte getRsv()
    {
        return rsv;
    }

    public long getSendQuotaSize()
    {
        return sendQuotaSize;
    }

    public void setChannelId(long channelId)
    {
        this.channelId = channelId;
    }

    public void setRsv(byte rsv)
    {
        this.rsv = rsv;
    }

    public void setSendQuotaSize(long sendQuotaSize)
    {
        this.sendQuotaSize = sendQuotaSize;
    }
}
