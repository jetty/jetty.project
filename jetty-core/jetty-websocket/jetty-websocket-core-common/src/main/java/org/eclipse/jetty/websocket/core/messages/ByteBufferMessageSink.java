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
 * passed to the constructor in the form of a {@link ByteBuffer}.</p>
 */
public class ByteBufferMessageSink extends AbstractMessageSink
{
    private BufferAggregator aggregator;

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
            long size = (aggregator == null ? 0 : aggregator.remaining()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize)));
                return;
            }

            // If the frame is fin and no aggregator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (aggregator == null || !aggregator.hasRemaining()))
            {
                try (RetainableByteBuffer buffer = frame.acquire())
                {
                    try (RetainableByteBuffer coalesced = coalesce(buffer))
                    {
                        invoke(getMethodHolder(), coalesced, callback);
                        autoDemand();
                        return;
                    }
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
                    try (RetainableByteBuffer coalesced = coalesce(buffer))
                    {
                        invoke(getMethodHolder(), coalesced, callback);
                        autoDemand();
                    }
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

    /**
     * <p>Returns a buffer whose bytes can be read from a single {@link ByteBuffer}.</p>
     * <p>If the given buffer has multiple byte sources, its bytes are copied into
     * the returned buffer, otherwise the original buffer is returned.</p>
     * <p>The returned buffer must be released.</p>
     * <p>Coalescing is necessary to maintain the semantic that WebSocket messages
     * are delivered via a single {@link ByteBuffer} in a single invocation.</p>
     *
     * @param buffer the buffer to coalesce
     * @return a new coalesced buffer, or the original buffer
     */
    private RetainableByteBuffer coalesce(RetainableByteBuffer buffer)
    {
        long remaining = buffer.remaining();
        boolean coalesce = buffer.read(b -> remaining == b.remaining() ? 0 : -1) < 0;
        if (coalesce)
        {
            RetainableByteBuffer.Mutable copy = getCoreSession().getByteBufferPool().acquire(Math.toIntExact(remaining), false);
            copy.append(buffer);
            return copy;
        }
        else
        {
            buffer.retain();
            return buffer;
        }
    }

    protected void invoke(MethodHolder methodHolder, RetainableByteBuffer buffer, Callback callback) throws Throwable
    {
        // The buffer is coalesced, so buffer.read() is a single invocation.
        // The application is only given the ByteBuffer, not the Callback.
        // Since the invocation is synchronous, we do not need to retain.
        buffer.read(b ->
        {
            int p = b.position();
            int r = b.remaining();
            methodHolder.invoke(b.slice());
            b.position(p + r);
            callback.succeeded();
            return r;
        });
    }
}
