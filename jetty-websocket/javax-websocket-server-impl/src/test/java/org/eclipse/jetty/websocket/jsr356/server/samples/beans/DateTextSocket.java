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

package org.eclipse.jetty.websocket.jsr356.server.samples.beans;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.util.StackUtil;

@ServerEndpoint(value = "/echo/beans/date", decoders = { DateDecoder.class }, encoders = { DateEncoder.class })
public class DateTextSocket
{
    private static final Logger LOG = Log.getLogger(DateTextSocket.class);

    private Session session;

    @OnOpen
    public void onOpen(Session session)
    {
        this.session = session;
    }

    // The decoder declared in the @ServerEndpoint will be used
    @OnMessage
    public void onMessage(Date d) throws IOException
    {
        if (d == null)
        {
            session.getAsyncRemote().sendText("Error: Date is null");
        }
        else
        {
            String msg = SimpleDateFormat.getDateInstance(SimpleDateFormat.SHORT).format(d);
            // The encoder declared in the @ServerEndpoint will be used
            session.getAsyncRemote().sendText(msg);
        }
    }

    @OnError
    public void onError(Throwable cause) throws IOException
    {
        LOG.warn("Error",cause);
        session.getBasicRemote().sendText("Exception: " + StackUtil.toString(cause));
    }
}
