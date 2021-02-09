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

package org.eclipse.jetty.websocket.common.io.payload;

import java.nio.ByteBuffer;

import org.eclipse.jetty.websocket.api.extensions.Frame;

public class DeMaskProcessor implements PayloadProcessor
{
    private byte[] maskBytes;
    private int maskInt;
    private int maskOffset;

    @Override
    public void process(ByteBuffer payload)
    {
        if (maskBytes == null)
        {
            return;
        }

        int maskInt = this.maskInt;
        int start = payload.position();
        int end = payload.limit();
        int offset = this.maskOffset;
        int remaining;
        while ((remaining = end - start) > 0)
        {
            if (remaining >= 4 && (offset & 3) == 0)
            {
                payload.putInt(start, payload.getInt(start) ^ maskInt);
                start += 4;
                offset += 4;
            }
            else
            {
                payload.put(start, (byte)(payload.get(start) ^ maskBytes[offset & 3]));
                ++start;
                ++offset;
            }
        }
        maskOffset = offset;
    }

    public void reset(byte[] mask)
    {
        this.maskBytes = mask;
        int maskInt = 0;
        if (mask != null)
        {
            for (byte maskByte : mask)
            {
                maskInt = (maskInt << 8) + (maskByte & 0xFF);
            }
        }
        this.maskInt = maskInt;
        this.maskOffset = 0;
    }

    @Override
    public void reset(Frame frame)
    {
        reset(frame.getMask());
    }
}
