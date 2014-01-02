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

public class MuxNewChannelSlot implements MuxControlBlock
{
    private boolean fallback;
    private long initialSendQuota;
    private long numberOfSlots;
    private byte rsv;

    public long getInitialSendQuota()
    {
        return initialSendQuota;
    }

    public long getNumberOfSlots()
    {
        return numberOfSlots;
    }

    @Override
    public int getOpCode()
    {
        return MuxOp.NEW_CHANNEL_SLOT;
    }

    public byte getRsv()
    {
        return rsv;
    }

    public boolean isFallback()
    {
        return fallback;
    }

    public void setFallback(boolean fallback)
    {
        this.fallback = fallback;
    }

    public void setInitialSendQuota(long initialSendQuota)
    {
        this.initialSendQuota = initialSendQuota;
    }

    public void setNumberOfSlots(long numberOfSlots)
    {
        this.numberOfSlots = numberOfSlots;
    }

    public void setRsv(byte rsv)
    {
        this.rsv = rsv;
    }
}
