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

package org.eclipse.jetty.websocket.jsr356.decoders;

import java.nio.ByteBuffer;
import javax.websocket.DecodeException;
import javax.websocket.Decoder;
import javax.websocket.PongMessage;

import org.eclipse.jetty.util.BufferUtil;

public class PongMessageDecoder extends AbstractDecoder implements Decoder.Binary<PongMessage>
{
    private static class PongMsg implements PongMessage
    {
        private final ByteBuffer bytes;

        public PongMsg(ByteBuffer buf)
        {
            int len = buf.remaining();
            this.bytes = ByteBuffer.allocate(len);
            BufferUtil.put(buf, this.bytes);
            BufferUtil.flipToFlush(this.bytes, 0);
        }

        @Override
        public ByteBuffer getApplicationData()
        {
            return this.bytes;
        }
    }

    @Override
    public PongMessage decode(ByteBuffer bytes) throws DecodeException
    {
        return new PongMsg(bytes);
    }

    @Override
    public boolean willDecode(ByteBuffer bytes)
    {
        return true;
    }
}
