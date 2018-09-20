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
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Objects;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.jsr356.JavaxWebSocketSession;
import org.eclipse.jetty.websocket.jsr356.util.InvalidSignatureException;

public class PartialByteArrayMessageSink extends AbstractMessageSink
{
    public PartialByteArrayMessageSink(JavaxWebSocketSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);

        Objects.requireNonNull(methodHandle, "MethodHandle");
        // byte[] buf, int offset, int length
        MethodType onMessageType = MethodType.methodType(Void.TYPE, byte[].class, int.class, int.class, boolean.class);
        if (methodHandle.type() != onMessageType)
        {
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
        }
    }

    @SuppressWarnings("Duplicates")
    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            byte[] buffer;
            int offset = 0;
            int length = 0;

            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                length = payload.remaining();
                buffer = BufferUtil.toArray(payload);
            }
            else
            {
                buffer = new byte[0];
            }

            methodHandle.invoke(buffer, offset, length, frame.isFin());

            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }
}
