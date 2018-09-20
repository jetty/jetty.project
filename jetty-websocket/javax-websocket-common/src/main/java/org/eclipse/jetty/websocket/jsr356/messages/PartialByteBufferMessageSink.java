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

package org.eclipse.jetty.websocket.jsr356.messages;

import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;

public class PartialByteBufferMessageSink extends AbstractMessageSink
{
    public PartialByteBufferMessageSink(JavaxWebSocketSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            ByteBuffer buffer;

            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                // copy buffer here
                buffer = ByteBuffer.allocate(payload.remaining());
                BufferUtil.clearToFill(buffer);
                BufferUtil.put(payload, buffer);
                BufferUtil.flipToFlush(buffer, 0);
            }
            else
            {
                buffer = BufferUtil.EMPTY_BUFFER;
            }

            methodHandle.invoke(buffer, frame.isFin());

            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
