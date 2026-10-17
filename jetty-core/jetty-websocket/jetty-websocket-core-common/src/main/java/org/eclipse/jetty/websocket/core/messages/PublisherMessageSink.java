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

import java.util.concurrent.Flow;

import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.CoreSession;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

/**
 * <p>A {@link MessageSink} for application methods that consume a WebSocket
 * BINARY message asynchronously, via a {@link Flow.Publisher} of the frame
 * payloads of the message.</p>
 * <p>The first frame of a message creates a {@link MessagePublisher}, that is
 * passed to the application method, which is invoked in the thread that
 * received the frame; the frames of the message are then fed to the
 * publisher.</p>
 * <p>The application method must subscribe to the publisher, either before
 * returning or asynchronously after returning, since the frames of the
 * message are demanded only when the subscriber requests them.</p>
 * <p>The demand for the next WebSocket message is performed when the last
 * frame of the message has been consumed, either by publishing it to the
 * subscriber or by discarding it after the subscription has been cancelled,
 * therefore this {@link MessageSink} must always be auto-demanding.</p>
 * <p>Throwing from the application method, or from the subscriber, fails the
 * message and results in the WebSocket connection to be closed.</p>
 */
public class PublisherMessageSink extends AbstractMessageSink
{
    private MessagePublisher publisher;

    public PublisherMessageSink(CoreSession session, MethodHolder methodHolder, boolean autoDemand)
    {
        super(session, methodHolder, autoDemand);
        if (!autoDemand)
            throw new IllegalArgumentException("%s must be auto-demanding".formatted(getClass().getSimpleName()));
    }

    @Override
    public void accept(Frame frame, Callback callback)
    {
        MessagePublisher publisher = this.publisher;
        boolean first = publisher == null;
        if (first)
        {
            publisher = new MessagePublisher(getCoreSession());
            this.publisher = publisher;
        }

        Callback frameCallback = callback;
        if (frame.isFin())
        {
            // The message is consumed when the last
            // frame is, then demand the next message.
            frameCallback = Callback.from(() ->
            {
                this.publisher = null;
                callback.succeeded();
                autoDemand();
            }, x ->
            {
                this.publisher = null;
                callback.failed(x);
            });
        }
        publisher.accept(frame, frameCallback);

        if (first)
        {
            try
            {
                getMethodHolder().invoke(publisher);
            }
            catch (Throwable x)
            {
                publisher.fail(x);
            }
        }
    }

    @Override
    public void fail(Throwable failure)
    {
        MessagePublisher publisher = this.publisher;
        if (publisher != null)
            publisher.fail(failure);
    }
}
