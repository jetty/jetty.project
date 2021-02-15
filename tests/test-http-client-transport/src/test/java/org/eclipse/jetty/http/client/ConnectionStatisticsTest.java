//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.client;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.client.util.BytesContentProvider;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ArgumentsSource;

import static org.eclipse.jetty.http.client.Transport.H2C;
import static org.eclipse.jetty.http.client.Transport.HTTP;
import static org.hamcrest.MatcherAssert.assertThat;

public class ConnectionStatisticsTest extends AbstractTest<TransportScenario>
{
    @Override
    public void init(Transport transport) throws IOException
    {
        setScenario(new TransportScenario(transport));
        Assumptions.assumeTrue(scenario.transport == HTTP || scenario.transport == H2C);
    }

    @ParameterizedTest
    @ArgumentsSource(TransportProvider.class)
    public void testConnectionStatistics(Transport transport) throws Exception
    {
        init(transport);
        scenario.start(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });

        CountDownLatch closed = new CountDownLatch(2);
        Connection.Listener closer = new Connection.Listener()
        {
            @Override
            public void onOpened(Connection connection)
            {
            }

            @Override
            public void onClosed(Connection connection)
            {
                closed.countDown();
            }
        };

        ConnectionStatistics serverStats = new ConnectionStatistics();
        scenario.connector.addBean(serverStats);
        scenario.connector.addBean(closer);
        serverStats.start();

        ConnectionStatistics clientStats = new ConnectionStatistics();
        scenario.client.addBean(clientStats);
        scenario.client.addBean(closer);
        clientStats.start();

        scenario.client.setIdleTimeout(1000);

        byte[] content = new byte[3072];
        long contentLength = content.length;
        ContentResponse response = scenario.client.newRequest(scenario.newURI())
            .header(HttpHeader.CONNECTION, "close")
            .content(new BytesContentProvider(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));

        closed.await();

        assertThat(serverStats.getConnectionsMax(), Matchers.greaterThan(0L));
        assertThat(serverStats.getReceivedBytes(), Matchers.greaterThan(contentLength));
        assertThat(serverStats.getSentBytes(), Matchers.greaterThan(contentLength));
        assertThat(serverStats.getReceivedMessages(), Matchers.greaterThan(0L));
        assertThat(serverStats.getSentMessages(), Matchers.greaterThan(0L));

        assertThat(clientStats.getConnectionsMax(), Matchers.greaterThan(0L));
        assertThat(clientStats.getReceivedBytes(), Matchers.greaterThan(contentLength));
        assertThat(clientStats.getSentBytes(), Matchers.greaterThan(contentLength));
        assertThat(clientStats.getReceivedMessages(), Matchers.greaterThan(0L));
        assertThat(clientStats.getSentMessages(), Matchers.greaterThan(0L));
    }
}
