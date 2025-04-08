//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
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

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.Destination;
import org.eclipse.jetty.client.transport.HttpClientTransportOverHTTP;
import org.eclipse.jetty.fcgi.server.internal.ServerFCGIConnection;
import org.eclipse.jetty.http2.server.internal.HTTP2ServerConnection;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.quic.server.ServerQuicConnection;
import org.eclipse.jetty.server.internal.HttpConnection;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;

public class ConnectionPoolTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testPreCreateConnections(TransportType transportType) throws Exception
    {
        int maxConnectionsPerDestination = 8;
        prepareServer(transportType, new EmptyServerHandler());
        ConnectionListener serverConnections = new ConnectionListener();
        connector.addBean(serverConnections);
        server.start();

        startClient(transportType);
        client.setMaxConnectionsPerDestination(maxConnectionsPerDestination);
        if (transportType == TransportType.HTTPS)
            ((HttpClientTransportOverHTTP)client.getHttpClientTransport()).setInitializeConnections(true);

        var request = client.newRequest(newURI(transportType));
        Destination destination = client.resolveDestination(request);
        destination.getConnectionPool().preCreateConnections(client.getMaxConnectionsPerDestination())
            .get(5, TimeUnit.SECONDS);

        // Verify that server connections have been created.
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
        {
            switch (transportType)
            {
                case HTTP, HTTPS -> assertThat(serverConnections.filter(HttpConnection.class).size(), is(maxConnectionsPerDestination));
                case H2C, H2 -> assertThat(serverConnections.filter(HTTP2ServerConnection.class).size(), is(maxConnectionsPerDestination));
                case H3_QUICHE -> assertThat(serverConnections.filter(ServerQuicConnection.class).size(), is(1));
                case FCGI -> assertThat(serverConnections.filter(ServerFCGIConnection.class).size(), is(maxConnectionsPerDestination));
            }
        });

        // Verify that TLS was performed.
        List<Connection> sslConnections = switch (transportType)
        {
            case HTTP, H2C, FCGI, H3_QUICHE -> null;
            case HTTPS, H2 -> serverConnections.filter(SslConnection.class);
        };
        if (sslConnections != null)
        {
            assertThat(sslConnections.size(), is(maxConnectionsPerDestination));
            sslConnections.forEach(c -> assertThat(c.getBytesIn(), greaterThan(0L)));
            sslConnections.forEach(c -> assertThat(c.getBytesOut(), greaterThan(0L)));
        }
    }

    private static class ConnectionListener implements Connection.Listener
    {
        private final List<Connection> connections = new CopyOnWriteArrayList<>();

        @Override
        public void onOpened(Connection connection)
        {
            connections.add(connection);
        }

        @Override
        public void onClosed(Connection connection)
        {
            connections.remove(connection);
        }

        private List<Connection> filter(Class<? extends Connection> klass)
        {
            return connections.stream()
                .filter(klass::isInstance)
                .toList();
        }
    }
}
