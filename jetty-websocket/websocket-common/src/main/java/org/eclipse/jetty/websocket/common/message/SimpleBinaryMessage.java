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

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.websocket.common.events.EventDriver;

public class SimpleBinaryMessage implements MessageAppender
{
    private static final int BUFFER_SIZE = 65535;
    private final EventDriver onEvent;
    protected ByteArrayOutputStream2 out;
    private int size;
    protected boolean finished;

    public SimpleBinaryMessage(EventDriver onEvent)
    {
        this.onEvent = onEvent;
        finished = false;
    }

    @Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
        if (finished)
        {
            throw new IOException("Cannot append to finished buffer");
        }

        if (payload == null)
        {
            // empty payload is valid
            return;
        }

        onEvent.getPolicy().assertValidBinaryMessageSize(size + payload.remaining());
        size += payload.remaining();

        if (out == null)
            out = isLast ? new ByteArrayOutputStream2(size) : new ByteArrayOutputStream2(BUFFER_SIZE);
        BufferUtil.writeTo(payload, out);
    }

    @Override
    public void messageComplete()
    {
        finished = true;
        byte[] data;
        if (out == null)
            data = new byte[]{};
        else if (out.getCount() == out.getBuf().length)
            data = out.getBuf();
        else
            data = out.toByteArray();
        onEvent.onBinaryMessage(data);
    }
}
