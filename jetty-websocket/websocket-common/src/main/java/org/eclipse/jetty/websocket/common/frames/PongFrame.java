//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.common.frames;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.OpCode;

public class PongFrame extends ControlFrame
{
    public PongFrame()
    {
        super(OpCode.PONG);
    }
    
    public PongFrame(ByteBuffer buf)
    {
        this();
        setPayload(buf);
    }

    public PongFrame(byte[] bytes)
    {
        this();
        setPayload(ByteBuffer.wrap(bytes));
    }

    public PongFrame(String payload)
    {
        this(StringUtil.getUtf8Bytes(payload));
    }

    @Override
    public Type getType()
    {
        return Type.PONG;
    }
}
