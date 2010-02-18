// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================

package org.eclipse.jetty.client;

import java.io.File;

import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ssl.SslSocketConnector;

/**
 * Functional testing for HttpExchange.
 * 
 * 
 * 
 */
public class SslHttpExchangeTest extends HttpExchangeTest
{
    @Override
    protected void setUp() throws Exception
    {
        _scheme="https://";
        startServer();
        _httpClient=new HttpClient();
        // _httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        _httpClient.setConnectorType(HttpClient.CONNECTOR_SOCKET);
        _httpClient.setMaxConnectionsPerAddress(2);
        _httpClient.start();
    }

    @Override
    protected void newServer()
    {
        _server = new Server();
        //SslSelectChannelConnector connector = new SslSelectChannelConnector();
        SslSocketConnector connector = new SslSocketConnector();

        String keystore = System.getProperty("user.dir") + File.separator + "src" + File.separator + "test" + File.separator + "resources" + File.separator
                + "keystore";

        connector.setPort(0);
        connector.setKeystore(keystore);
        connector.setPassword("storepwd");
        connector.setKeyPassword("keypwd");
	connector.setAllowRenegotiate(true);

        _server.setConnectors(new Connector[]
        { connector });
        _connector=connector;
    }
}
