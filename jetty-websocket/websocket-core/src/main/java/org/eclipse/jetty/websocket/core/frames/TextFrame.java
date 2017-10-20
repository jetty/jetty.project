//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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
import org.eclipse.jetty.util.StringUtil;

public class TextFrame extends DataFrame
{
    public TextFrame()
    {
        super(OpCode.TEXT);
    }
    
    public TextFrame(TextFrame frame)
    {
        super(frame);
    }

    @Override
    public Type getType()
    {
        if (getOpCode() == OpCode.CONTINUATION)
            return Type.CONTINUATION;
        return Type.TEXT;
    }

    public TextFrame setPayload(String str)
    {
        setPayload(ByteBuffer.wrap(StringUtil.getUtf8Bytes(str)));
        return this;
    }
    
    public String getPayloadAsUTF8()
    {
        if (payload == null)
        {
            return "";
        }
        return BufferUtil.toUTF8String(payload);
    }
}
