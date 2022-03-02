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

package org.eclipse.jetty.ee9.websocket.tests.client;

import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.ee9.servlet.ServletContextHandler;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.websocket.api.Session;
import org.eclipse.jetty.ee9.websocket.api.exceptions.UpgradeException;
import org.eclipse.jetty.ee9.websocket.api.util.WSURI;
import org.eclipse.jetty.ee9.websocket.client.ClientUpgradeRequest;
import org.eclipse.jetty.ee9.websocket.client.WebSocketClient;
import org.eclipse.jetty.ee9.websocket.server.config.JettyWebSocketServletContainerInitializer;
import org.eclipse.jetty.ee9.websocket.tests.CloseTrackingEndpoint;
import org.eclipse.jetty.ee9.websocket.tests.EchoSocket;
import org.eclipse.jetty.ee9.websocket.tests.GetAuthHeaderEndpoint;
import org.eclipse.jetty.ee9.websocket.tests.SimpleStatusServlet;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Various connect condition testing
 */
@SuppressWarnings("Duplicates")
public class ClientConnectTest
{
    private Server server;
    private WebSocketClient client;
    private final CountDownLatch serverLatch = new CountDownLatch(1);

    @SuppressWarnings("unchecked")
    private <E extends Throwable> E assertExpectedError(ExecutionException e, CloseTrackingEndpoint wsocket, Matcher<Throwable> errorMatcher)
    {
        // Validate thrown cause
        Throwable cause = e.getCause();

        assertThat("ExecutionException.cause", cause, errorMatcher);

        // Validate websocket captured cause
        assertDoesNotThrow(() -> wsocket.errorLatch.await(5, TimeUnit.SECONDS));
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
        client.setConnectTimeout(TimeUnit.SECONDS.toMillis(3));
        client.setIdleTimeout(Duration.ofSeconds(3));
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

        JettyWebSocketServletContainerInitializer.configure(context,
            (servletContext, container) ->
            {
                container.setIdleTimeout(Duration.ofSeconds(10));
                container.addMapping("/echo", (req, resp) ->
                {
                    if (req.hasSubProtocol("echo"))
                        resp.setAcceptedSubProtocol("echo");
                    return new EchoSocket();
                });
                container.addMapping("/get-auth-header", (req, resp) -> new GetAuthHeaderEndpoint());

                container.addMapping("/noResponse", (req, resp) ->
                {
                    try
                    {
                        serverLatch.await();
                    }
                    catch (InterruptedException e)
                    {
                        e.printStackTrace();
                    }
                    return null;
                });
            });

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
        client.setIdleTimeout(Duration.ofSeconds(10));

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
        client.setIdleTimeout(Duration.ofSeconds(10));

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
        client.setConnectTimeout(1000);
        client.setIdleTimeout(Duration.ofSeconds(1));
        CloseTrackingEndpoint cliSock = new CloseTrackingEndpoint();

        // Connect to endpoint which waits and does not send back a response.
        URI wsUri = WSURI.toWebsocket(server.getURI().resolve("/noResponse"));
        Future<Session> future = client.connect(cliSock, wsUri);

        // The attempt to get upgrade response future should throw error
        Exception e = assertThrows(Exception.class,
            () -> future.get(5, TimeUnit.SECONDS));

        // Allow server to exit now we have failed.
        serverLatch.countDown();

        // Unwrap the exception to test if it was what we expected.
        assertThat(e, instanceOf(ExecutionException.class));

        Throwable jettyUpgradeException = e.getCause();
        assertThat(jettyUpgradeException, instanceOf(UpgradeException.class));

        Throwable coreUpgradeException = jettyUpgradeException.getCause();
        assertThat(coreUpgradeException, instanceOf(org.eclipse.jetty.websocket.core.exception.UpgradeException.class));

        Throwable timeoutException = coreUpgradeException.getCause();
        assertThat(timeoutException, instanceOf(TimeoutException.class));
        assertThat(timeoutException.getMessage(), containsString("Idle timeout"));
    }
}
