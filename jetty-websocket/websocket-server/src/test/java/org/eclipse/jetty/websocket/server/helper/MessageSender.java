// ========================================================================
// Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
//
//     The Eclipse Public License is available at
//     http://www.eclipse.org/legal/epl-v10.html
//
//     The Apache License v2.0 is available at
//     http://www.opensource.org/licenses/apache2.0.php
//
// You may elect to redistribute this code under either of these licenses.
//========================================================================
package org.eclipse.jetty.websocket.server.helper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.api.WebSocketConnection;

public class MessageSender extends WebSocketAdapter
{
    private CountDownLatch connectLatch = new CountDownLatch(1);
    private int closeCode = -1;
    private String closeMessage = null;

    public void awaitConnect() throws InterruptedException
    {
        connectLatch.await(1,TimeUnit.SECONDS);
    }

    public void close()
    {
        try
        {
            getConnection().close(StatusCode.NORMAL,null);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    public int getCloseCode()
    {
        return closeCode;
    }

    public String getCloseMessage()
    {
        return closeMessage;
    }

    @Override
    public void onWebSocketClose(int statusCode, String reason)
    {
        this.closeCode = statusCode;
        this.closeMessage = reason;
    }

    @Override
    public void onWebSocketConnect(WebSocketConnection connection)
    {
        super.onWebSocketConnect(connection);
        connectLatch.countDown();
    }

    public void sendMessage(String format, Object... args) throws IOException
    {
        getBlockingConnection().write(String.format(format,args));
    }
}