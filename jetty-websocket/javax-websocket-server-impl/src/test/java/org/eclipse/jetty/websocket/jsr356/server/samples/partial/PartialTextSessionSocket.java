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

package org.eclipse.jetty.websocket.jsr356.server.samples.partial;

import java.io.IOException;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.util.StackUtil;

@ServerEndpoint("/echo/partial/textsession")
public class PartialTextSessionSocket
{
    private static final Logger LOG = Log.getLogger(PartialTextSessionSocket.class);
    private StringBuilder buf = new StringBuilder();

    @OnMessage
    public void onPartial(String msg, boolean fin, Session session) throws IOException
    {
        buf.append("('").append(msg).append("',").append(fin).append(')');
        if (fin)
        {
            session.getBasicRemote().sendText(buf.toString());
            buf.setLength(0);
        }
    }

    @OnError
    public void onError(Throwable cause, Session session) throws IOException
    {
        LOG.warn("Error",cause);
        session.getBasicRemote().sendText("Exception: " + StackUtil.toString(cause));
    }
}
