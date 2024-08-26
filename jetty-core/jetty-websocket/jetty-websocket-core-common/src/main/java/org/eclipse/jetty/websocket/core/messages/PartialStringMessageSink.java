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
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} implementation that delivers TEXT frames
 * to the application function passed to the constructor in the form
 * of a {@link String}.</p>
 */
public class PartialStringMessageSink extends AbstractMessageSink
{
    private Utf8StringBuilder accumulator;

    /**
     * Creates a new {@link PartialStringMessageSink}.
     *
     * @param session the WebSocket session
     * @param methodHolder the application function to invoke when a new frame has arrived
     * @param autoDemand whether this {@link MessageSink} manages demand automatically
     */
    public PartialStringMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        try
        {
            if (accumulator == null)
                accumulator = new Utf8StringBuilder(getCoreSession().getInputBufferSize());

            accumulator.append(frame.getPayload());

            if (frame.isFin())
            {
                String complete = accumulator.takeCompleteString(BadPayloadException.InvalidUtf8::new);
                getMethodHolder().invoke(complete, true);
            }
            else
            {
                String partial = accumulator.takePartialString(BadPayloadException.InvalidUtf8::new);
                getMethodHolder().invoke(partial, false);
            }

            callback.succeeded();

            autoDemand();
        }
        catch (Throwable t)
        {
            callback.failed(t);
        }
        finally
        {
            if (frame.isFin())
                accumulator = null;
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        if (accumulator != null)
            accumulator.reset();
    }
}
