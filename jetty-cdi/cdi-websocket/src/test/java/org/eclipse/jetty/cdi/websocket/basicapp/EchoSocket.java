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

package org.eclipse.jetty.cdi.websocket.basicapp;

import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

@ServerEndpoint("/echo")
public class EchoSocket
{
    private static final Logger LOG = Log.getLogger(EchoSocket.class);
    @SuppressWarnings("unused")
    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        LOG.debug("onOpen(): {}",session);
        this.session = session;
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        LOG.debug("onClose(): {}",close);
        this.session = null;
    }

    @OnMessage
    public String onMessage(String msg)
    {
        return msg;
    }
}
