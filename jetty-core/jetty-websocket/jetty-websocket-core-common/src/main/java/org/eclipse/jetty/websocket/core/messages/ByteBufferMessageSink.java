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

package org.eclipse.jetty.websocket.core.messages;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferCallbackAccumulator;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.InvalidSignatureException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;

/**
 * <p>A {@link MessageSink} implementation that accumulates BINARY frames
 * into a message that is then delivered to the application function
 * passed to the constructor in the form of a {@link ByteBuffer}.</p>
 */
public class ByteBufferMessageSink extends AbstractMessageSink
{
    private ByteBufferCallbackAccumulator accumulator;

    /**
     * Creates a new {@link ByteBufferMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHandle the application function to invoke when a new message has been assembled
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public ByteBufferMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand)
    {
        this(session, methodHandle, autoDemand, true);
    }

    protected ByteBufferMessageSink(CoreSession session, MethodHandle methodHandle, boolean autoDemand, boolean validateSignature)
    {
        super(session, methodHandle, autoDemand);

        if (validateSignature)
        {
            MethodType onMessageType = MethodType.methodType(Void.TYPE, ByteBuffer.class);
            if (methodHandle.type() != onMessageType)
                throw InvalidSignatureException.build(onMessageType, methodHandle.type());
        }
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            long size = (accumulator == null ? 0 : accumulator.getLength()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize)));
                return;
            }

            if (frame.isFin() && accumulator == null)
            {
                invoke(getMethodHandle(), frame.getPayload(), callback);
                autoDemand();
                return;
            }

            if (!frame.isFin() && !frame.hasPayload())
            {
                callback.succeeded();
                getCoreSession().demand(1);
                return;
            }

            if (accumulator == null)
                accumulator = new ByteBufferCallbackAccumulator();
            accumulator.addEntry(frame.getPayload(), callback);

            if (frame.isFin())
            {
                ByteBufferPool bufferPool = getCoreSession().getByteBufferPool();
                RetainableByteBuffer buffer = bufferPool.acquire(accumulator.getLength(), false);
                ByteBuffer byteBuffer = buffer.getByteBuffer();
                accumulator.writeTo(byteBuffer);
                callback = Callback.from(buffer::release);
                invoke(getMethodHandle(), byteBuffer, callback);
                autoDemand();
            }
            else
            {
                // Did not call the application so must explicitly demand here.
                getCoreSession().demand(1);
            }
        }
        catch (Throwable t)
        {
            if (accumulator != null)
                accumulator.fail(t);
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                accumulator = null;
        }
    }

    protected void invoke(MethodHandle methodHandle, ByteBuffer byteBuffer, Callback callback) throws Throwable
    {
        methodHandle.invoke(byteBuffer);
        callback.succeeded();
    }
}
