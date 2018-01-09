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

package org.eclipse.jetty.websocket.jsr356.coders;

import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.ParseException;

import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.EndpointConfig;

/**
 * Example of a valid decoder impl declaring 2 decoders.
 */
public class ValidDualDecoder implements Decoder.Text<Integer>, Decoder.Binary<Long>
{
    @Override
    public Long decode(ByteBuffer bytes) throws DecodeException
    {
        if (bytes.get() != '[')
            throw new DecodeException(bytes, "Unexpected opening byte");
        long val = bytes.getLong();
        if (bytes.get() != ']')
            throw new DecodeException(bytes, "Unexpected closing byte");
        return val;
    }

    @Override
    public Integer decode(String s) throws DecodeException
    {
        DecimalFormat numberFormat = new DecimalFormat("[#,###]");
        try
        {
            Number number = numberFormat.parse(s);
            if (number instanceof Long)
            {
                Long val = (Long) number;
                if (val > Integer.MAX_VALUE)
                {
                    throw new DecodeException(s, "Value exceeds Integer.MAX_VALUE");
                }
                return val.intValue();
            }

            if (number instanceof Integer)
            {
                return (Integer) number;
            }

            throw new DecodeException(s, "Unrecognized number format");
        }
        catch (ParseException e)
        {
            throw new DecodeException(s, "Unable to parse number", e);
        }
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void init(EndpointConfig config)
    {
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        return true;
    }

    @Override
    public boolean willDecode(String s)
    {
        return true;
    }
}
