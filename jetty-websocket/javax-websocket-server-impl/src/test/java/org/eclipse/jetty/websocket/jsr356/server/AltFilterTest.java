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

package org.eclipse.jetty.websocket.jsr356.server;

import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.junit.Assert.assertThat;

import java.net.URI;
import java.util.Queue;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.common.test.LeakTrackingBufferPoolRule;
import org.eclipse.jetty.websocket.jsr356.server.samples.echo.BasicEchoSocket;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;

/**
 * Testing the use of an alternate {@link org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter}
 * defined in the WEB-INF/web.xml
 */
public class AltFilterTest
{
    @Rule
    public TestingDir testdir = new TestingDir();

    @Rule
    public LeakTrackingBufferPoolRule bufferPool = new LeakTrackingBufferPoolRule("Test");

    @Test
    public void testEcho() throws Exception
    {
        WSServer wsb = new WSServer(testdir,"app");
        wsb.copyWebInf("alt-filter-web.xml");
        // the endpoint (extends javax.websocket.Endpoint)
        wsb.copyClass(BasicEchoSocket.class);

        try
        {
            wsb.start();
            URI uri = wsb.getServerBaseURI();
            
            WebAppContext webapp = wsb.createWebAppContext();
            wsb.deployWebapp(webapp);
            
            FilterHolder filterWebXml = webapp.getServletHandler().getFilter("wsuf-test");
            assertThat("Filter[wsuf-test]", filterWebXml, notNullValue());
            
            FilterHolder filterSCI = webapp.getServletHandler().getFilter("Jetty_WebSocketUpgradeFilter");
            assertThat("Filter[Jetty_WebSocketUpgradeFilter]", filterSCI, nullValue());

            WebSocketClient client = new WebSocketClient(bufferPool);
            try
            {
                client.start();
                JettyEchoSocket clientEcho = new JettyEchoSocket();
                Future<Session> future = client.connect(clientEcho,uri.resolve("echo"));
                // wait for connect
                future.get(1,TimeUnit.SECONDS);
                clientEcho.sendMessage("Hello Echo");
                Queue<String> msgs = clientEcho.awaitMessages(1);
                Assert.assertEquals("Expected message","Hello Echo",msgs.poll());
            }
            finally
            {
                client.stop();
            }
        }
        finally
        {
            wsb.stop();
        }
    }
}
