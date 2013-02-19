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

package org.eclipse.jetty.websocket.jsr356.decoders.samples;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;

public class DualDecoder implements Decoder.Text<Integer>, Decoder.Binary<Integer>
{
    @Override
    public Integer decode(ByteBuffer bytes) throws DecodeException
    {
        try
        {
            return bytes.getInt();
        }
        catch (BufferUnderflowException e)
        {
            throw new DecodeException(bytes,"Unable to read int from binary message",e);
        }
    }

    @Override
    public Integer decode(String s) throws DecodeException
    {
        try
        {
            return Integer.parseInt(s);
        }
        catch (NumberFormatException e)
        {
            throw new DecodeException(s,"Unable to parse Integer",e);
        }
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        if (bytes == null)
        {
            return false;
        }
        return bytes.remaining() >= 4;
    }

    @Override
    public boolean willDecode(String s)
    {
        if (s == null)
        {
            return false;
        }

        try
        {
            Integer.parseInt(s);
            return true;
        }
        catch (NumberFormatException e)
        {
            return false;
        }
    }
}
