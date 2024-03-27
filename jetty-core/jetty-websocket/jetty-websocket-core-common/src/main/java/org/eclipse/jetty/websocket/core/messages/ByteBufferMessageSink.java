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
    private RetainableByteBuffer.Mutable.Accumulator accumulator;

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
            int size = Math.addExact(accumulator == null ? 0 : Math.toIntExact(accumulator.remaining()), frame.getPayloadLength());
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize)));
                return;
            }

            // If the frame is fin and no accumulator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (accumulator == null || accumulator.remaining() == 0))
            {
                invoke(getMethodHandle(), frame.getPayload(), callback);
                autoDemand();
                return;
            }

            if (!frame.isFin() && !frame.hasPayload())
            {
                callback.succeeded();
                getCoreSession().demand();
                return;
            }

            if (accumulator == null)
                accumulator = new RetainableByteBuffer.Mutable.Accumulator(getCoreSession().getByteBufferPool(), false, maxSize);
            RetainableByteBuffer wrappedPayload = RetainableByteBuffer.wrap(frame.getPayload(), callback::succeeded);
            accumulator.append(wrappedPayload);
            wrappedPayload.release();

            if (frame.isFin())
            {
                callback = Callback.from(accumulator::release);
                invoke(getMethodHandle(), accumulator.getByteBuffer(), callback);
                autoDemand();
            }
            else
            {
                // Did not call the application so must explicitly demand here.
                getCoreSession().demand();
            }
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
        if (accumulator != null)
            accumulator.clear();
    }

    protected void invoke(MethodHandle methodHandle, ByteBuffer byteBuffer, Callback callback) throws Throwable
    {
        methodHandle.invoke(byteBuffer);
        callback.succeeded();
    }
}
