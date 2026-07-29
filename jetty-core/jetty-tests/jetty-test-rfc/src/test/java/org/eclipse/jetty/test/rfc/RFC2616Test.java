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

package org.eclipse.jetty.test.rfc;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpParser;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.http.pathmap.PathSpec;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.rewrite.handler.RedirectRegexRule;
import org.eclipse.jetty.rewrite.handler.RewriteHandler;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ConditionalHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.DumpHandler;
import org.eclipse.jetty.server.handler.EchoHandler;
import org.eclipse.jetty.server.handler.PathMappingsHandler;
import org.eclipse.jetty.server.handler.ResourceHandler;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@SuppressWarnings("checkstyle:MethodName")
public class RFC2616Test
{
    private Server server;
    private LocalConnector connector;

    @BeforeEach
    public void init() throws Exception
    {
        server = new Server();
        connector = new LocalConnector(server);
        HttpConfiguration httpConfiguration = connector.getConnectionFactory(HttpConfiguration.ConnectionFactory.class).getHttpConfiguration();
        httpConfiguration.setHttpCompliance(HttpCompliance.RFC2616);
        httpConfiguration.setUriCompliance(UriCompliance.JETTY_11);
        httpConfiguration.setSendDateHeader(true);
        connector.setIdleTimeout(10000);
        server.addConnector(connector);

        ConditionalHandler options = new ConditionalHandler.ElseNext()
        {
            final DefaultHandler optionsHandler = new DefaultHandler();
            {
                installBean(optionsHandler);
            }

            @Override
            public void setServer(Server server)
            {
                super.setServer(server);
                optionsHandler.setServer(server);
            }

            @Override
            protected boolean onConditionsMet(Request request, Response response, Callback callback) throws Exception
            {
                return optionsHandler.handle(request, response, callback);
            }
        };
        options.includeMethod("OPTIONS");

        server.setHandler(options);

        RewriteHandler rewrite = new RewriteHandler();
        rewrite.addRule(new RedirectRegexRule("/redirect/(.*)", "/tests/$1"));
        options.setHandler(rewrite);

        PathMappingsHandler pathMappings = new PathMappingsHandler.NoContext();
        rewrite.setHandler(pathMappings);

        pathMappings.addMapping(PathSpec.from("/echo/*"), new EchoHandler());

        ResourceHandler resourceHandler = new ResourceHandler();
        resourceHandler.setAcceptRanges(true);
        ContextHandler staticContext = new ContextHandler(resourceHandler, "/static");
        staticContext.setBaseResource(ResourceFactory.of(server).newClassLoaderResource("/static/"));
        pathMappings.addMapping(PathSpec.from("/static/*"), staticContext);

        ContextHandler vcontext = new ContextHandler();
        vcontext.setContextPath("/");
        vcontext.setVirtualHosts(List.of("VirtualHost"));
        vcontext.setHandler(new DumpHandler("Virtual Dump"));

        ContextHandler context = new ContextHandler();
        context.setContextPath("/");
        context.setHandler(new DumpHandler());

        pathMappings.addMapping(PathSpec.from("/"), new Handler.Sequence(vcontext, context));

        server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        server.stop();
        server.join();
    }

