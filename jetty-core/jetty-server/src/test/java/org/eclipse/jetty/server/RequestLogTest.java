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

package org.eclipse.jetty.server;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.component.LifeCycle;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test raw behaviors of RequestLog and how Request / Response objects behave during
 * the call to a RequestLog implementation.
 * <p>
 * This differs from other RequestLog tests as it test the combination of
 * bad requests, and RequestLog implementations that can cause changes
 * in the request or response objects.
 * </p>
 */
public class RequestLogTest
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestLogTest.class);

    private static class NormalResponse extends Handler.Abstract
    {
        @Override
        public boolean handle(Request request, Response response, Callback callback) throws Exception
        {
            response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=UTF-8");
            Content.Sink.write(
                response, true, "Got %s to %s%n".formatted(request.getMethod(), request.getHttpURI()), callback);
            return true;
        }
    }

    public Server createServer(RequestLog requestLog, Handler serverHandler) throws Exception
    {
        Server server = new Server();
        ServerConnector connector = new ServerConnector(server);
        connector.setPort(0);
        server.addConnector(connector);

        server.setRequestLog(requestLog);
        server.setHandler(serverHandler);
        return server;
    }

    /**
     * Test a normal GET request.
     */
    @Test
    public void testNormalGetRequest() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer(
                (request, response1) -> requestLogLines.add(String.format(
                    "method:%s|uri:%s|status:%d",
                    request.getMethod(), request.getHttpURI(), response1.getStatus())),
                new NormalResponse());
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                String rawRequest =
                    """
                        GET /hello HTTP/1.1
                        Host: %s
                        Connection: close

                        """
                        .formatted(baseURI.getRawAuthority());
                out.write(rawRequest.getBytes(UTF_8));
                out.flush();

                String expectedURI = "http://%s/hello".formatted(baseURI.getRawAuthority());
                HttpTester.Response response = HttpTester.parseResponse(in);

                // Find status code
                assertThat("Status Code Response", response.getStatus(), is(200));

                // Find body content (always last line)
                String bodyContent = response.getContent();
                assertThat("Body Content", bodyContent, containsString("Got GET to " + expectedURI));

                String reqlog = requestLogLines.poll(5, TimeUnit.SECONDS);
                assertThat("RequestLog", reqlog, containsString("method:GET|uri:%s|status:200".formatted(expectedURI)));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Test an unread HTTP/1.1 POST, it has valid body content, the dispatched Handler on the server doesn't read the POST body content.
     * The RequestLog accidentally attempts to read the Request body content due to the use of Request.getParameterNames() API.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {"/hello", "/hello?a=b"})
    public void testNormalPostFormRequest(String requestPath) throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer(
                (request, response1) ->
                {
                    try
                    {
                        // Use API that would trigger a read of the request
                        Fields params = Request.getParameters(request);

                        // This should result in only params from the query string, not from request body, as
                        // nothing is read during RequestLog execution
                        requestLogLines.add(String.format(
                            "method:%s|uri:%s|params.size:%d|status:%d",
                            request.getMethod(),
                            request.getHttpURI(),
                            params.getSize(),
                            response1.getStatus()));
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                },
                new NormalResponse());
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                StringBuilder form = new StringBuilder();
                for (int i = 'a'; i < 'j'; i++)
                {
                    form.append((char)i).append("=").append(i).append("&");
                }

                byte[] bufForm = form.toString().getBytes(UTF_8);

                String rawRequest =
                    """
                        POST %s HTTP/1.1
                        Host: %s
                        Content-Type: application/x-www-form-urlencoded
                        Content-Length: %d
                        Connection: close

                        """
                        .formatted(requestPath, baseURI.getRawAuthority(), bufForm.length);

                out.write(rawRequest.getBytes(UTF_8));
                out.write(bufForm);
                out.flush();

                String expectedURI = "http://%s%s".formatted(baseURI.getRawAuthority(), requestPath);
                HttpTester.Response response = HttpTester.parseResponse(in);

                // Find status code
                assertThat("Status Code Response", response.getStatus(), is(200));

                // Find body content (always last line)
                assertThat("Body Content", response.getContent(), containsString("Got POST to " + expectedURI));

                String reqlog = requestLogLines.poll(5, TimeUnit.SECONDS);
                int querySize = 0;
                if (requestPath.contains("?"))
                    querySize = 1; // assuming that parameterized version only has 1 query value
                assertThat(
                    "RequestLog",
                    reqlog,
                    containsString(
                        "method:POST|uri:%s|params.size:%d|status:200".formatted(expectedURI, querySize)));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Test a Bad HTTP/1.1 POST Request, it has body content, but also includes a Content-Length + Transfer-Encoding header.
     * This results in a BadMessage internally, and the Handler is never called.
     * The POST body content is never read by a Handler or the error handling code.
     * The RequestLog accidentally attempts to read the Request body content due to the use of Request.getParameterNames() API.
     */
    @Test
    public void testBadPostFormRequest() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            server = createServer(
                (request, response1) ->
                {
                    try
                    {
                        // Use API that would trigger a read of the request
                        Fields params = Request.getParameters(request);

                        // This should result in no params, as nothing is read during RequestLog execution
                        requestLogLines.add(String.format(
                            "method:%s|uri:%s|params.size:%d|status:%d",
                            request.getMethod(),
                            request.getHttpURI(),
                            params.getSize(),
                            response1.getStatus()));
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                },
                new NormalResponse());
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                StringBuilder form = new StringBuilder();
                for (int i = 'a'; i < 'j'; i++)
                {
                    form.append((char)i).append("=").append(i).append("&");
                }

                byte[] bufForm = form.toString().getBytes(UTF_8);

                String rawRequest =
                    """
                        POST /hello HTTP/1.1
                        Host: %s
                        Content-Type: application/x-www-form-urlencoded
                        Content-Length: %d
                        Transfer-Encoding: chunked
                        Connection: close

                        """
                        .formatted(baseURI.getRawAuthority(), bufForm.length);

                out.write(rawRequest.getBytes(UTF_8));
                out.write(bufForm);
                out.flush();

                HttpTester.Response response = HttpTester.parseResponse(in);

                // Find status code
                assertThat("Status Code Response", response.getStatus(), is(400));

                // Find body content (always last line)
                assertThat(
                    "Body Content",
                    response.getContent(),
                    containsString("<td>Transfer-Encoding and Content-Length</td>"));

                // We should see a requestlog entry for this 400 response
                String reqlog = requestLogLines.poll(3, TimeUnit.SECONDS);
                assertThat("RequestLog", reqlog, containsString("method:POST|uri:/hello|params.size:0|status:400"));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Test where the response is committed, then the dispatch changes the status code and response headers.
     * The RequestLog should see the committed status code and committed headers, not the changed ones.
     */
    @Test
    public void testResponseThenChangeStatusAndHeaders() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            RequestLog requestLog = (request, response) ->
            {
                String xname = response.getHeaders().get("X-Name");
                requestLogLines.add(String.format(
                    "method:%s|uri:%s|header[x-name]:%s|status:%d",
                    request.getMethod(), request.getHttpURI(), xname, response.getStatus()));
            };

            Handler handler = new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    response.setStatus(202);
                    response.getHeaders().put("X-Name", "actual");
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");

                    String msg = "Got %s to %s%n".formatted(request.getMethod(), request.getHttpURI());
                    Callback testCallback = Callback.from(callback, () ->
                    {
                        assertTrue(response.isCommitted(), "Response should be committed");
                        // This shouldn't change the status for the RequestLog output
                        response.setStatus(204);
                        // attempting to set a response header after commit shouldn't be possible
                        UnsupportedOperationException unsupported =
                            assertThrows(UnsupportedOperationException.class, () ->
                            {
                                response.getHeaders().put("X-Name", "post-commit");
                            });
                        assertThat(unsupported.getMessage(), is("Read Only"));
                        // finish response
                        response.write(true, null, callback);
                    });
                    Content.Sink.write(response, false, msg, testCallback);
                    return true;
                }
            };

            server = createServer(requestLog, handler);
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                String rawRequest =
                    """
                        GET /world HTTP/1.1
                        Host: %s
                        Connection: close

                        """
                        .formatted(baseURI.getRawAuthority());

                out.write(rawRequest.getBytes(UTF_8));
                out.flush();

                String expectedURI = "http://%s/world".formatted(baseURI.getRawAuthority());
                HttpTester.Response response = HttpTester.parseResponse(in);

                // Find status code
                assertThat("Status Code Response", response.getStatus(), is(202));

                // Find body content (always last line)
                assertThat("Body Content", response.getContent(), containsString("Got GET to " + expectedURI));

                // We should see a requestlog entry for the original 202 response
                String reqlog = requestLogLines.poll(3, TimeUnit.SECONDS);
                assertThat(
                    "RequestLog",
                    reqlog,
                    containsString("method:GET|uri:%s|header[x-name]:actual|status:202".formatted(expectedURI)));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }

    /**
     * Test where the request local-address and remote-address is accessible during RequestLog.
     * Requires that the EndPoint is closed before the RequestLog can execute.
     */
    @Test
    public void testLogRemoteAndLocalAddressesAfterClose() throws Exception
    {
        Server server = null;
        try
        {
            BlockingArrayQueue<String> requestLogLines = new BlockingArrayQueue<>();

            RequestLog requestLog = (request, response) ->
            {
                SocketAddress remoteAddress = request.getConnectionMetaData().getRemoteSocketAddress();
                SocketAddress localAddress = request.getConnectionMetaData().getLocalSocketAddress();
                requestLogLines.add(String.format(
                    "method:%s|uri:%s|remote-addr:%s|local-addr:%s|status:%d",
                    request.getMethod(), request.getHttpURI(), remoteAddress, localAddress, response.getStatus()));
            };

            Handler handler = new Handler.Abstract()
            {
                @Override
                public boolean handle(Request request, Response response, Callback callback) throws Exception
                {
                    response.setStatus(202);
                    response.getHeaders()
                        .put(
                            "X-RemoteAddr",
                            Objects.toString(
                                request.getConnectionMetaData().getRemoteSocketAddress(), "<null>"));
                    response.getHeaders()
                        .put(
                            "X-LocalAddr",
                            Objects.toString(
                                request.getConnectionMetaData().getLocalSocketAddress(), "<null>"));
                    response.getHeaders().put(HttpHeader.CONTENT_TYPE, "text/plain; charset=utf-8");

                    String msg = "Got %s to %s%n".formatted(request.getMethod(), request.getHttpURI());
                    Callback testCallback = Callback.from(callback, () ->
                    {
                        EndPoint endPoint =
                            request.getConnectionMetaData().getConnection().getEndPoint();
                        // Close connection
                        endPoint.close();
                        // Wait for endpoint to be closed
                        Awaitility.await().atMost(Duration.ofSeconds(5)).until(() -> !endPoint.isOpen());
                    });
                    Content.Sink.write(response, true, msg, testCallback);
                    return true;
                }
            };

            server = createServer(requestLog, handler);
            server.start();

            URI baseURI = server.getURI();

            try (Socket socket = new Socket(baseURI.getHost(), baseURI.getPort());
                 OutputStream out = socket.getOutputStream();
                 InputStream in = socket.getInputStream())
            {
                String rawRequest =
                    """
                        GET /world HTTP/1.1
                        Host: %s
                        Connection: close

                        """
                        .formatted(baseURI.getRawAuthority());

                out.write(rawRequest.getBytes(UTF_8));
                out.flush();

                String expectedURI = "http://%s/world".formatted(baseURI.getRawAuthority());
                HttpTester.Response response = HttpTester.parseResponse(in);

                // Find status code
                assertThat("Status Code Response", response.getStatus(), is(202));

                // Find body content (always last line)
                assertThat("Body Content", response.getContent(), containsString("Got GET to " + expectedURI));

                String remoteAddrStr = response.get("X-RemoteAddr");
                String localAddrStr = response.get("X-LocalAddr");

                assertThat(remoteAddrStr, not(containsString("<null>")));
                assertThat(localAddrStr, not(containsString("<null>")));

                // We should see a requestlog entry for this 400 response
                String reqlog = requestLogLines.poll(3, TimeUnit.SECONDS);
                assertThat(
                    "RequestLog",
                    reqlog,
                    containsString("method:GET|uri:%s|remote-addr:%s|local-addr:%s|status:202"
                        .formatted(expectedURI, remoteAddrStr, localAddrStr)));
            }
        }
        finally
        {
            LifeCycle.stop(server);
        }
    }
}
