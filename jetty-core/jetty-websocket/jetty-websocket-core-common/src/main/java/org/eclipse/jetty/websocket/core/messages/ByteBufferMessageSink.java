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

import java.nio.ByteBuffer;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} implementation that accumulates BINARY frames
 * into a message that is then delivered to the application function
 * passed to the constructor in the form of a {@link ByteBuffer}.</p>
 */
public class ByteBufferMessageSink extends AbstractMessageSink
{
    private RetainableByteBuffer.DynamicCapacity accumulator;

    /**
     * Creates a new {@link ByteBufferMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHolder the application function to invoke when a new message has been assembled
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public ByteBufferMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            long size = (accumulator == null ? 0 : accumulator.size()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                MessageTooLargeException failure = new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize));
                fail(failure);
                callback.failed(failure);
                return;
            }

            // If the frame is fin and no accumulator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (accumulator == null || accumulator.isEmpty()))
            {
                invoke(getMethodHolder(), frame.getPayload(), callback);
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
                accumulator = new RetainableByteBuffer.DynamicCapacity(getCoreSession().getByteBufferPool(), frame.getPayload().isDirect(), -1L);
            RetainableByteBuffer.Mutable rbb = RetainableByteBuffer.wrap(frame.getPayload(), callback::succeeded);
            // Since the accumulator has an unlimited max size, append() will never return false
            // so there is no need to check its return value.
            accumulator.append(rbb);
            rbb.release();

            if (frame.isFin())
            {
                RetainableByteBuffer buffer = accumulator.take();
                callback = Callback.from(buffer::release);
                invoke(getMethodHolder(), buffer.getByteBuffer(), callback);
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
        {
            accumulator.release();
            accumulator = null;
        }
    }

    protected void invoke(MethodHolder methodHolder, ByteBuffer byteBuffer, Callback callback) throws Throwable
    {
        methodHolder.invoke(byteBuffer);
        callback.succeeded();
    }
}