    @Test
    public void testFolded_2_2() throws Exception
    {
        String req = """
            GET http://localhost/path HTTP/1.1
            Host: localhost
            Folded: value
             over
             many
             lines
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("value over many lines"));
    }

    @Test
    public void test33()
    {
        try
        {
            HttpFields.Mutable fields = HttpFields.build()
                .put("D1", "Sun, 6 Nov 1994 08:49:37 GMT")
                .put("D2", "Sunday, 6-Nov-94 08:49:37 GMT")
                .put("D3", "Sun Nov  6 08:49:37 1994");
            Date d1 = new Date(fields.getDateField("D1"));
            Date d2 = new Date(fields.getDateField("D2"));
            Date d3 = new Date(fields.getDateField("D3"));

            assertEquals(d2, d1, "3.3.1 RFC 822 RFC 850");
            assertEquals(d3, d2, "3.3.1 RFC 850 ANSI C");

            fields.putDate("Date", d1.getTime());
            assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", fields.get("Date"), "3.3.1 RFC 822 preferred");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            fail();
        }
    }

    @Test
    public void test332()
    {
        try
        {
            String get = connector.getResponse("GET /R1 HTTP/1.0\n" + "Host: localhost\n" + "\n");
            checkContains(get, 0, "HTTP/1.1 200", "GET");
            checkContains(get, 0, "Content-Type: text/html", "GET _content");
            checkContains(get, 0, "<html>", "GET body");
            int cli = get.indexOf("Content-Length");
            String contentLength = get.substring(cli, get.indexOf("\r", cli));

            String head = connector.getResponse("HEAD /R1 HTTP/1.0\n" + "Host: localhost\n" + "\n");
            checkContains(head, 0, "HTTP/1.1 200", "HEAD");
            checkContains(head, 0, "Content-Type: text/html", "HEAD _content");
            assertEquals(-1, head.indexOf("<html>"), "HEAD no body");
            checkContains(head, 0, contentLength, "3.3.2 HEAD");
        }
        catch (Exception e)
        {
            e.printStackTrace();
            assertTrue(false);
        }
    }

    @Test
    public void test36a() throws Exception
    {
        int offset = 0;
        // Chunk last
        String response = connector.getResponse(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked,identity\n" +
                "Content-Type: text/plain\n" +
                "\r\n" +
                "5\r\n" +
                "123\r\n\r\n" +
                "0\r\n\r\n");
        checkContains(response, offset, "HTTP/1.1 400 Bad", "Chunked last");
    }

    @Test
    public void test36b() throws Exception
    {
        String response;
        int offset = 0;
        // Chunked
        LocalEndPoint endp = connector.executeRequest(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "2\r\n" +
                "12\r\n" +
                "3\r\n" +
                "345\r\n" +
                "0\r\n\r\n" +

                "GET /R2 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "4\r\n" +
                "6789\r\n" +
                "5\r\n" +
                "abcde\r\n" +
                "0\r\n\r\n" +

                "GET /R3 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Connection: close\n" +
                "\n");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200", "3.6.1 Chunking");
        offset = checkContains(response, offset, "12345", "3.6.1 Chunking");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200", "3.6.1 Chunking");
        offset = checkContains(response, offset, "6789abcde", "3.6.1 Chunking");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "/R3", "3.6.1 Chunking");
    }

    @Test
    public void test36c() throws Exception
    {
        String response;
        int offset = 0;
        LocalEndPoint endp = connector.executeRequest(
            "POST /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "3\r\n" +
                "fgh\r\n" +
                "3\r\n" +
                "Ijk\r\n" +
                "0\r\n\r\n" +

                "POST /R2 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "\n" +
                "4\r\n" +
                "lmno\r\n" +
                "5\r\n" +
                "Pqrst\r\n" +
                "0\r\n\r\n" +

                "GET /R3 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Connection: close\n" +
                "\n");
        offset = 0;
        response = endp.getResponse();
        checkNotContained(response, "HTTP/1.1 100", "3.6.1 Chunking");
        offset = checkContains(response, offset, "HTTP/1.1 200", "3.6.1 Chunking");
        offset = checkContains(response, offset, "fghIjk", "3.6.1 Chunking");
        offset = 0;
        response = endp.getResponse();
        checkNotContained(response, "HTTP/1.1 100", "3.6.1 Chunking");
        offset = checkContains(response, offset, "HTTP/1.1 200", "3.6.1 Chunking");
        offset = checkContains(response, offset, "lmnoPqrst", "3.6.1 Chunking");
        offset = 0;
        response = endp.getResponse();
        checkNotContained(response, "HTTP/1.1 100", "3.6.1 Chunking");
        offset = checkContains(response, offset, "/R3", "3.6.1 Chunking");
    }

    @Test
    public void test36d() throws Exception
    {
        String response;
        int offset = 0;
        // Chunked and keep alive
        LocalEndPoint endp = connector.executeRequest(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "Connection: keep-alive\n" +
                "\n" +
                "3\r\n" +
                "123\r\n" +
                "3\r\n" +
                "456\r\n" +
                "0\r\n\r\n" +

                "GET /R2 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Connection: close\n" +
                "\n");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200", "3.6.1 Chunking") + 10;
        offset = checkContains(response, offset, "123456", "3.6.1 Chunking");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "/R2", "3.6.1 Chunking") + 10;
    }

    @Test
    public void test39() throws Exception
    {
        HttpFields fields = HttpFields.build()
            .put("Q", "bbb;q=0.5,aaa,ccc;q=0.002,d;q=0,e;q=0.0001,ddd;q=0.001,aa2,abb;q=0.7").asImmutable();
        List<String> list = fields.getQualityCSV("Q");
        assertEquals("aaa", HttpField.getValueParameters(list.get(0), null), "Quality parameters");
        assertEquals("aa2", HttpField.getValueParameters(list.get(1), null), "Quality parameters");
        assertEquals("abb", HttpField.getValueParameters(list.get(2), null), "Quality parameters");
        assertEquals("bbb", HttpField.getValueParameters(list.get(3), null), "Quality parameters");
        assertEquals("ccc", HttpField.getValueParameters(list.get(4), null), "Quality parameters");
        assertEquals("ddd", HttpField.getValueParameters(list.get(5), null), "Quality parameters");
    }

    @Test
    public void test41a() throws Exception
    {
        int offset = 0;

        // If _content length not used, second request will not be read.
        LocalEndPoint endp = connector.executeRequest(
            "\r\n" +
                "GET /R1 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "GET /R2 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                " \r\n" +
                "GET /R3 HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n"
        );

        String response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200 OK", "2. identity") + 10;
        offset = checkContains(response, offset, "/R1", "2. identity") + 3;

        response = endp.getResponse();
        offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200 OK", "2. identity") + 10;
        offset = checkContains(response, offset, "/R2", "2. identity") + 3;

        response = endp.getResponse();
        offset = 0;
        checkNotContained(response, offset, "HTTP/1.1 200 OK", "2. identity");
        checkNotContained(response, offset, "/R3", "2. identity");
    }

    @Test
    public void test44b() throws Exception
    {
        String response;
        int offset = 0;
        // If _content length not used, second request will not be read.
        LocalEndPoint endp = connector.executeRequest(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: identity\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 5\n" +
                "\n" +
                "123\r\n" +
                "GET /R2 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: other\n" +
                "Connection: close\n" +
                "\n");
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 400 ", "2. identity") + 10;
        offset = 0;
        response = endp.getResponse();
        assertThat("There should be no next response as first one closed connection", response, is(nullValue()));
    }

    @Test
    public void test44c() throws Exception
    {
        // Due to smuggling concerns, handling has been changed to
        // treat content length and chunking as a bad request.
        int offset = 0;
        String response;
        LocalEndPoint endp = connector.executeRequest(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Transfer-Encoding: chunked\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 100\n" +
                "\n" +
                "3\n" +
                "123\n" +
                "3\n" +
                "456\n" +
                "0\n" +
                "\n" +

                "GET /R2 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Connection: close\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 6\n" +
                "\n" +
                "abcdef");
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 400 ", "3. ignore c-l") + 1;
        checkNotContained(response, offset, "/R2", "3. _content-length");
    }

    @Test
    public void test44d() throws Exception
    {
        // No _content length
        assertTrue(true, "Skip 411 checks as IE breaks this rule");
        // offset=0; connector.reopen();
        // response=connector.getResponse("GET /R2 HTTP/1.1\n"+
        // "Host: localhost\n"+
        // "Content-Type: text/plain\n"+
        // "Connection: close\n"+
        // "\n"+
        // "123456");
        // offset=checkContains(response,offset,
        // "HTTP/1.1 411 ","411 length required")+10;
        // offset=0; connector.reopen();
        // response=connector.getResponse("GET /R2 HTTP/1.0\n"+
        // "Content-Type: text/plain\n"+
        // "\n"+
        // "123456");
        // offset=checkContains(response,offset,
        // "HTTP/1.0 411 ","411 length required")+10;

    }

    @Test
    public void testUserInfo_5_1_2() throws Exception
    {
        String req = """
            GET http://username:password@host:8888/R1.txt HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req));

        assertThat(response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * @throws Exception if there is an unspecified problem
     * @see <a href="https://www.rfc-editor.org/rfc/rfc2616#section-5.2">RFC 2616 - Section 5.2 - The Resource Identified by a Request</a>
     */
    @Test
    public void test52a() throws Exception
    {
        // Default Host
        int offset = 0;
        String response = connector.getResponse(
            "GET http://VirtualHost:8888/path/R1 HTTP/1.1\n" +
            "Host: wronghost\n" +
            "Connection: close\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "Virtual host") + 1;
        offset = checkContains(response, offset, "Virtual Dump", "Virtual host") + 1;
        offset = checkContains(response, offset, "pathInContext=/path/R1", "Virtual host") + 1;
        offset = checkContains(response, offset, "servername=VirtualHost", "Virtual host") + 1;
    }

    @Test
    public void test52b() throws Exception
    {
        // Default Host
        int offset = 0;
        String response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "Default host") + 1;
        offset = checkContains(response, offset, "Dump Handler", "Default host") + 1;
        offset = checkContains(response, offset, "pathInContext=/path/R1", "Default host") + 1;

        // Virtual Host
        offset = 0;
        response = connector.getResponse("GET /path/R2 HTTP/1.1\n" + "Host: VirtualHost\n" + "Connection: close\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "Default host") + 1;
        offset = checkContains(response, offset, "Virtual Dump", "virtual host") + 1;
        offset = checkContains(response, offset, "pathInContext=/path/R2", "Default host") + 1;
    }

    @Test
    public void test52c() throws Exception
    {
        // Virtual Host
        int offset = 0;
        String response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "Host: VirtualHost\n" + "Connection: close\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "2. virtual host field") + 1;
        offset = checkContains(response, offset, "Virtual Dump", "2. virtual host field") + 1;
        offset = checkContains(response, offset, "pathInContext=/path/R1", "2. virtual host field") + 1;

        // Virtual Host case insensitive
        offset = 0;
        response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "Host: ViRtUalhOst\n" + "Connection: close\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "2. virtual host field") + 1;
        offset = checkContains(response, offset, "Virtual Dump", "2. virtual host field") + 1;
        offset = checkContains(response, offset, "pathInContext=/path/R1", "2. virtual host field") + 1;

        // Virtual Host
        offset = 0;
        response = connector.getResponse("GET /path/R1 HTTP/1.1\n" + "\n");
        offset = checkContains(response, offset, "HTTP/1.1 400", "3. no host") + 1;
    }

    @Test
    public void test81() throws Exception
    {
        int offset = 0;
        String response = connector.getResponse("GET /R1 HTTP/1.1\n" + "Host: localhost\n" + "\n", 250, TimeUnit.MILLISECONDS);
        offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "8.1.2 default") + 10;
        checkContains(response, offset, "Content-Length: ", "8.1.2 default");

        LocalEndPoint endp = connector.executeRequest("GET /R1 HTTP/1.1\n" + "Host: localhost\n" + "\n" +
            "GET /R2 HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n" +
            "GET /R3 HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");

        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "8.1.2 default") + 1;
        offset = checkContains(response, offset, "/R1", "8.1.2 default") + 1;
        offset = 0;
        response = endp.getResponse();
        offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "8.1.2.2 pipeline") + 11;
        offset = checkContains(response, offset, "Connection: close", "8.1.2.2 pipeline") + 1;
        offset = checkContains(response, offset, "/R2", "8.1.2.1 close") + 3;

        offset = 0;
        response = endp.getResponse();
        assertThat(response, nullValue());
    }

