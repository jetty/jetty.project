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

package org.eclipse.jetty.websocket.tests.client;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnMessage;

import org.eclipse.jetty.websocket.tests.jsr356.AbstractJsrTrackingSocket;
import org.eclipse.jetty.websocket.tests.jsr356.coders.Quotes;
import org.eclipse.jetty.websocket.tests.jsr356.coders.QuotesDecoder;

@ClientEndpoint(decoders = QuotesDecoder.class, subprotocols = "quotes")
public class QuotesSocket extends AbstractJsrTrackingSocket
{
    public BlockingQueue<Quotes> messageQueue = new LinkedBlockingDeque<>();
    
    public QuotesSocket(String id)
    {
        super(id);
    }
    
    @OnMessage
    public void onMessage(Quotes quote)
    {
        LOG.debug("onMessage({})", quote);
        messageQueue.offer(quote);
    }
}
