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

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.QuietException;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Blocking;
import org.eclipse.jetty.util.BlockingArrayQueue;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.DateCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

public class CustomRequestLogTest
{
    private final BlockingQueue<String> _entries = new BlockingArrayQueue<>();
    private final BlockingQueue<Long> requestTimes = new BlockingArrayQueue<>();
    private CustomRequestLog _log;
    private Server _server;
    private LocalConnector _connector;
    private ServerConnector _serverConnector;
    private URI _serverURI;

    private static final long DELAY = 2000;

    @BeforeEach
    public void before()
    {
        _server = new Server();
        _connector = new LocalConnector(_server);
        _serverConnector = new ServerConnector(_server);
        _server.addConnector(_connector);
        _server.addConnector(_serverConnector);
    }

    void testHandlerServerStart(String formatString) throws Exception
    {
        _serverConnector.setPort(0);
        _serverConnector.getBean(HttpConnectionFactory.class).getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        TestRequestLogWriter writer = new TestRequestLogWriter();
        _log = new CustomRequestLog(writer, formatString);
        _server.setRequestLog(_log);
        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setHandler(new TestHandler());
        _server.setHandler(contextHandler);
        _server.start();

        String host = _serverConnector.getHost();
        if (host == null)
            host = "localhost";

        int localPort = _serverConnector.getLocalPort();
        _serverURI = new URI(String.format("http://%s:%d/", host, localPort));
    }

    @AfterEach
    public void after() throws Exception
    {
        _server.stop();
    }

    @Test
    public void testRequestFilter() throws Exception
    {
        AtomicReference<Boolean> logRequest = new AtomicReference<>();
        testHandlerServerStart("RequestPath: %U");
        _log.setFilter((request, response) -> logRequest.get());

        logRequest.set(true);
        _connector.getResponse("GET /path HTTP/1.0\n\n");
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("RequestPath: /path"));

