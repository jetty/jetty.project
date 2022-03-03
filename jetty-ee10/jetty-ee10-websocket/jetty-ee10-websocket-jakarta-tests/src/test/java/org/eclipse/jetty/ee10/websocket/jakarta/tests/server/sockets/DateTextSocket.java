//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee10.websocket.jakarta.tests.server.sockets;

import java.io.IOException;
import java.util.Date;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.coders.DateDecoder;
import org.eclipse.jetty.ee10.websocket.jakarta.tests.coders.DateEncoder;
import org.eclipse.jetty.toolchain.test.StackUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint(value = "/echo/beans/date", decoders = {DateDecoder.class}, encoders = {DateEncoder.class})
public class DateTextSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(DateTextSocket.class);

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
            // The encoder declared in the @ServerEndpoint will be used
            session.getAsyncRemote().sendObject(d);
        }
    }

    @OnError
    public void onError(Throwable cause) throws IOException
    {
        LOG.warn("Error", cause);
        session.getBasicRemote().sendText("Exception: " + StackUtils.toString(cause));
    }
}
