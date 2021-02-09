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

package org.eclipse.jetty.websocket.tests.client;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.servlet.DispatcherType;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.MappedByteBufferPool;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.UpgradeException;
import org.eclipse.jetty.websocket.api.util.WSURI;
import org.eclipse.jetty.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.eclipse.jetty.websocket.client.WebSocketUpgradeRequest;
import org.eclipse.jetty.websocket.server.NativeWebSocketServletContainerInitializer;
import org.eclipse.jetty.websocket.server.WebSocketUpgradeFilter;
import org.eclipse.jetty.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.websocket.tests.EchoSocket;
import org.eclipse.jetty.websocket.tests.GetAuthHeaderEndpoint;
import org.eclipse.jetty.websocket.tests.InvalidUpgradeServlet;
import org.eclipse.jetty.websocket.tests.SimpleStatusServlet;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Various connect condition testing
 */
@SuppressWarnings("Duplicates")
public class ClientConnectTest
{
    public ByteBufferPool bufferPool = new MappedByteBufferPool();

    private Server server;
    private WebSocketClient client;

    @SuppressWarnings("unchecked")
    private <E extends Throwable> E assertExpectedError(ExecutionException e, CloseTrackingEndpoint wsocket, Matcher<Throwable> errorMatcher)
    {
        // Validate thrown cause
        Throwable cause = e.getCause();

        assertThat("ExecutionException.cause", cause, errorMatcher);

        // Validate websocket captured cause
        Throwable capcause = wsocket.error.get();
        assertThat("Error", capcause, notNullValue());
        assertThat("Error", capcause, errorMatcher);

        // Validate that websocket didn't see an open event
        assertThat("Open Latch", wsocket.openLatch.getCount(), is(1L));

        // Return the captured cause
        return (E)capcause;
    }

    @BeforeEach
    public void startClient() throws Exception
    {
        client = new WebSocketClient();
        client.setBufferPool(bufferPool);
        client.setConnectTimeout(TimeUnit.SECONDS.toMillis(3));
        client.setMaxIdleTimeout(TimeUnit.SECONDS.toMillis(3));
        client.getPolicy().setIdleTimeout(TimeUnit.SECONDS.toMillis(10));
        client.start();
    }

    @BeforeEach
    public void startServer() throws Exception
    {
        server = new Server();

        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        NativeWebSocketServletContainerInitializer.configure(context,
            (servletContext, configuration) ->
            {
                configuration.getPolicy().setIdleTimeout(10000);
                configuration.addMapping("/echo", (req, resp) ->
                {
                    if (req.hasSubProtocol("echo"))
                        resp.setAcceptedSubProtocol("echo");
                    return new EchoSocket();
                });
                configuration.addMapping("/get-auth-header", (req, resp) -> new GetAuthHeaderEndpoint());
            });

        context.addFilter(WebSocketUpgradeFilter.class, "/*", EnumSet.of(DispatcherType.REQUEST));

        context.addServlet(new ServletHolder(new SimpleStatusServlet(404)), "/bogus");
        context.addServlet(new ServletHolder(new SimpleStatusServlet(200)), "/a-okay");
        context.addServlet(new ServletHolder(new InvalidUpgradeServlet()), "/invalid-upgrade/*");

        server.setHandler(context);

        server.start();
    }

