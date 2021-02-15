//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.websocket.common.OpCode;

public class TextFrame extends DataFrame
{
    public TextFrame()
    {
        super(OpCode.TEXT);
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

    @Override
    public String getPayloadAsUTF8()
    {
        if (data == null)
        {
            return null;
        }
        return BufferUtil.toUTF8String(data);
    }
}
