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
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.ByteBufferPool;
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
    private Accumulator accumulator;

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
            long size = (accumulator == null ? 0 : accumulator.getLength()) + frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxBinaryMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Binary message too large: %,d > %,d", size, maxSize)));
                return;
            }

            // If the frame is fin and no accumulator has been
            // created or used, then we don't need to aggregate.
            if (frame.isFin() && (accumulator == null || accumulator.getLength() == 0))
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
                accumulator = new Accumulator();
            accumulator.addEntry(frame.getPayload(), callback);

            if (frame.isFin())
            {
                ByteBufferPool bufferPool = getCoreSession().getByteBufferPool();
                RetainableByteBuffer.Mutable buffer = bufferPool.acquire(accumulator.getLength(), false);
                accumulator.writeTo(buffer);
                callback = Callback.from(buffer::release);
                invoke(getMethodHolder(), buffer, callback);
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
            accumulator.fail(failure);
    }

    private void invoke(MethodHolder methodHolder, RetainableByteBuffer buffer, Callback callback) throws Throwable
    {
        try
        {
            buffer.quietRead(byteBuffer ->
            {
                try
                {
                    invoke(methodHolder, byteBuffer, callback);
                    return 0;
                }
                catch (Throwable x)
                {
                    throw new RuntimeException(x);
                }
            });
        }
        catch (RuntimeException x)
        {
            throw x.getCause();
        }
    }

    protected void invoke(MethodHolder methodHolder, ByteBuffer byteBuffer, Callback callback) throws Throwable
    {
        methodHolder.invoke(byteBuffer);
        callback.succeeded();
    }

    private static class Accumulator
    {
        private final List<Entry> _entries = new ArrayList<>();
        private int _length;

        private void addEntry(ByteBuffer buffer, Callback callback)
        {
            _entries.add(new Entry(buffer, callback));
            _length = Math.addExact(_length, buffer.remaining());
        }

        private int getLength()
        {
            return _length;
        }

        private void writeTo(RetainableByteBuffer.Mutable buffer)
        {
            if (buffer.space() < _length)
                throw new IllegalArgumentException("not enough buffer space remaining");

            for (Entry entry : _entries)
            {
                buffer.put(entry.buffer());
                entry.callback().succeeded();
            }
            _entries.clear();
            _length = 0;
        }

        private void fail(Throwable t)
        {
            // In some usages the callback recursively fails the accumulator.
            // So we copy and clear to avoid double completing the callback.
            List<Entry> entries = new ArrayList<>(_entries);
            _entries.clear();
            _length = 0;
            entries.forEach(e -> e.callback().failed(t));
        }

        private record Entry(ByteBuffer buffer, Callback callback)
        {
        }
    }
}
