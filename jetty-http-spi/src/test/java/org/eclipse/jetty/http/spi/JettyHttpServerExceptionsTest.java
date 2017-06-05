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

import static org.junit.Assert.fail;
import static org.junit.Assert.assertNull;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.when;
import java.io.IOException;
import java.util.concurrent.Executor;
import org.eclipse.jetty.http.spi.util.Pool;
import org.eclipse.jetty.http.spi.util.SpiConstants;
import org.eclipse.jetty.http.spi.util.SpiUtility;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandlerCollection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.powermock.reflect.Whitebox;

@RunWith(PowerMockRunner.class)
@PrepareForTest(JettyHttpServer.class)
public class JettyHttpServerExceptionsTest extends JettyHttpServerBase
{

    private Executor executor;

    @Test(expected = IllegalArgumentException.class)
    public void testSetExecutorIllegalArgumentException()
    {
        // given
        executor = null;

        // when
        jettyHttpServer.setExecutor(executor);

        // then
        fail("An IllegalArgumentException must have occured by now as executor is null");
    }

    @Test(expected = UnsupportedOperationException.class)
    public void testSetExecutorUnsupportedOperationException()
    {
        // given
        executor = SpiUtility.getThreadPoolExecutor(Pool.CORE_POOL_SIZE.getValue(),SpiConstants.poolInfo);

        // when
        jettyHttpServer.setExecutor(executor);

        // then
        fail("An UnsupportedOperationException must have occured by now as executor " + "instance is not of type DelegatingThreadPool");
    }

    @Test(expected = IOException.class)
    public void testBindIOException() throws Exception
    {
        // given
        setUpForBindException();

        // when
        jettyHttpServer.stop(SpiConstants.DELAY);

        // then
        fail("A IOException must have occured by now as the server shared value is true");
    }

    private void setUpForBindException() throws Exception
    {
        jettyHttpServer = new JettyHttpServer(new Server(),true);
        jettyHttpServer.start();
        SpiUtility.callBind(jettyHttpServer);
    }

    @Test(expected = RuntimeException.class)
    public void testStopServer() throws Exception
    {
        // given
        Server server = mock(Server.class);
        when(server.getBeans(NetworkConnector.class)).thenReturn(null);
        jettyHttpServer = new JettyHttpServer(server,false);
        jettyHttpServer.bind(SpiUtility.getInetSocketAddress(),SpiConstants.BACK_LOG);

        // when
        jettyHttpServer.stop(SpiConstants.DELAY);

        // then
        fail("A RuntimeException must have occured by now as we are stopping the server with wrong object");
    }

    @Test
    public void test() throws Exception
    {
        // when
        ContextHandlerCollection handler = Whitebox.<ContextHandlerCollection> invokeMethod(jettyHttpServer,"findContextHandlerCollection",new Object[]
        { null });

        // then
        assertNull("Handler must be null as handlers parameter is null",handler);
    }
}
