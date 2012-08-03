package org.eclipse.jetty.server;
//========================================================================
//Copyright 2011-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

import java.net.Socket;

import org.eclipse.jetty.util.IO;

public class SelectChannelAsyncContextTest extends LocalAsyncContextTest
{
    @Override
    protected Connector initConnector()
    {
        return new SelectChannelConnector(_server);
    }

    @Override
    protected String getResponse(String request) throws Exception
    {
        SelectChannelConnector connector = (SelectChannelConnector)_connector;
        Socket socket = new Socket((String)null,connector.getLocalPort());
        socket.getOutputStream().write(request.getBytes("UTF-8"));
        return IO.toString(socket.getInputStream());
    }
}
