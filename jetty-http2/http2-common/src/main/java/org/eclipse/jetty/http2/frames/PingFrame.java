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

package org.eclipse.jetty.http2.frames;

public class PingFrame extends Frame
{
    private final byte[] payload;
    private final boolean reply;

    public PingFrame(byte[] payload, boolean reply)
    {
        super(FrameType.PING);
        this.payload = payload;
        this.reply = reply;
    }

    public byte[] getPayload()
    {
        return payload;
    }

    public boolean isReply()
    {
        return reply;
    }
}
