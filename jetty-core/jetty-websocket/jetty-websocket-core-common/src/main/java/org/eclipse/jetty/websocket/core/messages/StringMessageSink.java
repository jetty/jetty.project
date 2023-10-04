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
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.exception.BadPayloadException;
import org.eclipse.jetty.websocket.core.exception.MessageTooLargeException;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} implementation that accumulates TEXT frames
 * into a message that is then delivered to the application function
 * passed to the constructor in the form of a {@link String}.</p>
 */
public class StringMessageSink extends AbstractMessageSink
{
    private Utf8StringBuilder out;
    private int size;

    /**
     * Creates a new {@link StringMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHolder the application function to invoke when a new message has been assembled
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public StringMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
        this.size = 0;
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            size += frame.getPayloadLength();
            long maxSize = getCoreSession().getMaxTextMessageSize();
            if (maxSize > 0 && size > maxSize)
            {
                callback.failed(new MessageTooLargeException(String.format("Text message too large: %,d > %,d", size, maxSize)));
                return;
            }

            if (out == null)
                out = new Utf8StringBuilder(getCoreSession().getInputBufferSize());

            out.append(frame.getPayload());

            if (frame.isFin())
            {
                getMethodHolder().invoke(out.takeCompleteString(BadPayloadException.InvalidUtf8::new));
                callback.succeeded();
                autoDemand();
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
        finally
        {
            if (frame.isFin())
            {
                size = 0;
                out = null;
            }
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        if (out != null)
            out.reset();
    }
}
