//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.test.client.transport;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class ConnectionStatisticsTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testConnectionStatistics(Transport transport) throws Exception
    {
        // Counting SslConnection opening/closing makes the test more complicated.
        assumeTrue(!transport.isSecure());
        // FastCGI server does not have statistics.
        assumeTrue(transport != Transport.FCGI);

        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean process(Request request, Response response, Callback callback)
            {
                Content.copy(request, response, callback);
                return true;
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
        connector.addBean(serverStats);
        connector.addBean(closer);
        serverStats.start();

        ConnectionStatistics clientStats = new ConnectionStatistics();
        client.addBean(clientStats);
        client.addBean(closer);
        clientStats.start();

        long idleTimeout = 1000;
        client.setIdleTimeout(idleTimeout);

        byte[] content = new byte[3072];
        long contentLength = content.length;
        ContentResponse response = client.newRequest(newURI(transport))
            .headers(headers -> headers.put(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE))
            .body(new BytesRequestContent(content))
            .timeout(5, TimeUnit.SECONDS)
            .send();

        assertThat(response.getStatus(), Matchers.equalTo(HttpStatus.OK_200));
        assertTrue(closed.await(2 * idleTimeout, TimeUnit.MILLISECONDS));

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