        logRequest.set(false);
        _connector.getResponse("GET /path HTTP/1.0\n\n");
        assertNull(_entries.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @Disabled // TODO
    public void testLogRemoteUser() throws Exception
    {
        String authHeader = HttpHeader.AUTHORIZATION + ": Basic " + Base64.getEncoder().encodeToString("username:password".getBytes());
        testHandlerServerStart("%u %{d}u");

        _connector.getResponse("GET / HTTP/1.0\n\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("- -"));

        _connector.getResponse("GET / HTTP/1.0\n" + authHeader + "\n\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("- username"));

        _connector.getResponse("GET /secure HTTP/1.0\n" + authHeader + "\n\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("username username"));
    }

    @Test
    public void testModifier() throws Exception
    {
        testHandlerServerStart("%s: %!404,301{Referer}i");

        _connector.getResponse("GET /error404 HTTP/1.0\nReferer: testReferer\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("404: -"));

        _connector.getResponse("GET /error301 HTTP/1.0\nReferer: testReferer\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("301: -"));

        _connector.getResponse("GET /success HTTP/1.0\nReferer: testReferer\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("200: testReferer"));
    }

    @Test
    public void testDoublePercent() throws Exception
    {
        testHandlerServerStart("%%%%%%a");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("%%%a"));
    }

    @Test
    public void testLogAddress() throws Exception
    {
        testHandlerServerStart("%{local}a|%{local}p|" +
            "%{remote}a|%{remote}p|" +
            "%{server}a|%{server}p|" +
            "%{client}a|%{client}p");

        Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
        while (e.hasMoreElements())
        {
            NetworkInterface n = e.nextElement();
            if (n.isLoopback())
            {
                Enumeration<InetAddress> ee = n.getInetAddresses();
                while (ee.hasMoreElements())
                {
                    InetAddress i = ee.nextElement();
                    try (Socket client = newSocket(i.getHostAddress(), _serverURI.getPort()))
                    {
                        OutputStream os = client.getOutputStream();
                        String request = "GET / HTTP/1.0\n" +
                            "Host: webtide.com:1234\n" +
                            "Forwarded: For=10.1.2.3:1337\n" +
                            "\n\n";
                        os.write(request.getBytes(StandardCharsets.ISO_8859_1));
                        os.flush();

                        String[] log = Objects.requireNonNull(_entries.poll(5, TimeUnit.SECONDS)).split("\\|");
                        assertThat(log.length, is(8));

                        String localAddr = log[0];
                        String localPort = log[1];
                        String remoteAddr = log[2];
                        String remotePort = log[3];
                        String serverAddr = log[4];
                        String serverPort = log[5];
                        String clientAddr = log[6];
                        String clientPort = log[7];

                        assertThat(serverPort, is("1234"));
                        assertThat(clientPort, is("1337"));
                        assertThat(remotePort, not(clientPort));
                        assertThat(localPort, not(serverPort));

                        assertThat(serverAddr, is("webtide.com"));
                        assertThat(clientAddr, is("10.1.2.3"));
                        assertThat(InetAddress.getByName(remoteAddr), is(client.getInetAddress()));
                        assertThat(InetAddress.getByName(localAddr), is(i));
                    }
                }
            }
        }
    }

    @Test
    public void testLogBytesSent() throws Exception
    {
        testHandlerServerStart("BytesSent: %O");

        _connector.getResponse("GET / HTTP/1.0\necho: hello world\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesSent: 11"));
    }

    @Test
    public void testLogBytesReceived() throws Exception
    {
        testHandlerServerStart("BytesReceived: %I");

        _connector.getResponse("GET / HTTP/1.0\n" +
            "Content-Length: 11\n\n" +
            "hello world");

        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesReceived: 11"));
    }

    @Test
    public void testLogBytesTransferred() throws Exception
    {
        testHandlerServerStart("BytesTransferred: %S");

        _connector.getResponse("GET / HTTP/1.0\n" +
            "echo: hello world\n" +
            "Content-Length: 11\n\n" +
            "hello world");

        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("BytesTransferred: 22"));
    }

    @Test
    public void testLogRequestCookie() throws Exception
    {
        testHandlerServerStart("RequestCookies: %{cookieName}C, %{cookie2}C, %{cookie3}C");

        _connector.getResponse("GET / HTTP/1.0\nCookie: cookieName=cookieValue; cookie2=value2\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieValue, value2, -"));
    }

    @Test
    public void testLogRequestCookies() throws Exception
    {
        testHandlerServerStart("RequestCookies: %C");

        _connector.getResponse("GET / HTTP/1.0\nCookie: cookieName=cookieValue; cookie2=value2\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestCookies: cookieName=cookieValue;cookie2=value2"));
    }

    @Test
    public void testLogEnvironmentVar() throws Exception
    {
        testHandlerServerStart("EnvironmentVar: %{JAVA_HOME}e");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);

        String envVar = System.getenv("JAVA_HOME");
        assertThat(log, is("EnvironmentVar: " + ((envVar == null) ? "-" : envVar)));
    }

    @Test
    public void testLogRequestProtocol() throws Exception
    {
        testHandlerServerStart("%H");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("HTTP/1.0"));
    }

