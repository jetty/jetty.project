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

package org.eclipse.jetty.websocket.tests.jsr356.coders;

import java.io.DataOutputStream;
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
        return String.format("[%,d]", object);
    }

    @Override
    public void encode(Long object, OutputStream os) throws EncodeException, IOException
    {
        DataOutputStream data = new DataOutputStream(os);
        data.writeByte((byte) '[');
        data.writeLong(object);
        data.writeByte((byte) ']');
        data.flush();
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
