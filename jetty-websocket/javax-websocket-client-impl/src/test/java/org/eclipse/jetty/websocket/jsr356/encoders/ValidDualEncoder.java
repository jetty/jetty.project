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

package org.eclipse.jetty.websocket.jsr356.encoders;

import java.io.IOException;
import java.io.OutputStream;
import javax.websocket.EncodeException;
import javax.websocket.Encoder;
import javax.websocket.EndpointConfig;

/**
 * Example of a valid encoder impl declaring 2 encoders.
 */
public class ValidDualEncoder implements Encoder.Text<Integer>, Encoder.BinaryStream<Long>
{
    @Override
    public void destroy()
    {
    }

    @Override
    public String encode(Integer object) throws EncodeException
    {
        return Integer.toString(object);
    }

    @Override
    public void encode(Long object, OutputStream os) throws EncodeException, IOException
    {
        byte[] b = new byte[8];
        long v = object;
        b[0] = (byte)(v >>> 56);
        b[1] = (byte)(v >>> 48);
        b[2] = (byte)(v >>> 40);
        b[3] = (byte)(v >>> 32);
        b[4] = (byte)(v >>> 24);
        b[5] = (byte)(v >>> 16);
        b[6] = (byte)(v >>> 8);
        b[7] = (byte)(v >>> 0);
        os.write(b, 0, 8);
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
