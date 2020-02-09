//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.concurrent.Executor;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ByteArrayOutputStream2;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.common.AbstractMessageSink;
import org.eclipse.jetty.websocket.common.invoke.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

public class ByteArrayMessageSink extends AbstractMessageSink
{
    private static final byte[] EMPTY_BUFFER = new byte[0];
    private static final int BUFFER_SIZE = 65535;
    private final Session session;
    private ByteArrayOutputStream2 out;
    private int size;

    public ByteArrayMessageSink(Executor executor, MethodHandle methodHandle, Session session)
    {
        super(executor, methodHandle);
        this.session = session;

        Objects.requireNonNull(methodHandle, "MethodHandle");
        // byte[] buf, int offset, int length
        MethodType onMessageType = MethodType.methodType(Void.TYPE, byte[].class, int.class, int.class);
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
                size = size + payload.remaining();
                long maxMessageSize = session.getMaxBinaryMessageSize();
                if (maxMessageSize > 0 && size > maxMessageSize)
                    throw new MessageTooLargeException("Message size [" + size + "] exceeds maximum size [" + maxMessageSize + "]");

                if (out == null)
                    out = frame.isFin() ? new ByteArrayOutputStream2(size) : new ByteArrayOutputStream2(BUFFER_SIZE);

                BufferUtil.writeTo(payload, out);
            }

            if (frame.isFin())
            {
                if (out != null)
                {
                    byte[] buf;
                    if (out.getCount() == out.getBuf().length)
                        buf = out.getBuf();
                    else
                        buf = out.toByteArray();
                    methodHandle.invoke(buf, 0, buf.length);
                }
                else
                    methodHandle.invoke(EMPTY_BUFFER, 0, 0);
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
