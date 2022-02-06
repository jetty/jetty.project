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

package org.eclipse.jetty.websocket.javax.tests.server.sockets.streaming;

import java.io.IOException;
import java.io.InputStream;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.toolchain.test.StackUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@ServerEndpoint("/echo/streaming/inputstream")
public class InputStreamSocket
{
    private static final Logger LOG = LoggerFactory.getLogger(InputStreamSocket.class);

    @OnMessage
    public String onInputStream(InputStream stream) throws IOException
    {
        return IO.toString(stream, StringUtil.__UTF8);
    }

    @OnError
    public void onError(Session session, Throwable cause) throws IOException
    {
        LOG.warn("Error", cause);
        session.getBasicRemote().sendText("Exception: " + StackUtils.toString(cause));
    }
}
