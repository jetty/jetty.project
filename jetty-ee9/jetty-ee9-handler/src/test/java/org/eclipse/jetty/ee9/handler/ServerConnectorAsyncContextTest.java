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

package org.eclipse.jetty.ee9.handler;

public class ServerConnectorAsyncContextTest extends LocalAsyncContextTest
{
    /*
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

     */
}