    @Test
    public void testLogRequestHeader() throws Exception
    {
        testHandlerServerStart("RequestHeader: %{Header1}i, %{Header2}i, %{Header3}i");

        _connector.getResponse("GET / HTTP/1.0\nHeader1: value1\nHeader2: value2\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestHeader: value1, value2, -"));
    }

    @Test
    public void testLogKeepAliveRequests() throws Exception
    {
        testHandlerServerStart("KeepAliveRequests: %k");

        LocalConnector.LocalEndPoint connect = _connector.connect();
        connect.addInput("""
            GET /a HTTP/1.0
            Connection: keep-alive

            """);
        connect.addInput("""
            GET /a HTTP/1.1
            Host: localhost

            """);

        assertThat(connect.getResponse(), containsString("200 OK"));
        assertThat(connect.getResponse(), containsString("200 OK"));

        connect.addInput("GET /a HTTP/1.0\n\n");
        assertThat(connect.getResponse(), containsString("200 OK"));

        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 1"));
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 2"));
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("KeepAliveRequests: 3"));
    }

    @Disabled
    @Test
    public void testLogKeepAliveRequestsHttp2() throws Exception
    {
        testHandlerServerStart("KeepAliveRequests: %k");
        fail();
    }

    @Test
    public void testLogRequestMethod() throws Exception
    {
        testHandlerServerStart("RequestMethod: %m");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestMethod: GET"));
    }

    @Test
    public void testLogResponseHeader() throws Exception
    {
        testHandlerServerStart("ResponseHeader: %{Header1}o, %{Header2}o, %{Header3}o");

        String response = _connector.getResponse("GET /responseHeaders HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("ResponseHeader: value1, value2, -"));
    }

    @Test
    public void testLogQueryString() throws Exception
    {
        testHandlerServerStart("QueryString: %q");

        _connector.getResponse("GET /path?queryString HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("QueryString: ?queryString"));
    }

    @Test
    public void testLogRequestFirstLine() throws Exception
    {
        testHandlerServerStart("RequestFirstLin: %r");

        _connector.getResponse("GET /path?query HTTP/1.0\nHeader: null\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("RequestFirstLin: GET /path?query HTTP/1.0"));
    }

    @Test
    public void testLogResponseStatus() throws Exception
    {
        testHandlerServerStart("LogResponseStatus: %s");

        _connector.getResponse("GET /error404 HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 404"));

        _connector.getResponse("GET /error301 HTTP/1.0\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 301"));

        _connector.getResponse("GET / HTTP/1.0\n\n");
        log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("LogResponseStatus: 200"));
    }

    @Test
    public void testLogRequestTime() throws Exception
    {
        testHandlerServerStart("RequestTime: %t");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        long requestTime = getTimeRequestReceived();
        DateCache dateCache = new DateCache(CustomRequestLog.DEFAULT_DATE_FORMAT, Locale.getDefault(), "GMT");
        assertThat(log, is("RequestTime: [" + dateCache.format(requestTime) + "]"));
    }

    @Test
    public void testLogRequestTimeCustomFormats() throws Exception
    {
        testHandlerServerStart("%{EEE MMM dd HH:mm:ss zzz yyyy}t\n" +
            "%{EEE MMM dd HH:mm:ss zzz yyyy|EST}t\n" +
            "%{EEE MMM dd HH:mm:ss zzz yyyy|EST|ja}t");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);
        long requestTime = getTimeRequestReceived();

        DateCache dateCache1 = new DateCache("EEE MMM dd HH:mm:ss zzz yyyy", Locale.getDefault(), "GMT");
        DateCache dateCache2 = new DateCache("EEE MMM dd HH:mm:ss zzz yyyy", Locale.getDefault(), "EST");
        DateCache dateCache3 = new DateCache("EEE MMM dd HH:mm:ss zzz yyyy", Locale.forLanguageTag("ja"), "EST");

        String[] logs = log.split("\n");
        assertThat(logs[0], is("[" + dateCache1.format(requestTime) + "]"));
        assertThat(logs[1], is("[" + dateCache2.format(requestTime) + "]"));
        assertThat(logs[2], is("[" + dateCache3.format(requestTime) + "]"));
    }

    @Test
    public void testLogLatencyMicroseconds() throws Exception
    {
        testHandlerServerStart("%{us}T");

        _connector.getResponse("GET /delay HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);
        long lowerBound = getTimeRequestReceived();
        long upperBound = System.currentTimeMillis();

        long measuredDuration = Long.parseLong(log);
        long durationLowerBound = TimeUnit.MILLISECONDS.toMicros(DELAY);
        long durationUpperBound = TimeUnit.MILLISECONDS.toMicros(upperBound - lowerBound);

        assertThat(measuredDuration, greaterThanOrEqualTo(durationLowerBound));
        assertThat(measuredDuration, lessThanOrEqualTo(durationUpperBound));
    }

    @Test
    public void testLogLatencyMilliseconds() throws Exception
    {
        testHandlerServerStart("%{ms}T");

        _connector.getResponse("GET /delay HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);
        long lowerBound = getTimeRequestReceived();
        long upperBound = System.currentTimeMillis();

        long measuredDuration = Long.parseLong(log);
        long durationLowerBound = DELAY;
        long durationUpperBound = upperBound - lowerBound;

        assertThat(measuredDuration, greaterThanOrEqualTo(durationLowerBound));
        assertThat(measuredDuration, lessThanOrEqualTo(durationUpperBound));
    }

    @Test
    public void testLogLatencySeconds() throws Exception
    {
        testHandlerServerStart("%{s}T");

        _connector.getResponse("GET /delay HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertNotNull(log);
        long lowerBound = getTimeRequestReceived();
        long upperBound = System.currentTimeMillis();

        long measuredDuration = Long.parseLong(log);
        long durationLowerBound = TimeUnit.MILLISECONDS.toSeconds(DELAY);
        long durationUpperBound = TimeUnit.MILLISECONDS.toSeconds(upperBound - lowerBound);

        assertThat(measuredDuration, greaterThanOrEqualTo(durationLowerBound));
        assertThat(measuredDuration, lessThanOrEqualTo(durationUpperBound));
    }

    @Test
    public void testLogUrlRequestPath() throws Exception
    {
        testHandlerServerStart("UrlRequestPath: %U");

        _connector.getResponse("GET /path?query HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        assertThat(log, is("UrlRequestPath: /path"));
    }

    @Test
    public void testLogConnectionStatus() throws Exception
    {
        testHandlerServerStart("%U ConnectionStatus: %s %X");

        _connector.getResponse("GET /one HTTP/1.0\n\n");
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/one ConnectionStatus: 200 -"));

        _connector.getResponse("""
            GET /two HTTP/1.1
            Host: localhost
            Connection: close

            """);
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/two ConnectionStatus: 200 -"));

        LocalConnector.LocalEndPoint connect = _connector.connect();
        connect.addInput("""
            GET /three HTTP/1.0
            Connection: keep-alive

            """);
        connect.addInput("""
            GET /four HTTP/1.1
            Host: localhost

            """);
        connect.addInput("""
            GET /five HTTP/1.1
            Host: localhost
            Connection: close

            """);
        assertThat(connect.getResponse(), containsString("200 OK"));
        assertThat(connect.getResponse(), containsString("200 OK"));
        assertThat(connect.getResponse(), containsString("200 OK"));
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/three ConnectionStatus: 200 +"));
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/four ConnectionStatus: 200 +"));
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/five ConnectionStatus: 200 -"));

        _connector.getResponse("""
            GET /no/host HTTP/1.1

            """);
        connect.getResponse();
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/no/host ConnectionStatus: 400 X"));

        _connector.getResponse("""
            GET /abort HTTP/1.1
            Host: localhost

            """);
        connect.getResponse();
        assertThat(_entries.poll(5, TimeUnit.SECONDS), is("/abort ConnectionStatus: 200 X"));
    }

    @Disabled // TODO
    @Test
    public void testLogRequestTrailer() throws Exception
    {
        testHandlerServerStart("%{trailerName}ti");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        fail(log);
    }

    @Disabled // TODO
    @Test
    public void testLogResponseTrailer() throws Exception
    {
        testHandlerServerStart("%{trailerName}to");

        _connector.getResponse("GET / HTTP/1.0\n\n");
        String log = _entries.poll(5, TimeUnit.SECONDS);
        fail(log);
    }

    protected Socket newSocket(String host, int port) throws Exception
    {
        Socket socket = new Socket(host, port);
        socket.setSoTimeout(10000);
        socket.setTcpNoDelay(true);
        return socket;
    }

    class TestRequestLogWriter implements RequestLog.Writer
    {
        @Override
        public void write(String requestEntry)
        {
            try
            {
                _entries.add(requestEntry);
            }
            catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private long getTimeRequestReceived() throws InterruptedException
    {
        Long requestTime = requestTimes.poll(5, TimeUnit.SECONDS);
        assertNotNull(requestTime);
        return requestTime;
    }

    private class TestHandler extends Handler.Processor
    {
        @Override
        public void process(Request request, Response response, Callback callback) throws Exception
        {
            requestTimes.offer(request.getTimeStamp());

            if (request.getPathInContext().contains("error404"))
            {
                response.setStatus(404);
            }
            else if (request.getPathInContext().contains("error301"))
            {
                response.setStatus(301);
            }
            else if (request.getHeaders().get("echo") != null)
            {
                try (Blocking.Callback blocker = Blocking.callback())
                {
                    response.write(false, blocker, String.valueOf(request.getHeaders().get("echo")));
                    blocker.block();
                }
            }
            else if (request.getPathInContext().contains("responseHeaders"))
            {
                response.addHeader("Header1", "value1");
                response.addHeader("Header2", "value2");
            }
            else if (request.getPathInContext().contains("/abort"))
            {
                response.write(false, Callback.from(() -> callback.failed(new QuietException.Exception("test fail")), callback::failed), "data");
                return;
            }
            else if (request.getPathInContext().contains("delay"))
            {
                try
                {
                    Thread.sleep(DELAY);
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
            }

            if (request.getContentLength() > 0)
                Content.Source.consumeAll(request);

            callback.succeeded();
        }
    }
}