    /**
     * Test Message Transmission Requirements -- Bad client behaviour, invalid Expect header.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test82ExpectInvalid() throws Exception
    {
        // Expect Failure

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /echo/R1 HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Expect: unknown\n"); // Invalid Expect header.
        req2.append("Content-Type: text/plain\n");
        req2.append("Content-Length: 8\n");
        req2.append("\n");
        req2.append("12345678\n");

        LocalEndPoint endp = connector.executeRequest(req2.toString());
        HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());

        assertThat("8.2.3 expect failure", response.getStatus(), is(HttpStatus.EXPECTATION_FAILED_417));
    }

    /**
     * Test Message Transmission Requirements -- Acceptable bad client behavior, Expect 100 with body content.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test82ExpectWithBody() throws Exception
    {
        // Expect with body

        StringBuffer req3 = new StringBuffer();
        req3.append("GET /echo/R1 HTTP/1.1\n");
        req3.append("Host: localhost\n");
        req3.append("Expect: 100-continue\n"); // Valid Expect header.
        req3.append("Content-Type: text/plain\n");
        req3.append("Content-Length: 8\n");
        req3.append("Connection: close\n");
        req3.append("\n");
        req3.append("123456\r\n"); // Body

        // Should only expect 1 response.
        // The existence of 2 responses usually means a bad "HTTP/1.1 100" was received.
        LocalEndPoint endp = connector.executeRequest(req3.toString());
        HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());

        assertThat("8.2.3 expect 100", response.getStatus(), is(HttpStatus.OK_200));
    }

    /**
     * Test Message Transmission Requirements -- Acceptable bad client behavior, Expect 100 with body content.
     *
     * @throws Exception failure
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test82UnexpectWithBody() throws Exception
    {
        // Expect with body

        String request1 = """
            GET /redirect/R1 HTTP/1.1\r
            Host: localhost\r
            Expect: 100-continue\r
            Content-Type: text/plain\r
            Content-Length: 8\r
            \r
            123456\r
            GET /echo/R1 HTTP/1.1\r
            Host: localhost\r
            Content-Type: text/plain\r
            Content-Length: 8\r
            Connection: close\r
            \r
            87654321
            """;

        LocalEndPoint endp = connector.executeRequest(request1);
        HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());
        assertThat(response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(response.get(HttpHeader.CONNECTION), nullValue());
    }

    /**
     * Test Message Transmission Requirements
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-8.2">RFC 2616 (section 8.2)</a>
     */
    @Test
    public void test82ExpectNormal() throws Exception
    {
        // Expect 100

        StringBuilder req4 = new StringBuilder();
        req4.append("GET /echo/R1 HTTP/1.1\n");
        req4.append("Host: localhost\n");
        req4.append("Connection: close\n");
        req4.append("Expect: 100-continue\n"); // Valid Expect header.
        req4.append("Content-Type: text/plain\n");
        req4.append("Content-Length: 7\n");
        req4.append("\n"); // No body

        LocalEndPoint endp = connector.executeRequest(req4.toString());

        String response = endp.getResponse();
        assertThat(response, startsWith("HTTP/1.1 100 Continue"));

        endp.addInput("6543210");

        response = endp.getResponse();
        assertThat(response, startsWith("HTTP/1.1 200 OK"));
        assertThat(response, containsString("6543210"));
    }

