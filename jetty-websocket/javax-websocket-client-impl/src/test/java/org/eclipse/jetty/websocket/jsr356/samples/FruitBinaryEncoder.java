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

package org.eclipse.jetty.websocket.jsr356.samples;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.util.BufferUtil;

public class FruitBinaryEncoder implements Encoder.Binary<Fruit>
{
    public static final byte FRUIT_ID_BYTE = (byte)0xAF;
    // the number of bytes to store a string (1 int)
    public static final int STRLEN_STORAGE = 4;

    @Override
    public void destroy()
    {
    }

    @Override
    public ByteBuffer encode(Fruit fruit) throws EncodeException
    {
        int len = 1; // id byte
        len += STRLEN_STORAGE + fruit.name.length();
        len += STRLEN_STORAGE + fruit.color.length();

        ByteBuffer buf = ByteBuffer.allocate(len + 64);
        buf.flip();
        buf.put(FRUIT_ID_BYTE);
        putString(buf, fruit.name);
        putString(buf, fruit.color);
        buf.flip();

        return buf;
    }

    @Override
    public void init(EndpointConfig config)
    {
    }

    private void putString(ByteBuffer buf, String str)
    {
        buf.putInt(str.length());
        BufferUtil.toBuffer(str, Charset.forName("UTF-8"));
    }
}
