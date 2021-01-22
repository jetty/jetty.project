//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http2.server;

import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;

/**
 * HTTP/2 server to run the 'h2spec' tool against.
 */
public class H2SpecServer
{
    public static void main(String[] args) throws Exception
    {
        int port = Integer.parseInt(args[0]);

        Server server = new Server();

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setRequestHeaderSize(16 * 1024);

        HttpConnectionFactory http = new HttpConnectionFactory(httpConfig);
        HTTP2CServerConnectionFactory h2c = new HTTP2CServerConnectionFactory(httpConfig);
        ServerConnector connector = new ServerConnector(server, http, h2c);
        connector.setPort(port);
        server.addConnector(connector);

        server.start();
    }
}
