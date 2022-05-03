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

package org.eclipse.jetty.ee9.websocket.jakarta.common.decoders;

import java.nio.ByteBuffer;

import jakarta.websocket.DecodeException;
import jakarta.websocket.Decoder;
import org.eclipse.jetty.util.BufferUtil;

public class ByteArrayDecoder extends AbstractDecoder implements Decoder.Binary<byte[]>
{
    public static final ByteArrayDecoder INSTANCE = new ByteArrayDecoder();

    @Override
    public byte[] decode(ByteBuffer bytes) throws DecodeException
    {
        return BufferUtil.toArray(bytes);
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        return true;
    }
}
