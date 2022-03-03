//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.common.coders.tests;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.util.BufferUtil;

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
