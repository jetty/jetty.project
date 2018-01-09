//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.gateway;

import java.util.HashMap;

import javax.servlet.http.HttpServletRequest;

import org.eclipse.jetty.client.Address;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.client.RHTTPListener;
import org.eclipse.jetty.rhttp.client.RHTTPRequest;
import org.eclipse.jetty.rhttp.client.RHTTPResponse;
import org.eclipse.jetty.rhttp.gateway.GatewayServer;
import org.eclipse.jetty.rhttp.gateway.TargetIdRetriever;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.nio.SelectChannelConnector;


/**
 * @version $Revision$ $Date$
 */
public class GatewayEchoServer
{
    private volatile GatewayServer server;
    private volatile Address address;
    private volatile String uri;
    private volatile HttpClient httpClient;
    private volatile RHTTPClient client;

    public void start() throws Exception
    {
        server = new GatewayServer();
        Connector connector = new SelectChannelConnector();
        server.addConnector(connector);
        server.setTargetIdRetriever(new EchoTargetIdRetriever());
        server.start();
        server.dumpStdErr();
        address = new Address("localhost", connector.getLocalPort());
        uri = server.getContext().getContextPath()+GatewayServer.DFT_EXT_PATH;

        httpClient = new HttpClient();
        httpClient.setConnectorType(HttpClient.CONNECTOR_SELECT_CHANNEL);
        httpClient.start();

        client = new JettyClient(httpClient, new Address("localhost", connector.getLocalPort()), server.getContext().getContextPath()+GatewayServer.DFT_CONNECT_PATH, "echo");
        client.addListener(new EchoListener(client));
        client.connect();
    }

    public void stop() throws Exception
    {
        client.disconnect();
        httpClient.stop();
        server.stop();
    }

    public Address getAddress()
    {
        return address;
    }

    public String getURI()
    {
        return uri;
    }

    public static class EchoTargetIdRetriever implements TargetIdRetriever
    {
        public String retrieveTargetId(HttpServletRequest httpRequest)
        {
            return "echo";
        }
    }

    private static class EchoListener implements RHTTPListener
    {
        private final RHTTPClient client;

        public EchoListener(RHTTPClient client)
        {
            this.client = client;
        }

        public void onRequest(RHTTPRequest request) throws Exception
        {
            RHTTPResponse response = new RHTTPResponse(request.getId(), 200, "OK", new HashMap<String, String>(), request.getBody());
            client.deliver(response);
        }
    }
}
