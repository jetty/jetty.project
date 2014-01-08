//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rhttp.connector;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.embedded.HelloHandler;
import org.eclipse.jetty.rhttp.client.JettyClient;
import org.eclipse.jetty.rhttp.client.RHTTPClient;
import org.eclipse.jetty.rhttp.connector.ReverseHTTPConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.log.Log;

/**
 * A Test content server that uses a {@link ReverseHTTPConnector}.
 * The main of this class starts 3 TestReversionServers with IDs A, B and C.
 */
public class TestReverseServer extends Server
{
    TestReverseServer(String targetId)
    {
        setHandler(new HelloHandler("Hello "+targetId,"Hi from "+targetId));
        
        HttpClient httpClient = new HttpClient();
        RHTTPClient client = new JettyClient(httpClient,"http://localhost:8080/__rhttp",targetId);
        ReverseHTTPConnector connector = new ReverseHTTPConnector(client);
        
        addConnector(connector);
    }
    
    public static void main(String... args) throws Exception
    {
        Log.getLogger("org.mortbay.jetty.rhttp.client").setDebugEnabled(true);
        
        TestReverseServer[] node = new TestReverseServer[] { new TestReverseServer("A"),new TestReverseServer("B"),new TestReverseServer("C") };
        
        for (TestReverseServer s : node)
            s.start();

        for (TestReverseServer s : node)
            s.join();
    }
}
