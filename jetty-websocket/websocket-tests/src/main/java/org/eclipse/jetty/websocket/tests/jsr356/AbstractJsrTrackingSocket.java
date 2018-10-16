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

package org.eclipse.jetty.websocket.tests.jsr356;

import javax.websocket.CloseReason;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.tests.AbstractTrackingEndpoint;

@SuppressWarnings("unused")
public abstract class AbstractJsrTrackingSocket extends AbstractTrackingEndpoint<Session>
{
    public AbstractJsrTrackingSocket(String id)
    {
        super(id);
    }
    
    @OnOpen
    public void onOpen(Session session, EndpointConfig config)
    {
        super.onWSOpen(session);
    }
    
    @OnClose
    public void onClose(CloseReason closeReason)
    {
        super.onWSClose(closeReason.getCloseCode().getCode(), closeReason.getReasonPhrase());
    }
    
    @OnError
    public void onError(Throwable cause)
    {
        super.onWSError(cause);
    }
}
