//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.IO;

public class ServerConnectorAsyncContextTest extends LocalAsyncContextTest
{
    @Override
    protected Connector initConnector()
    {
        return new ServerConnector(_server);
    }

    @Override
    protected String getResponse(String request) throws Exception
    {
        ServerConnector connector = (ServerConnector)_connector;
        Socket socket = new Socket((String)null, connector.getLocalPort());
        socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        return IO.toString(socket.getInputStream());
    }
}
