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

public class ByteBufferDecoder extends AbstractDecoder implements Decoder.Binary<ByteBuffer>
{
    public static final ByteBufferDecoder INSTANCE = new ByteBufferDecoder();

    @Override
    public ByteBuffer decode(ByteBuffer bytes) throws DecodeException
    {
        return bytes;
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        return true;
    }
}
