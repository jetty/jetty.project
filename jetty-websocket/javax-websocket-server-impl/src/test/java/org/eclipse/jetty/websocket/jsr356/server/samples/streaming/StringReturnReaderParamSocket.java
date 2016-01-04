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

package org.eclipse.jetty.websocket.jsr356.server.samples.streaming;

import java.io.IOException;
import java.io.Reader;

import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;
import javax.websocket.server.PathParam;
import javax.websocket.server.ServerEndpoint;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.util.StackUtil;

@ServerEndpoint("/echo/streaming/readerparam2/{param}")
public class StringReturnReaderParamSocket
{
    private static final Logger LOG = Log.getLogger(StringReturnReaderParamSocket.class);

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
        LOG.warn("Error",cause);
        session.getBasicRemote().sendText("Exception: " + StackUtil.toString(cause));
    }
}
