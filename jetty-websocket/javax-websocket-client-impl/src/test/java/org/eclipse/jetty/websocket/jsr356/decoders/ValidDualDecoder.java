//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.nio.ByteBuffer;

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
        return bytes.getLong();
    }

    @Override
    public Integer decode(String s) throws DecodeException
    {
        return Integer.parseInt(s);
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
