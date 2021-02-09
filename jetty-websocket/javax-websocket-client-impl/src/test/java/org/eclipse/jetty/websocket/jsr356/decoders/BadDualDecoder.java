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

package org.eclipse.jetty.websocket.jsr356.decoders;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.jsr356.samples.Fruit;
import org.eclipse.jetty.websocket.jsr356.samples.FruitBinaryEncoder;

/**
 * Intentionally bad example of attempting to decode the same object to different message formats.
 */
public class BadDualDecoder implements Decoder.Text<Fruit>, Decoder.Binary<Fruit>
{
    @Override
    public Fruit decode(ByteBuffer bytes) throws DecodeException
    {
        try
        {
            int id = bytes.get(bytes.position());
            if (id != FruitBinaryEncoder.FRUIT_ID_BYTE)
            {
                // not a binary fruit object
                throw new DecodeException(bytes, "Not an encoded Binary Fruit object");
            }

            Fruit fruit = new Fruit();
            fruit.name = getUTF8String(bytes);
            fruit.color = getUTF8String(bytes);
            return fruit;
        }
        catch (BufferUnderflowException e)
        {
            throw new DecodeException(bytes, "Unable to read Fruit from binary message", e);
        }
    }

    @Override
    public Fruit decode(String s) throws DecodeException
    {
        Pattern pat = Pattern.compile("([^|]*)|([^|]*)");
        Matcher mat = pat.matcher(s);
        if (!mat.find())
        {
            throw new DecodeException(s, "Unable to find Fruit reference encoded in text message");
        }

        Fruit fruit = new Fruit();
        fruit.name = mat.group(1);
        fruit.color = mat.group(2);

        return fruit;
    }

    @Override
    public void destroy()
    {
    }

    private String getUTF8String(ByteBuffer buf)
    {
        int strLen = buf.getInt();
        ByteBuffer slice = buf.slice();
        slice.limit(slice.position() + strLen);
        String str = BufferUtil.toUTF8String(slice);
        buf.position(buf.position() + strLen);
        return str;
    }

    @Override
    public void init(EndpointConfig config)
    {
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        if (bytes == null)
        {
            return false;
        }
        int id = bytes.get(bytes.position());
        return (id != FruitBinaryEncoder.FRUIT_ID_BYTE);
    }

    @Override
    public boolean willDecode(String s)
    {
        if (s == null)
        {
            return false;
        }

        Pattern pat = Pattern.compile("([^|]*)|([^|]*)");
        Matcher mat = pat.matcher(s);
        return (mat.find());
    }
}
