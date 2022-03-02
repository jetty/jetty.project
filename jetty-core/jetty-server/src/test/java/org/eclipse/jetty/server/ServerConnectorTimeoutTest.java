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

package org.eclipse.jetty.server;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.time.Duration.ofSeconds;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

@Disabled // TODO
public class ServerConnectorTimeoutTest extends ConnectorTimeoutTest
{
    @BeforeEach
    public void init() throws Exception
    {
        ServerConnector connector = new ServerConnector(_server, 1, 1);
        connector.setIdleTimeout(MAX_IDLE_TIME);
        _server.addConnector(connector);
    }

    @Test
    public void testStartStopStart() throws Exception
    {
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            _server.stop();
            _server.start();
        });
    }

    /* TODO
    @Test
    public void testIdleTimeoutAfterSuspend() throws Exception
    {
        _server.stop();
        SuspendHandler handler = new SuspendHandler();
        SessionHandler session = new SessionHandler();
        session.setHandler(handler);
        _server.setHandler(session);
        _server.start();

        handler.setSuspendFor(100);
        handler.setResumeAfter(25);
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String process = process(null).toUpperCase(Locale.ENGLISH);
            assertThat(process, containsString("RESUMED"));
        });
    }

    @Test
    public void testIdleTimeoutAfterTimeout() throws Exception
    {
        SuspendHandler handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(handler);
        _server.setHandler(session);
        _server.start();

        handler.setSuspendFor(50);
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String process = process(null).toUpperCase(Locale.ENGLISH);
            assertThat(process, containsString("TIMEOUT"));
        });
    }

    @Test
    public void testIdleTimeoutAfterComplete() throws Exception
    {
        SuspendHandler handler = new SuspendHandler();
        _server.stop();
        SessionHandler session = new SessionHandler();
        session.setHandler(handler);
        _server.setHandler(session);
        _server.start();

        handler.setSuspendFor(100);
        handler.setCompleteAfter(25);
        assertTimeoutPreemptively(ofSeconds(10), () ->
        {
            String process = process(null).toUpperCase(Locale.ENGLISH);
            assertThat(process, containsString("COMPLETED"));
        });
    }

    private String process(String content) throws IOException, InterruptedException
    {
        synchronized (this)
        {
            String request = "GET / HTTP/1.1\r\n" + "Host: localhost\r\n";

            if (content == null)
                request += "\r\n";
            else
                request += "Content-Length: " + content.length() + "\r\n" + "\r\n" + content;
            return getResponse(request);
        }
    }

    private String getResponse(String request) throws IOException, InterruptedException
    {
        try (Socket socket = new Socket((String)null, _connector.getLocalPort()))
        {
            socket.setSoTimeout(10 * MAX_IDLE_TIME);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
            InputStream inputStream = socket.getInputStream();
            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            String response = IO.toString(inputStream);
            long timeElapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) - start;
            assertThat(timeElapsed, greaterThanOrEqualTo(MAX_IDLE_TIME - 100L));
            return response;
        }
    }

    @Test
    public void testHttpWriteIdleTimeout() throws Exception
    {
        _httpConfiguration.setIdleTimeout(500);
        configureServer(new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
            {
                baseRequest.setHandled(true);
                IO.copy(request.getInputStream(), response.getOutputStream());
            }
        });
        Socket client = newSocket(_serverURI.getHost(), _serverURI.getPort());
        client.setSoTimeout(10000);

        assertFalse(client.isClosed());

        final OutputStream os = client.getOutputStream();
        final InputStream is = client.getInputStream();
        final StringBuilder response = new StringBuilder();

        CompletableFuture<Void> responseFuture = CompletableFuture.runAsync(() ->
        {
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8))
            {
                int c;
                while ((c = reader.read()) != -1)
                {
                    response.append((char)c);
                }
            }
            catch (IOException e)
            {
                // Valid path (as connection is forcibly closed)
                // t.printStackTrace(System.err);
            }
        });

        CompletableFuture<Void> requestFuture = CompletableFuture.runAsync(() ->
        {
            try
            {
                os.write((
                    "POST /echo HTTP/1.0\r\n" +
                        "host: " + _serverURI.getHost() + ":" + _serverURI.getPort() + "\r\n" +
                        "content-type: text/plain; charset=utf-8\r\n" +
                        "content-length: 20\r\n" +
                        "\r\n").getBytes("utf-8"));
                os.flush();

                os.write("123456789\n".getBytes("utf-8"));
                os.flush();
                TimeUnit.SECONDS.sleep(1);
                os.write("=========\n".getBytes("utf-8"));
                os.flush();
            }
            catch (InterruptedException | IOException e)
            {
                // Valid path, as write of second half of content can fail
                // e.printStackTrace(System.err);
            }
        });

        try (StacklessLogging ignore = new StacklessLogging(HttpChannel.class))
        {
            requestFuture.get(2, TimeUnit.SECONDS);
            responseFuture.get(3, TimeUnit.SECONDS);

            assertThat(response.toString(), containsString(" 500 "));
            assertThat(response.toString(), not(containsString("=========")));
        }
    }

     */
}
