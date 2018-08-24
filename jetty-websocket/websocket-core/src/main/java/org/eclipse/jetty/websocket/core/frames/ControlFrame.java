//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.frames;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.jetty.websocket.core.ProtocolException;

public abstract class ControlFrame extends WebSocketFrame
{
    /** Maximum size of Control frame, per RFC 6455 */
    public static final int MAX_CONTROL_PAYLOAD = 125;

    public ControlFrame(byte opcode)
    {
        super(opcode);
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }
        if (obj == null)
        {
            return false;
        }
        if (getClass() != obj.getClass())
        {
            return false;
        }
        ControlFrame other = (ControlFrame)obj;
        if (payload == null)
        {
            if (other.payload != null)
            {
                return false;
            }
        }
        else if (!payload.equals(other.payload))
        {
            return false;
        }
        if (finRsvOp != other.finRsvOp)
        {
            return false;
        }
        if (!Arrays.equals(mask,other.mask))
        {
            return false;
        }
        if (masked != other.masked)
        {
            return false;
        }
        return true;
    }

    @Override
    public final boolean isControlFrame()
    {
        return true;
    }

    @Override
    public final boolean isDataFrame()
    {
        return false;
    }

    @Override
    public WebSocketFrame setPayload(ByteBuffer buf)
    {
        // RFC-6455 Spec Required Control Frame validation.
        if (buf != null && buf.remaining() > MAX_CONTROL_PAYLOAD)
        {
            throw new ProtocolException("Control Payloads can not exceed " + MAX_CONTROL_PAYLOAD + " bytes in length.");
        }
        return super.setPayload(buf);
    }
}
