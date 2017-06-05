//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.util.log.Log;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest(JettyHttpServer.class)
public class JettyHttpServerTest extends JettyHttpServerBase
{

    private DelegatingThreadPool delegatingThreadPool;

    private Executor executor;

    private Executor actualExecutor;

    private HttpConfiguration httpConfiguration;

    private InetSocketAddress inetSocketAddress;

    private InetSocketAddress address;

    private ServerConnector serverConnector;

    private HttpConfiguration configuration;

    @Test
    public void testSetExecutor()
    {
        // given
        delegatingThreadPool = SpiUtility.getDelegatingThreadPool();
        jettyHttpServer = new JettyHttpServer(new Server(delegatingThreadPool),false);
        executor = SpiUtility.getDelegatingThreadPool();
        jettyHttpServer.setExecutor(executor);

        // when
        actualExecutor = jettyHttpServer.getExecutor();

        // then
        assertEquals("Executor instances must be equal.",executor,actualExecutor);
    }

    @Test
    public void testGetExecutor() throws Exception
    {
        // when
        executor = jettyHttpServer.getExecutor();

        // then
        assertNotNull("Executor instance shouldn't be null after server creation",executor);
    }

    @Test
    public void testGetDefaultHttpConfiguration() throws Exception
    {
        // when
        httpConfiguration = jettyHttpServer.getHttpConfiguration();

        // then
        assertNotNull("HttpConfiguratoin instance shouldn't be null after server creation",httpConfiguration);
    }

    @Test
    public void testGetCustomHttpConfiguration() throws Exception
    {
        // given
        configuration = new HttpConfiguration();

        // when
        jettyHttpServer = new JettyHttpServer(new Server(),false,configuration);

        // then
        assertEquals("Configuration instance must be equal.",configuration,jettyHttpServer.getHttpConfiguration());
    }

    @Test
    public void testInetSocketAddress() throws Exception
    {
        // given
        inetSocketAddress = new InetSocketAddress(SpiConstants.LOCAL_HOST,8080);

        // when
        jettyHttpServer.bind(inetSocketAddress,SpiConstants.BACK_LOG);

        // then
        assertEquals("InetSocketAddress instances must be equal",inetSocketAddress,jettyHttpServer.getAddress());
    }

    @Test
    public void testBindWithNewPort() throws Exception
    {
        // given
        SpiUtility.callBind(jettyHttpServer);
        inetSocketAddress = new InetSocketAddress(SpiConstants.LOCAL_HOST,8082);

        // when
        jettyHttpServer.bind(inetSocketAddress,8082);

        // then
        assertEquals("InetSocketAddress instances must be equal",inetSocketAddress,jettyHttpServer.getAddress());
    }

    @Test
    public void testBindWithNewPortWithDebugDisable() throws Exception
    {
        // given
        SpiUtility.callBind(jettyHttpServer);
        inetSocketAddress = new InetSocketAddress(SpiConstants.LOCAL_HOST,8082);
        Log.getRootLogger().setDebugEnabled(false);

        // when
        jettyHttpServer.bind(inetSocketAddress,8082);

        // then
        assertEquals("InetSocketAddress instances must be equal",inetSocketAddress,jettyHttpServer.getAddress());
    }

    @Test
    public void testServerConnector()
    {
        // given
        address = new InetSocketAddress(SpiConstants.DEFAULT_PORT);

        // when
        serverConnector = jettyHttpServer.newServerConnector(address,SpiConstants.HUNDRED);

        // then
        assertEquals("Port value must be equal to default port value",SpiConstants.DEFAULT_PORT,serverConnector.getPort());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testStart()
    {
        // given
        jettyHttpServer.start();
        executor = SpiUtility.getDelegatingThreadPool();

        // when
        jettyHttpServer.setExecutor(executor);

        // then
        fail("An Unsupported Operation exception must have been raised by now as we cannot " + "reset executor after server started.");
    }
}
