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

package org.eclipse.jetty.websocket.tests.client.jsr356;

import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;
import javax.websocket.PongMessage;

import org.eclipse.jetty.websocket.tests.DataUtils;
import org.eclipse.jetty.websocket.tests.jsr356.AbstractJsrTrackingEndpoint;

@ClientEndpoint
public class JsrClientTrackingSocket extends AbstractJsrTrackingEndpoint
{
    public BlockingQueue<String> messageQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> pongQueue = new LinkedBlockingDeque<>();
    public BlockingQueue<ByteBuffer> bufferQueue = new LinkedBlockingDeque<>();
    
    public JsrClientTrackingSocket()
    {
        super("@ClientEndpoint");
    }
    
    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public String onText(String msg)
    {
        messageQueue.offer(msg);
        return msg;
    }
    
    @OnMessage(maxMessageSize = 50 * 1024 * 1024)
    public ByteBuffer onBinary(ByteBuffer buffer)
    {
        ByteBuffer copy = DataUtils.copyOf(buffer);
        bufferQueue.offer(copy);
        return buffer;
    }
    
    @OnMessage
    public void onPong(PongMessage pong)
    {
        ByteBuffer copy = DataUtils.copyOf(pong.getApplicationData());
        pongQueue.offer(copy);
    }
}
