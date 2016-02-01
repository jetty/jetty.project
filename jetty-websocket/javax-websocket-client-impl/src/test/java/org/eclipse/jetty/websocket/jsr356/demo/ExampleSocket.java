//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.demo;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;

import javax.websocket.ClientEndpoint;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;

@ClientEndpoint
public class ExampleSocket
{
    private Session session;
    public CountDownLatch messageLatch = new CountDownLatch(1);
    public CountDownLatch closeLatch = new CountDownLatch(1);

    @OnClose
    public void onClose(CloseReason close)
    {
        System.out.printf("Closed: %d, \"%s\"%n",close.getCloseCode().getCode(),close.getReasonPhrase());
        closeLatch.countDown();
    }

    @OnMessage
    public void onMessage(String message)
    {
        System.out.printf("Received: \"%s\"%n",message);
        messageLatch.countDown();
    }

    @OnOpen
    public void onOpen(Session session)
    {
        System.out.printf("Opened%n");
        this.session = session;
    }

    public void writeMessage(String message)
    {
        System.out.printf("Writing: \"%s\"%n",message);
        try
        {
            session.getBasicRemote().sendText(message);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }
}
