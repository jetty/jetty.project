//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.io.message;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.core.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.core.driver.EventMethod;
import org.eclipse.jetty.websocket.core.io.WebSocketSession;

public class SimpleBinaryMessage implements MessageAppender
{
    private static final int BUFFER_SIZE = 65535;
    private final Object websocket;
    private final EventMethod onEvent;
    private final WebSocketSession session;
    private final WebSocketPolicy policy;
    private final ByteArrayOutputStream out;
    private int size;
    private boolean finished;

    public SimpleBinaryMessage(Object websocket, EventMethod onEvent, WebSocketSession session, WebSocketPolicy policy)
    {
        this.websocket = websocket;
        this.onEvent = onEvent;
        this.session = session;
        this.policy = policy;
        this.out = new ByteArrayOutputStream(BUFFER_SIZE);
        finished = false;
    }

    @Override
    public void appendMessage(ByteBuffer payload) throws IOException
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

        policy.assertValidBinaryMessageSize(size + payload.remaining());
        size += payload.remaining();

        BufferUtil.writeTo(payload,out);
    }

    @Override
    public void messageComplete()
    {
        finished = true;
        byte data[] = out.toByteArray();
        this.onEvent.call(websocket,session,data,0,data.length);
    }
}