    @AfterEach
    public void stopClient() throws Exception
    {
        client.stop();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testUpgradeRequest() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Connect.UpgradeRequest", sess.getUpgradeRequest(), notNullValue());
            assertThat("Connect.UpgradeResponse", sess.getUpgradeResponse(), notNullValue());
        }
    }

    @Test
    public void testUpgradeRequestPercentEncodedQuery() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();
        client.getPolicy().setIdleTimeout(10000);

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo?name=%25foo"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        request.setSubProtocols("echo");
        Future<Session> future = client.connect(cliSock, wsUri);

        try (Session sess = future.get(30, TimeUnit.SECONDS))
        {
            assertThat("Connect.UpgradeRequest", sess.getUpgradeRequest(), notNullValue());
            Map<String, List<String>> paramMap = sess.getUpgradeRequest().getParameterMap();
            List<String> values = paramMap.get("name");
            assertThat("Params[name]", values.get(0), is("%foo"));
            assertThat("Connect.UpgradeResponse", sess.getUpgradeResponse(), notNullValue());
        }
    }

    @Test
    public void testAltConnect() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/echo"));
        HttpClient httpClient = new HttpClient();
        try
        {
            httpClient.start();

            WebSocketUpgradeRequest req = new WebSocketUpgradeRequest(new WebSocketClient(), httpClient, wsUri, cliSock);
            req.header("X-Foo", "Req");
            CompletableFuture<Session> sess = req.sendAsync();

            sess.thenAccept((s) ->
            {
                System.out.printf("Session: %s%n", s);
                s.close();
                assertThat("Connect.UpgradeRequest", s.getUpgradeRequest(), notNullValue());
                assertThat("Connect.UpgradeResponse", s.getUpgradeResponse(), notNullValue());
            });
        }
        finally
        {
            httpClient.stop();
        }
    }

    @Test
    public void testUpgradeWithAuthorizationHeader() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/get-auth-header"));
        ClientUpgradeRequest request = new ClientUpgradeRequest();
        // actual value for this test is irrelevant, its important that this
        // header actually be sent with a value (the value specified)
        String authHeaderValue = "Basic YWxhZGRpbjpvcGVuc2VzYW1l";
        request.setHeader("Authorization", authHeaderValue);
        Future<Session> future = client.connect(cliSock, wsUri, request);

        try (Session sess = future.get(5, TimeUnit.SECONDS))
        {
            // Test client side
            String cliAuthValue = sess.getUpgradeRequest().getHeader("Authorization");
            assertThat("Client Request Authorization Value", cliAuthValue, is(authHeaderValue));

            // wait for response from server
            String received = cliSock.messageQueue.poll(5, TimeUnit.SECONDS);
            assertThat("Message", received, containsString("Header[Authorization]=" + authHeaderValue));
        }
    }

    @Test
    public void testBadHandshake() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/bogus"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
        assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(404));
    }

    @Test
    public void testBadHandshakeGetOK() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/a-okay"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
        assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(200));
    }

    @Test
    public void testBadHandshakeGetOKWithSecWebSocketAccept() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/invalid-upgrade/only-accept"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
        assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(200));
    }

    @Test
    public void testBadHandshakeSwitchingProtocolsInvalidConnectionHeader() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/invalid-upgrade/close-connection"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
        assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
    }

    @Test
    public void testBadHandshakeSwitchingProtocolsNoConnectionHeader() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/invalid-upgrade/missing-connection"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.requestURI", ue.getRequestURI(), notNullValue());
        assertThat("UpgradeException.requestURI", ue.getRequestURI().toASCIIString(), is(wsUri.toASCIIString()));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
    }

    @Test
    public void testBadUpgrade() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/invalid-upgrade/rubbish-accept"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        ExecutionException e = assertThrows(ExecutionException.class,
            () -> future.get(5, TimeUnit.SECONDS));

        UpgradeException ue = assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
        assertThat("UpgradeException.responseStatusCode", ue.getResponseStatusCode(), is(101));
    }

    @Test
    public void testConnectionNotAccepted() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        try (ServerSocket serverSocket = new ServerSocket())
        {
            InetAddress addr = InetAddress.getByName("localhost");
            InetSocketAddress endpoint = new InetSocketAddress(addr, 0);
            serverSocket.bind(endpoint, 1);
            int port = serverSocket.getLocalPort();

            URI wsUri = URI.create(String.format("ws://%s:%d/", addr.getHostAddress(), port));
            Future<Session> future = client.connect(cliSock, wsUri);

            // Intentionally not accept incoming socket.
            // serverSocket.accept();

            try
            {
                future.get(8, TimeUnit.SECONDS);
                fail("Should have Timed Out");
            }
            catch (ExecutionException e)
            {
                // Passing Path (active session wait timeout)
                assertExpectedError(e, cliSock, instanceOf(UpgradeException.class));
            }
            catch (TimeoutException e)
            {
                // Passing Path
            }
        }
    }

    @Test
    public void testConnectionRefused() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        // Intentionally bad port with nothing listening on it
        URI wsUri = new URI("ws://127.0.0.1:1");

        try
        {
            Future<Session> future = client.connect(cliSock, wsUri);

            // The attempt to get upgrade response future should throw error
            future.get(5, TimeUnit.SECONDS);
            fail("Expected ExecutionException -> ConnectException");
        }
        catch (ConnectException e)
        {
            Throwable t = cliSock.error.get();
            assertThat("Error Queue[0]", t, instanceOf(ConnectException.class));
        }
        catch (ExecutionException e)
        {
            assertExpectedError(e, cliSock,
                anyOf(
                    instanceOf(UpgradeException.class),
                    instanceOf(SocketTimeoutException.class),
                    instanceOf(ConnectException.class)));
        }
    }

    @Test
    public void testConnectionTimeoutConcurrent() throws Exception
    {
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        try (ServerSocket serverSocket = new ServerSocket())
        {
            InetAddress addr = InetAddress.getByName("localhost");
            InetSocketAddress endpoint = new InetSocketAddress(addr, 0);
            serverSocket.bind(endpoint, 1);
            int port = serverSocket.getLocalPort();
            URI wsUri = URI.create(String.format("ws://%s:%d/", addr.getHostAddress(), port));
            Future<Session> future = client.connect(cliSock, wsUri);

            // Accept the connection, but do nothing on it (no response, no upgrade, etc)
            serverSocket.accept();

            // The attempt to get upgrade response future should throw error
            Exception e = assertThrows(Exception.class,
                () -> future.get(5, TimeUnit.SECONDS));

            if (e instanceof ExecutionException)
            {
                assertExpectedError((ExecutionException)e, cliSock, anyOf(
                    instanceOf(ConnectException.class),
                    instanceOf(UpgradeException.class)
                ));
            }
            else
            {
                assertThat("Should have been a TimeoutException", e, instanceOf(TimeoutException.class));
            }
        }
    }
}
