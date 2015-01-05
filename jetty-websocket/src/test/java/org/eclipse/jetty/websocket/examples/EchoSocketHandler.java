//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.examples;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.WebSocket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

public class EchoSocketHandler implements WebSocket, WebSocket.OnTextMessage
{
    private static final Logger LOG = Log.getLogger(EchoSocketHandler.class);

    public CountDownLatch connectLatch = new CountDownLatch(1);
    public CountDownLatch disconnectLatch = new CountDownLatch(1);

    public List<String> textMessages = new ArrayList<String>();
    public List<ByteBuffer> binaryMessages = new ArrayList<ByteBuffer>();
    public List<Throwable> errors = new ArrayList<Throwable>();
    private Connection connection;

    @Override
    public void onMessage(String message)
    {
        LOG.info("on Text : {}",message);
        textMessages.add(message);
        connection.close();
    }

    @Override
    public void onOpen(Connection connection)
    {
        this.connection = connection;
        LOG.info("Connected to {}",connection);
        connectLatch.countDown();
        try
        {
            connection.sendMessage("Hello WebSocket World");
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    @Override
    public void onClose(int closeCode, String message)
    {
        LOG.info("Closed {} : {}",closeCode,message);
        disconnectLatch.countDown();
    }
}