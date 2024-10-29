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

package org.eclipse.jetty.unixdomain.server;

import java.io.IOException;
import java.net.SocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.HttpProxy;
import org.eclipse.jetty.client.Origin;
import org.eclipse.jetty.client.transport.HttpClientTransportDynamic;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.io.ClientConnector;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.Transport;
import org.eclipse.jetty.server.ConnectionFactory;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.ProxyConnectionFactory;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.component.LifeCycle;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V1;
import static org.eclipse.jetty.client.ProxyProtocolClientConnectionFactory.V2;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnixDomainTest
{
    private ConnectionFactory[] factories = new ConnectionFactory[]{new HttpConnectionFactory()};
    private Server server;
    private Path unixDomainPath;

    private void start(Handler handler) throws Exception
    {
        server = new Server();
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, factories);
        String dir = System.getProperty("jetty.unixdomain.dir");
        assertNotNull(dir);
        unixDomainPath = Files.createTempFile(Path.of(dir), "unix_", ".sock");
        assertTrue(unixDomainPath.toAbsolutePath().toString().length() < 108, "Unix-Domain path too long");
        Files.delete(unixDomainPath);
        connector.setUnixDomainPath(unixDomainPath);
        server.addConnector(connector);
        server.setHandler(handler);
        server.start();
    }

    @AfterEach
    public void dispose()
    {
        LifeCycle.stop(server);
    }

    @Test
    public void testHTTPOverUnixDomain() throws Exception
    {
        String uri = "http://localhost:1234/path";
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Verify the URI is preserved.
                assertEquals(uri, request.getHttpURI().asString());

                EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();

                // Verify the SocketAddresses.
                SocketAddress local = endPoint.getLocalSocketAddress();
                assertThat(local, Matchers.instanceOf(UnixDomainSocketAddress.class));
                SocketAddress remote = endPoint.getRemoteSocketAddress();
                assertThat(remote, Matchers.instanceOf(UnixDomainSocketAddress.class));

                // Verify that other address methods don't throw.
                local = assertDoesNotThrow(endPoint::getLocalAddress);
                assertNull(local);
                remote = assertDoesNotThrow(endPoint::getRemoteAddress);
                assertNull(remote);

                assertDoesNotThrow(endPoint::toString);

                callback.succeeded();
                return true;
            }
        });

        // Use the deprecated APIs for backwards compatibility testing.
        ClientConnector clientConnector = ClientConnector.forUnixDomain(unixDomainPath);
        try (HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic(clientConnector)))
        {
            httpClient.start();
            ContentResponse response = httpClient.newRequest(uri)
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testHTTPOverUnixDomainWithHTTPProxy() throws Exception
    {
        int fakeProxyPort = 4567;
        int fakeServerPort = 5678;
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                // Proxied requests must have an absolute URI.
                HttpURI uri = request.getHttpURI();
                assertNotNull(uri.getScheme());
                assertEquals(fakeServerPort, uri.getPort());
                callback.succeeded();
                return true;
            }
        });

        try (HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic()))
        {
            Origin proxyOrigin = new Origin(
                "http",
                new Origin.Address("localhost", fakeProxyPort),
                null,
                new Origin.Protocol(List.of("http/1.1"), false),
                new Transport.TCPUnix(unixDomainPath)
            );
            httpClient.getProxyConfiguration().addProxy(new HttpProxy(proxyOrigin, null));
            httpClient.start();
            ContentResponse response = httpClient.newRequest("localhost", fakeServerPort)
                .transport(new Transport.TCPUnix(unixDomainPath))
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response.getStatus());
        }
    }

    @Test
    public void testHTTPOverUnixDomainWithProxyProtocol() throws Exception
    {
        String srcAddr = "/proxySrcAddr";
        String dstAddr = "/proxyDstAddr";
        factories = new ConnectionFactory[]{new ProxyConnectionFactory(), new HttpConnectionFactory()};
        start(new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback)
            {
                EndPoint endPoint = request.getConnectionMetaData().getConnection().getEndPoint();
                assertThat(endPoint, Matchers.instanceOf(ProxyConnectionFactory.ProxyEndPoint.class));
                assertThat(endPoint.getLocalSocketAddress(), Matchers.instanceOf(UnixDomainSocketAddress.class));
                assertThat(endPoint.getRemoteSocketAddress(), Matchers.instanceOf(UnixDomainSocketAddress.class));
                String target = Request.getPathInContext(request);
                Path localPath = ((UnixDomainSocketAddress)endPoint.getLocalSocketAddress()).getPath();
                if ("/v1".equals(target))
                {
                    // As PROXYv1 does not support UNIX, the wrapped EndPoint data is used.
                    assertThat(localPath, Matchers.equalTo(unixDomainPath));
                }
                else if ("/v2".equals(target))
                {
                    assertThat(localPath.toString(), Matchers.equalTo(FS.separators(dstAddr)));
                    Path remotePath = ((UnixDomainSocketAddress)endPoint.getRemoteSocketAddress()).getPath();
                    assertThat(remotePath.toString(), Matchers.equalTo(FS.separators(srcAddr)));
                }
                else
                {
                    Assertions.fail("Invalid PROXY protocol version " + target);
                }
                callback.succeeded();
                return true;
            }
        });

        try (HttpClient httpClient = new HttpClient(new HttpClientTransportDynamic()))
        {
            httpClient.start();
            // Try PROXYv1 with the PROXY information retrieved from the EndPoint.
            // PROXYv1 does not support the UNIX family.
            ContentResponse response1 = httpClient.newRequest("localhost", 0)
                .transport(new Transport.TCPUnix(unixDomainPath))
                .path("/v1")
                .tag(new V1.Tag())
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response1.getStatus());

            // Try PROXYv2 with explicit PROXY information.
            var tag = new V2.Tag(V2.Tag.Command.PROXY, V2.Tag.Family.UNIX, V2.Tag.Protocol.STREAM, srcAddr, 0, dstAddr, 0, null);
            ContentResponse response2 = httpClient.newRequest("localhost", 0)
                .transport(new Transport.TCPUnix(unixDomainPath))
                .path("/v2")
                .tag(tag)
                .timeout(5, TimeUnit.SECONDS)
                .send();

            assertEquals(HttpStatus.OK_200, response2.getStatus());
        }
    }

    @Test
    public void testInvalidUnixDomainPath()
    {
        server = new Server();
        UnixDomainServerConnector connector = new UnixDomainServerConnector(server, factories);
        connector.setUnixDomainPath(Path.of("/does/not/exist"));
        server.addConnector(connector);
        assertThrows(IOException.class, () -> server.start());
    }
}
