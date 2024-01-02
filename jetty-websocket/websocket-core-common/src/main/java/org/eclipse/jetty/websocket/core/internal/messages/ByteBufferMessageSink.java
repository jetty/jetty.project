//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.websocket.core.internal.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

public class ByteBufferMessageSink extends AbstractMessageSink
{
    private ByteBufferCallbackAccumulator out;

    public ByteBufferMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);
        // Validate onMessage method signature.
        MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class);
        if (methodHandle.type() != onMessageType)
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            long size = (out == null ? 0 : out.getLength()) + frame.getPayloadLength();
            long maxBinaryMessageSize = session.getMaxBinaryMessageSize();
            if (maxBinaryMessageSize > 0 && size > maxBinaryMessageSize)
            {
                throw new MessageTooLargeException(String.format("Binary message too large: (actual) %,d > (configured max binary message size) %,d",
                    size, maxBinaryMessageSize));
            }

            // If the frame is fin and no accumulator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (out == null || out.getLength() == 0))
            {
                if (frame.hasPayload())
                    methodHandle.invoke(frame.getPayload());
                else
                    methodHandle.invoke(BufferUtil.EMPTY_BUFFER);

                callback.succeeded();
                session.demand(1);
                return;
            }

            // Aggregate the frame payload.
            if (frame.hasPayload())
            {
                ByteBuffer payload = frame.getPayload();
                if (out == null)
                    out = new ByteBufferCallbackAccumulator();
                out.addEntry(payload, callback);
                // The callback is now stored in the accumulator, so if
                // the methodHandle throws, don't fail the callback twice.
                callback = Callback.NOOP;
            }

            if (frame.isFin())
            {
                ByteBufferPool bufferPool = session.getByteBufferPool();
                ByteBuffer buffer = bufferPool.acquire(out.getLength(), false);
                out.writeTo(buffer);

                try
                {
                    methodHandle.invoke(buffer);
                }
                finally
                {
                    bufferPool.release(buffer);
                }
            }

            callback.succeeded();
            session.demand(1);
        }
        catch (Throwable t)
        {
            fail(t);
            callback.failed(t);
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        if (out != null)
            out.fail(failure);
    }
}
