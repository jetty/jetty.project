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

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.buffer.RetainableByteBuffer;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} implementation that delivers BINARY frames
 * to the application function passed to the constructor in the form
 * of a {@link ByteBuffer}.</p>
 */
public class PartialByteBufferMessageSink extends AbstractMessageSink
{
    /**
     * Creates a new {@link PartialByteBufferMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHolder the application function to invoke when a new frame has arrived
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public PartialByteBufferMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (frame.hasPayload() || frame.isFin())
            {
                try (RetainableByteBuffer buffer = frame.acquire())
                {
                    invoke(getMethodHolder(), buffer, frame.isFin(), callback);
                    autoDemand();
                }
            }
            else
            {
                callback.succeeded();
                getCoreSession().demand();
            }
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
    }

    /**
     * <p>Invokes the application function once for each {@link ByteBuffer} of the given buffer.</p>
     * <p>The application function is invoked with {@code last=true} only for the
     * {@link ByteBuffer} that contains the last bytes of a {@code fin} frame.</p>
     *
     * @param methodHolder the application function
     * @param buffer the frame payload
     * @param fin whether the frame is the last of the message
     * @param callback the callback to complete when the payload has been processed
     * @throws Throwable if the invocation fails
     */
    protected void invoke(MethodHolder methodHolder, RetainableByteBuffer buffer, boolean fin, Callback callback) throws Throwable
    {
        long remaining = buffer.remaining();
        if (remaining == 0)
        {
            methodHolder.invoke(BufferUtil.EMPTY_BUFFER, fin);
            callback.succeeded();
            return;
        }

        // The buffer may wrap multiple ByteBuffers.
        // The application is only given the ByteBuffer, not the Callback.
        // Since the invocation is synchronous, we do not need to retain.
        long[] total = new long[1];
        buffer.read(b ->
        {
            int p = b.position();
            int r = b.remaining();
            total[0] += r;
            methodHolder.invoke(b.slice(), fin && total[0] == remaining);
            b.position(p + r);
            return r;
        });
        callback.succeeded();
    }
}
