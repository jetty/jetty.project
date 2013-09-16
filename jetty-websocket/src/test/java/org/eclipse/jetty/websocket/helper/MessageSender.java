//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.helper;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.WebSocket;

public class MessageSender implements WebSocket, WebSocket.OnTextMessage
{
    private Connection conn;
    private CountDownLatch connectLatch = new CountDownLatch(1);
    private CountDownLatch messageLatch = new CountDownLatch(1);

    private int closeCode = -1;
    private String closeMessage = null;
    private String message = null;
    
    
    public void onOpen(Connection connection)
    {
        this.conn = connection;
        connectLatch.countDown();
    }

    public void onClose(int closeCode, String message)
    {
        this.conn = null;
        this.closeCode = closeCode;
        this.closeMessage = message;
    }
    
    
    public void onMessage(String data)
    {
        message = data;
    }

    public boolean isConnected()
    {
        if (this.conn == null)
        {
            return false;
        }
        return this.conn.isOpen();
    }
    
    public int getCloseCode()
    {
        return closeCode;
    }
    
    public String getCloseMessage()
    {
        return closeMessage;
    }
    
    public String getMessage()
    {
        return message;
    }

    public void sendMessage(String format, Object... args) throws IOException
    {
        this.conn.sendMessage(String.format(format,args));
    }

    public void awaitConnect() throws InterruptedException
    {
        connectLatch.await(1,TimeUnit.SECONDS);
    }
    
    public void awaitMessage() throws InterruptedException
    {
        messageLatch.await(1,TimeUnit.SECONDS);
    }

    public void close()
    {
        if (this.conn == null)
        {
            return;
        }
        this.conn.close();
    }
}