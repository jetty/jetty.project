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

package org.eclipse.jetty.ee10.websocket.jakarta.tests.coders;

import java.nio.ByteBuffer;
import java.text.DecimalFormat;
import java.text.ParseException;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import jakarta.websocket.EndpointConfig;

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
                Long val = (Long)number;
                if (val > Integer.MAX_VALUE)
                {
                    throw new DecodeException(s, "Value exceeds Integer.MAX_VALUE");
                }
                return val.intValue();
            }

            if (number instanceof Integer)
            {
                return (Integer)number;
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
