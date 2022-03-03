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

package org.eclipse.jetty.ee9.websocket.jakarta.common.encoders;

import java.nio.ByteBuffer;

import jakarta.websocket.EncodeException;
import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;

public class ByteArrayEncoder implements Encoder.Binary<byte[]>
{
    @Override
    public void destroy()
    {
        /* do nothing */
    }

    @Override
    public ByteBuffer encode(byte[] object) throws EncodeException
    {
        return ByteBuffer.wrap(object);
    }

    @Override
    public void init(EndpointConfig config)
    {
        /* do nothing */
    }
}
