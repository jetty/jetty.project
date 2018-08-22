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

package org.eclipse.jetty.http.spi;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.jws.WebMethod;
import javax.jws.WebService;
import javax.xml.ws.Endpoint;

public class TestEndpointMultiplePublishProblem
{

    private static String default_impl = System.getProperty("com.sun.net.httpserver.HttpServerProvider");

    @BeforeClass
    public static void change_Impl()
    {
        System.setProperty("com.sun.net.httpserver.HttpServerProvider", JettyHttpServerProvider.class.getName());
    }

    @AfterClass
    public static void restore_Impl()
    {
        if(default_impl != null)
        {
            System.setProperty( "com.sun.net.httpserver.HttpServerProvider", default_impl );
        }
    }

    @Test
    public void mainJetty() throws Exception {

        Server jettyWebServer = new Server(new DelegatingThreadPool(new QueuedThreadPool()));
        ServerConnector connector = new ServerConnector(jettyWebServer);
        connector.setHost("localhost");
        connector.setPort(0);
        connector.setReuseAddress(true);
        jettyWebServer.addConnector(connector);
        jettyWebServer.setHandler(new ContextHandlerCollection());

        JettyHttpServerProvider.setServer(jettyWebServer);

        jettyWebServer.start();

        Endpoint.publish(String.format("http://%s:%d/hello", "localhost", 0), new Ws());
        // Comment out the below line for success in later java such as java8_u172, works before u151 or so
        Endpoint.publish(String.format("http://%s:%d/hello2", "localhost", 0), new Ws());

        int port = connector.getLocalPort();

        System.out.printf("Started, check: http://localhost:%d/hello?wsdl%n", port);
    }


    @WebService
    public static class Ws {
        @WebMethod
        public String hello() {
            return "Hello";
        }
    }
}