    @Test
    public void test823dash5() throws Exception
    {
        // Expect with body: client sends the content right away, we should not send 100-Continue
        int offset = 0;
        String response = connector.getResponse(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Expect: 100-continue\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 8\n" +
                "Connection: close\n" +
                "\n" +
                "123456\r\n");
        checkNotContained(response, offset, "HTTP/1.1 100 ", "8.2.3 expect 100");
        offset = checkContains(response, offset, "HTTP/1.1 200 OK", "8.2.3 expect with body") + 1;
    }

    @Test
    public void test823() throws Exception
    {
        int offset = 0;
        // Expect 100
        LocalConnector.LocalEndPoint endp = connector.executeRequest("GET /R1 HTTP/1.1\n" +
            "Host: localhost\n" +
            "Connection: close\n" +
            "Expect: 100-continue\n" +
            "Content-Type: text/plain\n" +
            "Content-Length: 8\n" +
            "\n");
        String infomational = endp.getResponse();
        offset = checkContains(infomational, offset, "HTTP/1.1 100 ", "8.2.3 expect 100") + 1;
        checkNotContained(infomational, offset, "HTTP/1.1 200", "8.2.3 expect 100");
        endp.addInput("654321\r\n");
        String response = endp.getResponse();
        offset = 0;
        offset = checkContains(response, offset, "HTTP/1.1 200", "8.2.3 expect 100") + 1;
        offset = checkContains(response, offset, "654321", "8.2.3 expect 100") + 1;
    }

    @Test
    public void test823No100Response() throws Exception
    {
        // Expect 100 not sent
        int offset = 0;
        String response = connector.getResponse("GET /R1?error=401 HTTP/1.1\n" +
            "Host: localhost\n" +
            "Expect: 100-continue\n" +
            "Content-Type: text/plain\n" +
            "Content-Length: 8\n" +
            "\n");
        checkNotContained(response, offset, "HTTP/1.1 100", "8.2.3 expect 100");
        offset = checkContains(response, offset, "HTTP/1.1 401 ", "8.2.3 expect 100") + 1;
        offset = checkContains(response, offset, "Connection: close", "8.2.3 expect 100") + 1;
    }

    @Test
    public void test92() throws Exception
    {
        int offset = 0;

        String response = connector.getResponse("OPTIONS * HTTP/1.1\n" +
            "Connection: close\n" +
            "Host: localhost\n" +
            "\n");
        offset = checkContains(response, offset, "HTTP/1.1 200", "200") + 1;

        offset = 0;
        response = connector.getResponse("GET * HTTP/1.1\n" +
            "Connection: close\n" +
            "Host: localhost\n" +
            "\n");
        offset = checkContains(response, offset, "HTTP/1.1 400", "400") + 1;
    }

    /**
     * Test OPTIONS (HTTP) method - Server Options
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-9.2">RFC 2616 (section 9.2)</a>
     */
    @Test
    public void test92DefaultOptions() throws Exception
    {
        // Server OPTIONS

        StringBuffer req1 = new StringBuffer();
        req1.append("OPTIONS * HTTP/1.1\n"); // Apply to server in general, rather than a specific resource
        req1.append("Connection: close\n");
        req1.append("Host: localhost\n");
        req1.append("\n");

        LocalEndPoint endp = connector.executeRequest(req1.toString());
        HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());

