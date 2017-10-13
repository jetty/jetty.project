//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core.example;

import java.nio.ByteBuffer;

import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.WebSocketCoreSession;
import org.eclipse.jetty.websocket.core.WebSocketLocalEndpoint;

class ExampleLocalEndpoint implements WebSocketLocalEndpoint.Adaptor
{
    private WebSocketCoreSession session;

    @Override
    public void onOpen()
    {
        ExampleWebSocketHandler.LOG.debug("onOpen {}", this);
        session.getRemote().sendText("Opened!", new Callback()
        {
            @Override
            public void succeeded()
            {
                ExampleWebSocketHandler.LOG.debug("onOpen write!");
            }

            @Override
            public void failed(Throwable x)
            {
                ExampleWebSocketHandler.LOG.warn(x);
            }
        });
    }

    @Override
    public void onText(Frame frame, Callback callback)
    {
        ByteBuffer payload = frame.getPayload();
        String text = BufferUtil.toUTF8String(payload);

        ExampleWebSocketHandler.LOG.debug("onText {} / {}",text,frame);
        session.getRemote().sendText("echo: "+text, callback);
    }

    public void setSession(WebSocketCoreSession session)
    {
        this.session = session;
    }
}
