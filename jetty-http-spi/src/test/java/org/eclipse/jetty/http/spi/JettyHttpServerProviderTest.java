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

package org.eclipse.jetty.http.spi;

import java.io.IOException;
import java.net.InetSocketAddress;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.server.Server;
import org.junit.After;
import org.junit.Test;
import com.sun.net.httpserver.HttpServer;

public class JettyHttpServerProviderTest
{

    private HttpServer jettyHttpServer;

    @After
    public void tearDown() throws Exception
    {
        JettyHttpServerProvider.setServer(null);
        if (jettyHttpServer != null)
        {
            jettyHttpServer.stop(SpiConstants.ONE);
        }
    }

    @Test
    public void testCreateHttpServer() throws Exception
    {
        // when
        initializeHttpServerProvider();

        // then
        assertNotNull("HttpServer instance shouldn't be null after server creation",jettyHttpServer);
    }

    @Test(expected = IOException.class)
    public void testCreateHttpServerIOException() throws Exception
    {
        // given
        Server server = new Server();
        JettyHttpServerProvider.setServer(server);

        // when
        initializeHttpServerProvider();

        // then
        fail("A IOException must have occured by now as port is in use and shared flag is on");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testcreateHttpsServerUnsupportedOperationException() throws Exception
    {
        // given
        initializeHttpServerProvider();
        JettyHttpServerProvider jettyHttpServerProvider = new JettyHttpServerProvider();

        // when
        jettyHttpServerProvider.createHttpsServer(new InetSocketAddress("localhost",SpiConstants.ONE),SpiConstants.BACK_LOG);

        // then
        fail("A UnsupportedOperationException must have occured by now as " + "JettyHttpServerProvider not supporting this operation");
    }

    private void initializeHttpServerProvider() throws Exception
    {
        String localHost = "localhost";
        int port = SpiConstants.ONE;
        jettyHttpServer = new JettyHttpServerProvider().createHttpServer(new InetSocketAddress(localHost,port),SpiConstants.BACK_LOG);
    }
}