        assertThat("9.2 OPTIONS", response.getStatus(), is(HttpStatus.OK_200));
        String allow = response.get("Allow");
        assertNotNull(allow);
        String[] expectedMethods =
            {"GET", "HEAD", "OPTIONS"};
        for (String expectedMethod : expectedMethods)
        {
            assertThat(allow, containsString(expectedMethod));
        }
        assertEquals("0", response.get("Content-Length")); // Required if no response body.
    }

    @Test
    public void test94() throws Exception
    {
        String get = connector.getResponse("GET /R1 HTTP/1.0\n" + "Host: localhost\n" + "\n");

        checkContains(get, 0, "HTTP/1.1 200", "GET");
        checkContains(get, 0, "Content-Type: text/html", "GET _content");
        checkContains(get, 0, "<html>", "GET body");
        get = get.replaceAll("Date: .* GMT\r\n", "Date: GMT\r\n");

        String head = connector.getResponse("HEAD /R1 HTTP/1.0\n" + "Host: localhost\n" + "\n");
        checkContains(head, 0, "HTTP/1.1 200", "HEAD");
        checkContains(head, 0, "Content-Type: text/html", "HEAD _content");
        assertEquals(-1, head.indexOf("<html>"), "HEAD no body");
        head = head.replaceAll("Date: .* GMT\r\n", "Date: GMT\r\n");

        assertThat(get, startsWith(head));
    }

    /**
     * Test 206 Partial Content (Response Code)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.2.7">RFC 2616 (section 10.2.7)</a>
     */
    @Test
    public void test1027() throws Exception
    {
        // check to see if corresponding GET w/o range would return
        // a) ETag
        // b) Content-Location
        // these same headers will be required for corresponding
        // sub range requests

        StringBuffer req1 = new StringBuffer();
        req1.append("GET /static/alpha.txt HTTP/1.1\n");
        req1.append("Host: localhost\n");
        req1.append("Connection: close\n");
        req1.append("\n");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1.toString()));
        assertThat(response.getContent(), not(emptyString()));

        boolean noRangeHasContentLocation = (response.get("Content-Location") != null);
        boolean noRangeHasETag = (response.get("ETag") != null);

        // now try again for the same resource but this time WITH range header
        StringBuffer req2 = new StringBuffer();
        req2.append("GET /static/alpha.txt HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("Range: bytes=1-3\n"); // request first 3 bytes
        req2.append("\n");

        response = HttpTester.parseResponse(connector.getResponse(req2.toString()));

        assertThat("10.2.7 Partial Content", response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));

        // (point 1) A 206 response MUST contain either a Content-Range header
        // field (section 14.16) indicating the range included with this
        // response, or a multipart/byteranges Content-Type including Content-Range
        // fields for each part. If a Content-Length header field is present
        // in the response, its value MUST match the actual number of OCTETs
        // transmitted in the message-body.

        if (response.get("Content-Range") != null)
        {
            assertEquals("bytes 1-3/27", response.get("Content-Range"), "10.2.7 Partial Content / Response / Content Range");
        }

        if (response.get("Content-Length") != null)
        {
            assertEquals("3", response.get("Content-Length"), "10.2.7 Patial Content / Response / Content Length");
        }

        // (point 2) A 206 response MUST contain a Date header
        assertTrue(response.get("Date") != null, "10.2.7 Partial Content / Response / Date");

        // (point 3) A 206 response MUST contain ETag and/or Content-Location,
        // if the header would have been sent in a 200 response to the same request
        if (noRangeHasContentLocation)
        {
            assertTrue(response.get("Content-Location") != null, "10.2.7 Partial Content / Content-Location");
        }
        if (noRangeHasETag)
        {
            assertTrue(response.get("ETag") != null, "10.2.7 Partial Content / Content-Location");
        }

        // (point 4) A 206 response MUST contain Expires, Cache-Control, and/or Vary,
        // if the field-value might differ from that sent in any previous response
        // for the same variant

        // TODO: Not sure how to test this condition.

        // Test the body sent
        assertThat("10.2.7 Partial Content", response.getContent(), Matchers.containsString("BCD")); // should only have bytes 1-3
    }

    /**
     * Test Redirection 3xx (Response Code)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test103RedirectHttp10Path() throws Exception
    {
        String specId;

        // HTTP/1.0
        StringBuffer req1 = new StringBuffer();
        req1.append("GET /redirect/ HTTP/1.0\n");
        req1.append("Host: myhost:1234\n");
        req1.append("Connection: Close\n");
        req1.append("\n");

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1.toString()));

        specId = "10.3 Redirection HTTP/1.0 - basic";
        assertThat(specId, response.getStatus(), is(HttpStatus.FOUND_302));
        assertEquals("/tests/", response.get("Location"), specId);
    }

    /**
     * Test Redirection 3xx (Response Code)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test103RedirectHttp11Path() throws Exception
    {
        // HTTP/1.1

        StringBuffer req2 = new StringBuffer();
        req2.append("GET /redirect/ HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("\n");

        req2.append("GET /redirect/ HTTP/1.1\n");
        req2.append("Host: localhost\n");
        req2.append("Connection: close\n");
        req2.append("\n");

        LocalEndPoint endp = connector.executeRequest(req2.toString());
        HttpTester.Response response = HttpTester.parseResponse(endp.getResponse());

        String specId = "10.3 Redirection HTTP/1.1 - basic (response 1)";
        assertThat(specId, response.getStatus(), is(HttpStatus.FOUND_302));
        assertEquals("/tests/", response.get("Location"), specId);

        response = HttpTester.parseResponse(endp.getResponse());
        specId = "10.3 Redirection HTTP/1.1 - basic (response 2)";
        assertThat(specId, response.getStatus(), is(HttpStatus.FOUND_302));
        assertEquals("/tests/", response.get("Location"), specId);
        assertEquals("close", response.get("Connection"), specId);
    }

    /**
     * Test Redirection 3xx (Response Code)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test103RedirectHttp10Resource() throws Exception
    {
        // HTTP/1.0 - redirect with resource/content

        String req3 = """
            GET /redirect/R1.txt HTTP/1.0
            Host: localhost
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req3));

        String specId = "10.3 Redirection HTTP/1.0 w/content";
        assertThat(specId, response.getStatus(), is(HttpStatus.FOUND_302));
        assertEquals("/tests/R1.txt", response.get("Location"), specId);
    }

    /**
     * Test Redirection 3xx (Response Code)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-10.3">RFC 2616 (section 10.3)</a>
     */
    @Test
    public void test103RedirectHttp11Resource() throws Exception
    {
        // HTTP/1.1 - redirect with resource/content

        String req4 = """
            GET /redirect/R2.txt HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req4));

        String specId = "10.3 Redirection HTTP/1.1 w/content";
        assertThat(specId + " [status]", response.getStatus(), is(HttpStatus.FOUND_302));
        assertThat(specId + " [location]", response.get("Location"), is("/tests/R2.txt"));
        assertThat(specId + " [connection]", response.get("Connection"), is("close"));
    }

    @Test
    public void test10418() throws Exception
    {
        // Expect Failure
        int offset = 0;
        String response = connector.getResponse(
            "GET /R1 HTTP/1.1\n" +
                "Host: localhost\n" +
                "Expect: unknown\n" +
                "Content-Type: text/plain\n" +
                "Content-Length: 8\n" +
                "\n");
        offset = checkContains(response, offset, "HTTP/1.1 417", "8.2.3 expect failure") + 1;
    }

    /**
     * Test Content-Range (Header Field)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test1416NoRange() throws Exception
    {
        //
        // calibrate with normal request (no ranges); if this doesnt
        // work, dont expect ranges to work either
        //

        String req1 = """
            GET /static/alpha.txt HTTP/1.1
            Host: localhost
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        assertThat(response.toString(), response.getStatus(), is(HttpStatus.OK_200));
        assertThat(response.getContent(), containsString("ABCDEFGHIJKLMNOPQRSTUVWXYZ\n"));
    }

    private void assertPartialContentRange(String rangedef, String expectedRange, String expectedBody) throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        String req1 = "GET /static/alpha.txt HTTP/1.1\n" +
            "Host: localhost\n" +
            "Range: " + rangedef + "\n" + // Invalid range
            "Connection: close\n" +
            "\n";

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial Range: '" + rangedef + "'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));
        assertThat(specId, response.get(HttpHeader.CONTENT_RANGE), is("bytes " + expectedRange));
        assertThat(specId, response.getContent(), containsString(expectedBody));
    }

    /**
     * Test Content-Range (Header Field) - Tests multiple ranges, where all defined ranges are syntactically valid, however some ranges are outside of the
     * limits of the available data
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test1416PartialRange() throws Exception
    {
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable

        assertPartialContentRange("bytes=5-8,50-60", "5-8/27", alpha.substring(5, 8 + 1));
        assertPartialContentRange("bytes=50-60,5-8", "5-8/27", alpha.substring(5, 8 + 1));
    }

    /**
     * Test Content-Range (Header Field) - Tests single Range request header with 2 ranges defined, where there is a mixed case of validity, 1 range invalid,
     * another 1 valid.
     * Only the valid range should be processed. The invalid range should be ignored.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test1416PartialRangeMixedRanges() throws Exception
    {
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // a) Range: bytes=a-b,5-8

        // Invalid range, then Valid range
        String req1 = """
            GET /static/alpha.txt HTTP/1.1
            Host: localhost
            Range: bytes=a-b,5-8
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial Range (Mixed): 'bytes=a-b,5-8'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));
        assertThat(specId, response.get(HttpHeader.CONTENT_RANGE), is("bytes 5-8/27"));
        assertThat(specId, response.getContent(), containsString(alpha.substring(5, 8 + 1)));
    }

    /**
     * Test Content-Range (Header Field) - Tests single Range request header with 2 ranges defined, where there is a mixed case of validity, 1 range invalid,
     * another 1 valid.
     * Only the valid range should be processed. The invalid range should be ignored.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test1416PartialRangeMixedBytes() throws Exception
    {
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // b) Range: bytes=a-b,bytes=5-8
        // Invalid range, then Valid range
        String req1 = """
            GET /static/alpha.txt HTTP/1.1
            Host: localhost
            Range: bytes=a-b,bytes=5-8
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial Range (Mixed): 'bytes=a-b,bytes=5-8'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));
        assertThat(specId, response.get(HttpHeader.CONTENT_RANGE), is("bytes 5-8/27"));
        assertThat(specId, response.getContent(), containsString(alpha.substring(5, 8 + 1)));
    }

    /**
     * Test Content-Range (Header Field) - Tests multiple Range request headers, where there is a mixed case of validity, 1 range invalid, another 1 valid.
     * Only the valid range should be processed. The invalid range should be ignored.
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.16">RFC 2616 (section 14.16)</a>
     */
    @Test
    public void test1416PartialRangeMixedMultiple() throws Exception
    {
        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable
        //
        // should test for combinations of good and syntactically
        // invalid ranges here, but I am not certain what the right
        // behavior is anymore
        //
        // return data for valid ranges while ignoring unsatisfiable
        // ranges

        // c) Range: bytes=a-b
        // Range: bytes=5-8

        String req1 = """
            GET /static/alpha.txt HTTP/1.1
            Host: localhost
            Range: bytes=a-b
            Range: bytes=5-8
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial Range (Mixed): 'bytes=a-b' 'bytes=5-8'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));
        assertThat(specId, response.get(HttpHeader.CONTENT_RANGE), is("bytes 5-8/27"));
        assertThat(specId, response.getContent(), containsString(alpha.substring(5, 8 + 1)));
    }

    @Test
    public void test1423() throws Exception
    {
        try (StacklessLogging stackless = new StacklessLogging(HttpParser.class))
        {
            int offset = 0;
            String response = connector.getResponse("GET /R1 HTTP/1.0\n" + "Connection: close\n" + "\n");
            offset = checkContains(response, offset, "HTTP/1.1 200", "200") + 1;

            offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\n" + "Connection: close\n" + "\n");
            offset = checkContains(response, offset, "HTTP/1.1 400", "400") + 1;

            offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\n" + "Host: localhost\n" + "Connection: close\n" + "\n");
            offset = checkContains(response, offset, "HTTP/1.1 200", "200") + 1;

            offset = 0;
            response = connector.getResponse("GET /R1 HTTP/1.1\n" + "Host:\n" + "Connection: close\n" + "\n");
            offset = checkContains(response, offset, "HTTP/1.1 400", "400") + 1;
        }
    }

    /**
     * Tests the (byte) "Range" header for partial content.
     */
    private void assertByteRange(String rangedef, String expectedRange, String expectedBody) throws Exception
    {
        String req1 = "GET /static/alpha.txt HTTP/1.1\n" +
            "Host: localhost\n" +
            "Range: " + rangedef + "\n" +
            "Connection: close\n" +
            "\n";

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial (Byte) Range: '" + rangedef + "'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));
        // It might be strange to see a "Content-Range' response header to a 'Range' request,
        // but this is appropriate per the RFC2616 spec.
        assertEquals("bytes " + expectedRange, response.get("Content-Range"), specId);
        assertThat(specId, response.getContent(), containsString(expectedBody));
    }

    /**
     * Test Range (Header Field)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test1435Range() throws Exception
    {
        //
        // test various valid range specs that have not been
        // tested yet
        //

        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // First 3 bytes
        assertByteRange("bytes=0-2", "0-2/27", alpha.substring(0, 2 + 1));

        // From byte offset 23 thru the end of the content
        assertByteRange("bytes=23-", "23-26/27", alpha.substring(23));

        // Request byte offset 23 thru 42 (only 26 bytes in content)
        // The last 3 bytes are returned.
        assertByteRange("bytes=23-42", "23-26/27", alpha.substring(23, 26 + 1));

        // Request the last 3 bytes
        assertByteRange("bytes=-3", "24-26/27", alpha.substring(24, 26 + 1));
    }


    /**
     * Test Range (Header Field)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test1435RangeMultipart1() throws Exception
    {
        String rangedef = "bytes=23-23,-2"; // Request byte at offset 23, and the last 2 bytes

        String req1 = "GET /static/alpha.txt HTTP/1.1\n" +
            "Host: localhost\n" +
            "Range: " + rangedef + "\n" +
            "Connection: close\n" +
            "\n";

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        String specId = "Partial (Byte) Range: '" + rangedef + "'";
        assertThat(specId, response.getStatus(), is(HttpStatus.PARTIAL_CONTENT_206));

        String contentType = response.get("Content-Type");
        // RFC states that multiple parts should result in multipart/byteranges Content type.
        assertThat(specId + " Content-Type", contentType, startsWith("multipart/byteranges"));

        // Collect 'boundary' string
        String boundary = null;
        String[] parts = contentType.split(";");
        for (int i = 0; i < parts.length; i++)
        {
            if (parts[i].trim().startsWith("boundary="))
            {
                String[] boundparts = parts[i] == null ? null : parts[i].split("=");
                assertNotNull(boundparts);
                assertEquals(2, boundparts.length, specId + " Boundary parts.length");
                boundary = boundparts[1];
            }
        }

        assertNotNull(boundary, specId + " Should have found boundary in Content-Type header");

        List<String> lines = Arrays.asList(response.getContent().trim().split("(\\r)?\\n"));
        int i = 0;
        assertEquals("--" + boundary, lines.get(i++));
        assertEquals("Content-Type: text/plain", lines.get(i++));
        assertEquals("Content-Range: bytes 23-23/27", lines.get(i++));
        assertEquals("", lines.get(i++));
        assertEquals("X", lines.get(i++));
        assertEquals("--" + boundary, lines.get(i++));
        assertEquals("Content-Type: text/plain", lines.get(i++));
        assertEquals("Content-Range: bytes 25-26/27", lines.get(i++));
        assertEquals("", lines.get(i++));
        assertEquals("Z", lines.get(i++));
        assertEquals("", lines.get(i++));
        assertEquals("--" + boundary + "--", lines.get(i++));
    }

    /**
     * Test Range (Header Field)
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test1435PartialRange() throws Exception
    {
        //
        // test various valid range specs that have not been
        // tested yet
        //

        String alpha = "ABCDEFGHIJKLMNOPQRSTUVWXYZ\n";

        // server should not return a 416 if at least one syntactically valid ranges
        // are is satisfiable

        assertByteRange("bytes=5-8,50-60", "5-8/27", alpha.substring(5, 8 + 1));
        assertByteRange("bytes=50-60,5-8", "5-8/27", alpha.substring(5, 8 + 1));
    }

    private void assertBadByteRange(String rangedef) throws Exception
    {
        String req1 = "GET /static/alpha.txt HTTP/1.1\n" +
            "Host: localhost\n" +
            "Range: " + rangedef + "\n" +
            "Connection: close\n" +
            "\n";

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req1));

        assertThat("BadByteRange: '" + rangedef + "'", response.getStatus(), is(HttpStatus.RANGE_NOT_SATISFIABLE_416));
    }

    /**
     * Test Range (Header Field) - Bad Range Request
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.35">RFC 2616 (section 14.35)</a>
     */
    @Test
    public void test1435BadRangeInvalidSyntax() throws Exception
    {
        // server should ignore all range headers which include
        // at least one syntactically invalid range

        assertBadByteRange("bytes=a-b"); // Invalid due to non-digit entries
        assertBadByteRange("bytes=-"); // Invalid due to missing range ends
        assertBadByteRange("bytes=-1-"); // Invalid due negative to end range
        assertBadByteRange("doublehalfwords=1-2"); // Invalid due to bad key 'doublehalfwords'
    }

    /**
     * Test TE (Header Field) / Transfer Codings
     *
     * @see <a href="http://tools.ietf.org/html/rfc2616#section-14.39">RFC 2616 (section 14.39)</a>
     * TODO not implemented for HTTP/1
     */
    @Test
    @Disabled
    public void test1439TEDeflate() throws Exception
    {
        String specId;

        // Deflate not accepted
        String req2 = """
            GET /static/solutions.html HTTP/1.1
            Host: localhost
            TE: deflate
            Connection: close
            
            """;

        HttpTester.Response response = HttpTester.parseResponse(connector.getResponse(req2));
        specId = "14.39 TE Header";
        assertThat(specId, response.getStatus(), is(HttpStatus.NOT_IMPLEMENTED_501)); // Error on TE (deflate not supported)
    }

    @Test
    public void test196()
    {
        try
        {
            int offset = 0;
            String response = connector.getResponse("GET /R1 HTTP/1.0\n" + "\n");
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 default close") + 10;
            checkNotContained(response, offset, "Connection: close", "19.6.2 not assumed");

            LocalEndPoint endp = connector.executeRequest(
                "GET /R1 HTTP/1.0\n" + "Host: localhost\n" + "Connection: keep-alive\n" + "\n" +
                    "GET /R2 HTTP/1.0\n" + "Host: localhost\n" + "Connection: close\n" + "\n" +
                    "GET /R3 HTTP/1.0\n" + "Host: localhost\n" + "Connection: close\n" + "\n");

            offset = 0;
            response = endp.getResponse();
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "Connection: keep-alive", "19.6.2 Keep-alive 1") + 1;

            offset = checkContains(response, offset, "<html>", "19.6.2 Keep-alive 1") + 1;

            offset = checkContains(response, offset, "/R1", "19.6.2 Keep-alive 1") + 1;

            offset = 0;
            response = endp.getResponse();
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 Keep-alive 2") + 11;
            offset = checkContains(response, offset, "/R2", "19.6.2 Keep-alive close") + 3;

            offset = 0;
            response = endp.getResponse();
            assertThat("19.6.2 closed", response, nullValue());

            offset = 0;
            endp = connector.executeRequest(
                "GET /R1 HTTP/1.0\n" + "Host: localhost\n" + "Connection: keep-alive\n" + "Content-Length: 10\n" + "\n" + "1234567890\n" +
                    "GET /RA HTTP/1.0\n" + "Host: localhost\n" + "Connection: keep-alive\n" + "Content-Length: 10\n" + "\n" + "ABCDEFGHIJ\n" +
                    "GET /R2 HTTP/1.0\n" + "Host: localhost\n" + "Connection: close\n" + "\n" +
                    "GET /R3 HTTP/1.0\n" + "Host: localhost\n" + "Connection: close\n" + "\n");

            offset = 0;
            response = endp.getResponse();
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "Connection: keep-alive", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "<html>", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "1234567890", "19.6.2 Keep-alive 1") + 1;

            offset = 0;
            response = endp.getResponse();
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "Connection: keep-alive", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "<html>", "19.6.2 Keep-alive 1") + 1;
            offset = checkContains(response, offset, "ABCDEFGHIJ", "19.6.2 Keep-alive 1") + 1;

            offset = 0;
            response = endp.getResponse();
            offset = checkContains(response, offset, "HTTP/1.1 200 OK\r\n", "19.6.2 Keep-alive 2") + 11;
            offset = checkContains(response, offset, "/R2", "19.6.2 Keep-alive close") + 3;
            offset = 0;
            response = endp.getResponse();
            assertThat("19.6.2 closed", response, nullValue());
        }
        catch (Exception e)
        {
            e.printStackTrace();
            fail(e.getMessage());
        }
    }

    private int checkContains(String s, int offset, String c, String test)
    {
        assertThat(test, s.substring(offset), containsString(c));
        return s.indexOf(c, offset);
    }

    private void checkNotContained(String s, int offset, String c, String test)
    {
        assertThat(test, s.substring(offset), not(containsString(c)));
    }

    private void checkNotContained(String s, String c, String test)
    {
        checkNotContained(s, 0, c, test);
    }
}
