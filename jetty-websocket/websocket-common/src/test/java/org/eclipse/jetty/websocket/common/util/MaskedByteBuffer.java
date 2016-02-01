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

package org.eclipse.jetty.websocket.common.util;

import java.nio.ByteBuffer;

public class MaskedByteBuffer
{
    private static byte[] mask = new byte[]
            { 0x00, (byte)0xF0, 0x0F, (byte)0xFF };

    public static void putMask(ByteBuffer buffer)
    {
        buffer.put(mask,0,mask.length);
    }

    public static void putPayload(ByteBuffer buffer, byte[] payload)
    {
        int len = payload.length;
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte)(payload[i] ^ mask[i % 4]));
        }
    }

    public static void putPayload(ByteBuffer buffer, ByteBuffer payload)
    {
        int len = payload.remaining();
        for (int i = 0; i < len; i++)
        {
            buffer.put((byte)(payload.get() ^ mask[i % 4]));
        }
    }
}
