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

package org.eclipse.jetty.websocket.javax.common.messages;

import java.io.ByteArrayOutputStream;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.javax.common.JavaxWebSocketSession;

public class ByteBufferMessageSink extends AbstractMessageSink
{
    private static final int BUFFER_SIZE = 65535;
    private ByteArrayOutputStream out;
    private int size;

    public ByteBufferMessageSink(JavaxWebSocketSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
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

                size += payload.remaining();
                if (session.getMaxBinaryMessageBufferSize() > 0 && size > session.getMaxBinaryMessageBufferSize())
                {
                    throw new MessageTooLargeException(String.format("Binary message too large: (actual) %,d > (configured max binary buffer size) %,d",
                        size, session.getMaxBinaryMessageBufferSize()));
                }

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
