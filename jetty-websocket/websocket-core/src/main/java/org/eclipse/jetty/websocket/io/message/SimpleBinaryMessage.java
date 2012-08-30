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

package org.eclipse.jetty.websocket.io.message;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.driver.EventMethod;
import org.eclipse.jetty.websocket.io.WebSocketSession;

public class SimpleBinaryMessage implements MessageAppender
{
    private final Object websocket;
    private final EventMethod onEvent;
    private final WebSocketSession session;
    private final ByteBufferPool bufferPool;
    private final WebSocketPolicy policy;
    private final ByteBuffer buf;
    private int size;
    private boolean finished;

    public SimpleBinaryMessage(Object websocket, EventMethod onEvent, WebSocketSession session, ByteBufferPool bufferPool, WebSocketPolicy policy)
    {
        this.websocket = websocket;
        this.onEvent = onEvent;
        this.session = session;
        this.bufferPool = bufferPool;
        this.policy = policy;
        this.buf = bufferPool.acquire(policy.getBufferSize(),false);
        BufferUtil.clearToFill(this.buf);
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

        // TODO: grow buffer till max binary message size?
        BufferUtil.put(payload,buf);
    }

    @Override
    public void messageComplete()
    {
        BufferUtil.flipToFlush(this.buf,0);
        finished = true;

        try
        {
            // notify event
            byte data[] = BufferUtil.toArray(this.buf);
            this.onEvent.call(websocket,session,data,0,data.length);
        }
        finally
        {
            // release buffer (we are done with it now)
            bufferPool.release(this.buf);
        }
    }
}
