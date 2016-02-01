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

package org.eclipse.jetty.tests.ws;

import javax.inject.Inject;
import javax.servlet.http.HttpSession;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.tests.logging.JULog;

@ServerEndpoint(value = "/sessioninfo")
public class SessionInfoSocket
{
    @Inject
    private JULog LOG;

    @Inject
    private HttpSession httpSession;

    private Session wsSession;

    @OnOpen
    public void onOpen(Session session)
    {
        LOG.info("onOpen({0})",asClassId(session));
        this.wsSession = session;
    }

    @OnMessage
    public void onMessage(String message)
    {
        LOG.info("onMessage({0})",quoted(message));
        
        try
        {
            RemoteEndpoint.Basic remote = wsSession.getBasicRemote();
            LOG.info("Remote.Basic: {0}", remote);
            
            if ("info".equalsIgnoreCase(message))
            {
                LOG.info("returning 'info' details");
                remote.sendText("HttpSession = " + httpSession);
            }
            else if ("close".equalsIgnoreCase(message))
            {
                LOG.info("closing session");
                wsSession.close();
            }
            else
            {
                LOG.info("echoing message as-is");
                remote.sendText(message);
            }
        }
        catch (Throwable t)
        {
            LOG.warn(t);
        }
    }

    private String asClassId(Object obj)
    {
        if (obj == null)
        {
            return "<null>";
        }
        return String.format("%s@%X",obj.getClass().getName(),obj.hashCode());
    }

    private String quoted(String str)
    {
        if (str == null)
        {
            return "<null>";
        }
        return '"' + str + '"';
    }
}
