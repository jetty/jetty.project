//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.tests.client;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.jsr356.tests.DataUtils;
import org.eclipse.jetty.websocket.jsr356.tests.WSEventTracker;

@ClientEndpoint
public class JsrClientTrackingSocket extends WSEventTracker.Basic
{
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> pongQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    
    public JsrClientTrackingSocket()
    {
        super("@ClientEndpoint");
    }
    
    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public void onText(String msg)
    {
        messageQueue.offer(msg);
    }
    
    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public void onBinary(ByteBuffer buffer)
    {
        ByteBuffer copy = DataUtils.copyOf(buffer);
        bufferQueue.offer(copy);
    }
    
    @OnMessage
    public void onPong(PongMessage pong)
    {
        ByteBuffer copy = DataUtils.copyOf(pong.getApplicationData());
        pongQueue.offer(copy);
    }
}
