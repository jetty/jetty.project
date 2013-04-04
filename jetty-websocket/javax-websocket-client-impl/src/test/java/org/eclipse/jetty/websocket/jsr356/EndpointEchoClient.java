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

package org.eclipse.jetty.websocket.jsr356;

import static org.hamcrest.Matchers.*;

import java.io.IOException;

import javax.websocket.CloseReason;
import javax.websocket.Endpoint;
import javax.websocket.EndpointConfig;
import javax.websocket.Session;

import org.junit.Assert;

/**
 * Basic Echo Client from extended Endpoint
 */
public class EndpointEchoClient extends Endpoint
{
    private Session session = null;
    private CloseReason close = null;
    public EchoCaptureHandler textCapture = new EchoCaptureHandler();

    public CloseReason getClose()
    {
        return close;
    }

    @Override
    public void onOpen(Session session, EndpointConfig config)
    {
        this.session = session;
        Assert.assertThat("Session",session,notNullValue());
        Assert.assertThat("EndpointConfig",config,notNullValue());
        this.session.addMessageHandler(textCapture);
    }

    public void sendText(String text) throws IOException
    {
        if (session != null)
        {
            session.getBasicRemote().sendText(text);
        }
    }
}
