//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.common.AbstractMessageSink;
import org.eclipse.jetty.websocket.common.invoke.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.MessageTooLargeException;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

public class ByteBufferMessageSink extends AbstractMessageSink
{
    private static final int BUFFER_SIZE = 65535;
    private final long maxMessageSize;
    private ByteArrayOutputStream out;
    private int size;

    public ByteBufferMessageSink(Executor executor, MethodHandle methodHandle, long maxMessageSize)
    {
        super(executor, methodHandle);
        this.maxMessageSize = maxMessageSize;

        // Validate onMessageMethod
        Objects.requireNonNull(methodHandle, "MethodHandle");
        MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class);
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
            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                int nextSize = size + payload.remaining();
                if (maxMessageSize > 0 && size > maxMessageSize)
                    throw new MessageTooLargeException("Message size [" + size + "] exceeds maximum size [" + maxMessageSize + "]");
                size = nextSize;

                if (out == null)
                    out = new ByteArrayOutputStream(BUFFER_SIZE);

                BufferUtil.writeTo(payload, out);
                payload.position(payload.limit()); // consume buffer
            }

            if (frame.isFin())
            {
                if (out != null)
                    methodHandle.invoke(ByteBuffer.wrap(out.toByteArray()));
                else
                    methodHandle.invoke(BufferUtil.EMPTY_BUFFER);
            }

            callback.succeeded();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
            {
                // reset
                out = null;
                size = 0;
            }
        }
    }
}
