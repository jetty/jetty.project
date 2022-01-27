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

package org.eclipse.jetty.websocket.jakarta.tests.server.sockets.streaming;

import java.io.IOException;
import java.io.Reader;

import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.jetty.toolchain.test.StackUtils;
import org.eclipse.jetty.util.IO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint("/echo/streaming/readerparam2/{param}")
public class StringReturnReaderParamSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(StringReturnReaderParamSocket.class);

    @OnMessage
    public String onReader(Reader reader, @PathParam("param") String param) throws IOException
    {
        StringBuilder msg = new StringBuilder();
        msg.append(IO.toString(reader));
        msg.append('|');
        msg.append(param);
        return msg.toString();
    }

    @OnError
    public void onError(Session session, Throwable cause) throws IOException
    {
        LOG.warn("Error", cause);
        session.getBasicRemote().sendText("Exception: " + StackUtils.toString(cause));
    }
}
