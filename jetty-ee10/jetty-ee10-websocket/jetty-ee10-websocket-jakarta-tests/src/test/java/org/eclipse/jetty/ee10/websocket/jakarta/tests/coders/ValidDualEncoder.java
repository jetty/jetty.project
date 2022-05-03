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

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;

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
        data.writeByte((byte)'[');
        data.writeLong(object);
        data.writeByte((byte)']');
        data.flush();
    }

    @Override
    public void init(EndpointConfig config)
    {
    }
}
