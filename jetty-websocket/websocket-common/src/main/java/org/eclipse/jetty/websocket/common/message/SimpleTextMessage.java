//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.websocket.common.message;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.websocket.common.events.EventDriver;

public class SimpleTextMessage implements MessageAppender
{
    private final EventDriver onEvent;
    protected final Utf8StringBuilder utf;
    private int size = 0;
    protected boolean finished;

    public SimpleTextMessage(EventDriver onEvent)
    {
        this.onEvent = onEvent;
        this.utf = new Utf8StringBuilder(1024);
        size = 0;
        finished = false;
    }

    @Override
    public void appendFrame(ByteBuffer payload, boolean isLast) throws IOException
    {
        if (finished)
        {
            throw new IOException("Cannot append to finished buffer");
        }

        if (payload == null)
        {
            // empty payload is valid
            return;
        }

        onEvent.getPolicy().assertValidTextMessageSize(size + payload.remaining());
        size += payload.remaining();

        // allow for fast fail of BAD utf (incomplete utf will trigger on messageComplete)
        this.utf.append(payload);
    }

    @Override
    public void messageComplete()
    {
        finished = true;

        // notify event
        onEvent.onTextMessage(utf.toString());
    }
}
