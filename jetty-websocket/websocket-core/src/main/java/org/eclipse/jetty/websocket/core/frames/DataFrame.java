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

import org.eclipse.jetty.util.BufferUtil;

/**
 * A Data Frame
 */
public class DataFrame extends WebSocketFrame
{
    public DataFrame(byte opcode)
    {
        super(opcode);
    }

    public DataFrame(byte opCode, ByteBuffer payload)
    {
        this(opCode);
        setPayload(payload);
    }

    public DataFrame(byte opCode, String payload)
    {
        this(opCode);
        setPayload(payload);
    }

    public DataFrame(byte opCode, ByteBuffer payload, boolean fin)
    {
        this(opCode, payload);
        setFin(fin);
    }

    public DataFrame(byte opCode, String payload, boolean fin)
    {
        this(opCode, payload);
        setFin(fin);
    }

    @Override
    public final boolean isControlFrame()
    {
        return false;
    }

    @Override
    public final boolean isDataFrame()
    {
        return true;
    }
    
    @Override
    public String toString()
    {
        return super.toString()+BufferUtil.toDetailString(payload);
    }
}
