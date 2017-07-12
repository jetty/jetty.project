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

package org.eclipse.jetty.websocket.tests.jsr356;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.tests.DataUtils;

public class JsrTrackingEndpoint extends AbstractJsrTrackingEndpoint
{
    public JsrTrackingEndpoint()
    {
        super("JsrTrackingEndpoint");
    }
    
    public JsrTrackingEndpoint(String id)
    {
        super(id);
    }
    
    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        super.onOpen(session, config);
        
        // Chose to do this via a lambda MessageHandler to test javax.websocket 1.1 functionality
        session.addMessageHandler(String.class, message ->
        {
            messageQueue.offer(message);
            try
            {
                session.getBasicRemote().sendText(message);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    
        // Chose to do this via a lambda MessageHandler to test javax.websocket 1.1 functionality
        session.addMessageHandler(ByteBuffer.class, buffer ->
        {
            ByteBuffer copy = DataUtils.copyOf(buffer);
            bufferQueue.offer(copy);
            try
            {
                session.getBasicRemote().sendBinary(buffer);
            }
            catch (IOException e)
            {
                throw new RuntimeException(e);
            }
        });
    }
}
