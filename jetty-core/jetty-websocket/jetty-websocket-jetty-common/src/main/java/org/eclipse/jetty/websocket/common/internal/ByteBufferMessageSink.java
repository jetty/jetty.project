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

package org.eclipse.jetty.websocket.common.internal;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.websocket.api.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.messages.AbstractMessageSink;

public class ByteBufferMessageSink extends AbstractMessageSink
{
    private ByteBufferCallbackAccumulator accumulator;

    public ByteBufferMessageSink(CoreSession session, MethodHandle methodHandle)
    {
        super(session, methodHandle);

        MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class, Callback.class);
        if (methodHandle.type() != onMessageType)
            throw InvalidSignatureException.build(onMessageType, methodHandle.type());
    }

    @Override
    public void accept(Frame frame, org.eclipse.jetty.util.Callback callback)
    {
        try
        {
            long size = (accumulator == null ? 0 : accumulator.getLength()) + frame.getPayloadLength();
            long maxSize = session.getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
                throw new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize));

            ByteBuffer payload = frame.getPayload();
            if (frame.isFin() && accumulator == null)
            {
                methodHandle.invoke(payload, Callback.from(callback::succeeded, callback::failed));
                return;
            }

            if (accumulator == null)
                accumulator = new ByteBufferCallbackAccumulator();
            accumulator.addEntry(payload, callback);

            if (frame.isFin())
            {
                ByteBufferPool bufferPool = session.getByteBufferPool();
                RetainableByteBuffer buffer = bufferPool.acquire(accumulator.getLength(), payload.isDirect());
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                accumulator.writeTo(byteBuffer);
                methodHandle.invoke(byteBuffer, new ReleaseCallback(buffer));
            }
        }
        catch (Throwable x)
        {
            if (accumulator != null)
                accumulator.fail(x);
            callback.failed(x);
        }
        finally
        {
            if (frame.isFin())
                accumulator = null;
        }
    }

    private record ReleaseCallback(RetainableByteBuffer buffer) implements Callback
    {
        @Override
        public void succeed()
        {
            buffer.release();
        }

        @Override
        public void fail(Throwable x)
        {
            buffer.release();
        }
    }
}
