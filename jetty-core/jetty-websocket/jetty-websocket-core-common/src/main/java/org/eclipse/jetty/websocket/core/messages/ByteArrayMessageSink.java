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

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.BufferAggregator;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} implementation that accumulates BINARY frames
 * into a message that is then delivered to the application function
 * passed to the constructor in the form of a {@code byte[]}.</p>
 */
public class ByteArrayMessageSink extends AbstractMessageSink
{
    private BufferAggregator aggregator;

    /**
     * Creates a new {@link ByteArrayMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHolder the application function to invoke when a new message has been assembled
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public ByteArrayMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            long size = (aggregator == null ? 0 : aggregator.remaining()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
                throw new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize));

            // If the frame is fin and no accumulator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (aggregator == null || !aggregator.hasRemaining()))
            {
                try (RetainableByteBuffer buffer = frame.acquire())
                {
                    byte[] bytes = buffer.getArray();
                    getMethodHolder().invoke(bytes, 0, bytes.length);
                    callback.succeeded();
                    autoDemand();
                    return;
                }
            }

            if (!frame.isFin() && !frame.hasPayload())
            {
                callback.succeeded();
                getCoreSession().demand();
                return;
            }

            if (aggregator == null)
            {
                int initial = maxSize > 0 ? (int)Math.min(1024, maxSize) : 1024;
                aggregator = new BufferAggregator(getCoreSession().getByteBufferPool(), false, true, initial, maxSize);
            }

            try (RetainableByteBuffer buffer = frame.acquire())
            {
                aggregator.append(buffer);
                callback.succeeded();
                // Do not complete twice the callback if the invocation fails.
                callback = Callback.NOOP;
            }

            if (frame.isFin())
            {
                try (RetainableByteBuffer buffer = aggregator.take())
                {
                    byte[] bytes = buffer.getArray();
                    getMethodHolder().invoke(bytes, 0, bytes.length);
                    autoDemand();
                }
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
        if (aggregator != null)
            aggregator.clear();
    }
}
