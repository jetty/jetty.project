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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UnsupportedEncodingException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.MappingMatch;
import jakarta.servlet.http.Part;
import jakarta.servlet.http.PushBuilder;
import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpTester;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.UriCompliance;
import org.eclipse.jetty.http.pathmap.MatchedPath;
import org.eclipse.jetty.http.pathmap.RegexPathSpec;
import org.eclipse.jetty.http.pathmap.ServletPathSpec;
import org.eclipse.jetty.io.Connection;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.server.LocalConnector.LocalEndPoint;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.Session;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
@ExtendWith(WorkDirExtension.class)
public class RequestTest
{
    private static final Logger LOG = LoggerFactory.getLogger(RequestTest.class);
    public WorkDir workDir;
    private Server _server;
    private LocalConnector _connector;
    private RequestHandler _handler;
    private boolean _normalizeAddress = true;

    @BeforeEach
    public void init() throws Exception
    {
        _server = new Server();
        HttpConnectionFactory http = new HttpConnectionFactory()
        {
            @Override
            public Connection newConnection(Connector connector, EndPoint endPoint)
            {
                HttpConnection conn = new HttpConnection(getHttpConfiguration(), connector, endPoint, isRecordHttpComplianceViolations())
                {
                    @Override
                    protected HttpChannelOverHttp newHttpChannel()
                    {
                        return new HttpChannelOverHttp(this, getConnector(), getHttpConfiguration(), getEndPoint(), this)
                        {
                            @Override
                            protected String formatAddrOrHost(String addr)
                            {
                                if (_normalizeAddress)
                                    return super.formatAddrOrHost(addr);
                                return addr;
                            }
                        };
                    }
                };
                return configure(conn, connector, endPoint);
            }
        };
        http.setInputBufferSize(1024);
        http.getHttpConfiguration().setRequestHeaderSize(512);
        http.getHttpConfiguration().setResponseHeaderSize(512);
        http.getHttpConfiguration().setOutputBufferSize(2048);
        http.getHttpConfiguration().addCustomizer(new ForwardedRequestCustomizer());
        _connector = new LocalConnector(_server, http);
        _server.addConnector(_connector);
        _connector.setIdleTimeout(500);
        _handler = new RequestHandler();
        _server.setHandler(_handler);

        ErrorHandler errors = new ErrorHandler();
        errors.setServer(_server);
        errors.setShowStacks(true);
        _server.addBean(errors);
        _server.start();
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testRequestCharacterEncoding() throws Exception
    {
        AtomicReference<String> result = new AtomicReference<>(null);
        AtomicReference<String> overrideCharEncoding = new AtomicReference<>(null);

        _server.stop();
        ContextHandler handler = new CharEncodingContextHandler();
        _server.setHandler(handler);
        handler.setHandler(_handler);
        _handler._checker = (request, response) ->
        {
            try
            {
                String s = overrideCharEncoding.get();
                if (s != null)
                    request.setCharacterEncoding(s);

                result.set(request.getCharacterEncoding());
                return true;
            }
            catch (UnsupportedEncodingException e)
            {
                return false;
            }
        };
        _server.start();

        String request = "GET / HTTP/1.1\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        //test setting the default char encoding
        handler.setDefaultRequestCharacterEncoding("ascii");
        String response = _connector.getResponse(request);
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertEquals("ascii", result.get());

        //test overriding the default char encoding with explicit encoding
        result.set(null);
        overrideCharEncoding.set("utf-16");
        response = _connector.getResponse(request);
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertEquals("utf-16", result.get());

        //test fallback to content-type encoding
        result.set(null);
        overrideCharEncoding.set(null);
        handler.setDefaultRequestCharacterEncoding(null);
        response = _connector.getResponse(request);
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertEquals("utf-8", result.get());
    }

    @Test
    public void testParamExtraction() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            try
            {
                // do the parse
                request.getParameterMap();
                return false;
            }
            catch (BadMessageException e)
            {
                // Should be able to retrieve the raw query
                String rawQuery = request.getQueryString();
                return rawQuery.equals("param=aaa%ZZbbb&other=value");
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?param=aaa%ZZbbb&other=value HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testParameterExtractionKeepOrderingIntact() throws Exception
    {
        AtomicReference<Map<String, String[]>> reference = new AtomicReference<>();
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                reference.set(request.getParameterMap());
                return true;
            }
        };

        String request = "POST /?first=1&second=2&third=3&fourth=4 HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded\n" +
            "Connection: close\n" +
            "Content-Length: 34\n" +
            "\n" +
            "fifth=5&sixth=6&seventh=7&eighth=8";

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
        assertThat(new ArrayList<>(reference.get().keySet()), is(Arrays.asList("first", "second", "third", "fourth", "fifth", "sixth", "seventh", "eighth")));
    }

    @Test
    public void testParameterExtractionOrderingWithMerge() throws Exception
    {
        AtomicReference<Map<String, String[]>> reference = new AtomicReference<>();
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                reference.set(request.getParameterMap());
                return true;
            }
        };

        String request = "POST /?a=1&b=2&c=3&a=4 HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded\n" +
            "Connection: close\n" +
            "Content-Length: 11\n" +
            "\n" +
            "c=5&b=6&a=7";

        String responses = _connector.getResponse(request);
        Map<String, String[]> returnedMap = reference.get();
        assertTrue(responses.startsWith("HTTP/1.1 200"));
        assertThat(new ArrayList<>(returnedMap.keySet()), is(Arrays.asList("a", "b", "c")));
        assertTrue(Arrays.equals(returnedMap.get("a"), new String[]{"1", "4", "7"}));
        assertTrue(Arrays.equals(returnedMap.get("b"), new String[]{"2", "6"}));
        assertTrue(Arrays.equals(returnedMap.get("c"), new String[]{"3", "5"}));
    }

    @Test
    public void testParamExtractionBadSequence() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            request.getParameterMap();
            // should have thrown a BadMessageException
            return false;
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?test_%e0%x8%81=missing HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertThat("Responses", responses, startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testParamExtractionTimeout() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            request.getParameterMap();
            // should have thrown a BadMessageException
            return false;
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\n" +
            "Connection: close\n" +
            "Content-Length: 100\n" +
            "\n" +
            "name=value";

        LocalEndPoint endp = _connector.connect();
        endp.addInput(request);

        String response = BufferUtil.toString(endp.waitForResponse(false, 1, TimeUnit.SECONDS));
        assertThat("Responses", response, startsWith("HTTP/1.1 500"));
    }

    @Test
    public void testEmptyHeaders() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            assertNotNull(request.getLocale());
            assertTrue(request.getLocales().hasMoreElements()); // Default locale
            assertEquals("", request.getContentType());
            assertNull(request.getCharacterEncoding());
            assertEquals(0, request.getQueryString().length());
            assertEquals(-1, request.getContentLength());
            assertNull(request.getCookies());
            assertEquals("", request.getHeader("Name"));
            assertTrue(request.getHeaders("Name").hasMoreElements()); // empty
            assertThrows(IllegalArgumentException.class, () -> request.getDateHeader("Name"));
            assertEquals(-1, request.getDateHeader("Other"));
            return true;
        };

        String request = "GET /? HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\n" +
            "Content-Type: \n" +
            "Accept-Language: \n" +
            "Cookie: \n" +
            "Name: \n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testMultiPartNoConfig() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            try
            {
                request.getPart("stuff");
                return false;
            }
            catch (IllegalStateException e)
            {
                //expected exception because no multipart config is set up
                assertTrue(e.getMessage().startsWith("No multipart config"));
                return true;
            }
            catch (Exception e)
            {
                return false;
            }
        };

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        assertTrue(responses.startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testLocale() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            assertThat(request.getLocale().getLanguage(), is("da"));
            Enumeration<Locale> locales = request.getLocales();
            Locale locale = locales.nextElement();
            assertThat(locale.getLanguage(), is("da"));
            assertThat(locale.getCountry(), is(""));
            locale = locales.nextElement();
            assertThat(locale.getLanguage(), is("en"));
            assertThat(locale.getCountry(), is("AU"));
            locale = locales.nextElement();
            assertThat(locale.getLanguage(), is("en"));
            assertThat(locale.getCountry(), is("GB"));
            locale = locales.nextElement();
            assertThat(locale.getLanguage(), is("en"));
            assertThat(locale.getCountry(), is(""));
            assertFalse(locales.hasMoreElements());
            return true;
        };

        String request = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\r\n" +
            "Accept-Language: da, en-gb;q=0.8, en;q=0.7\r\n" +
            "Accept-Language: XX;q=0, en-au;q=0.9\r\n" +
            "\r\n";
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testMultiPart() throws Exception
    {
        Path testTmpDir = workDir.getEmptyPathDir();

        // We should have two tmp files after parsing the multipart form.
        RequestTester tester = (request, response) ->
        {
            try (Stream<Path> s = Files.list(testTmpDir))
            {
                return s.count() == 2;
            }
        };

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir.toFile(), tester));
        _server.stop();
        _server.setHandler(contextHandler);
        _server.start();

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"field1\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "Content-Transfer-Encoding: something\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "\r\n" +
            multipart;

        LocalEndPoint endPoint = _connector.connect();
        endPoint.addInput(request);
        String response = endPoint.getResponse();
        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertThat(response, containsString("Violation: TRANSFER_ENCODING"));

        // We know the previous request has completed if another request can be processed on the same connection.
        String cleanupRequest = "GET /foo/cleanup HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Connection: close\r\n" +
            "\r\n";

        endPoint.addInput(cleanupRequest);
        assertTrue(endPoint.getResponse().startsWith("HTTP/1.1 200"));
        assertThat("File Count in dir: " + testTmpDir, getFileCount(testTmpDir), is(0L));
    }

    @Test
    public void testLegacyMultiPart() throws Exception
    {
        Path testTmpDir = workDir.getEmptyPathDir();

        // We should have two tmp files after parsing the multipart form.
        RequestTester tester = (request, response) ->
        {
            try (Stream<Path> s = Files.list(testTmpDir))
            {
                return s.count() == 2;
            }
        };

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new MultiPartRequestHandler(testTmpDir.toFile(), tester));
        _server.stop();
        _server.setHandler(contextHandler);
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setMultiPartFormDataCompliance(MultiPartFormDataCompliance.LEGACY);
        _server.start();

        String multipart = "      --AaB03x\r" +
            "content-disposition: form-data; name=\"field1\"\r" +
            "\r" +
            "Joe Blow\r" +
            "--AaB03x\r" +
            "content-disposition: form-data; name=\"stuff\"; filename=\"foo.upload\"\r" +
            "Content-Type: text/plain;charset=ISO-8859-1\r" +
            "\r" +
            "000000000000000000000000000000000000000000000000000\r" +
            "--AaB03x--\r";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        String responses = _connector.getResponse(request);
        assertThat(responses, Matchers.startsWith("HTTP/1.1 200"));
        assertThat(responses, Matchers.containsString("Violation: CR_LINE_TERMINATION"));
        assertThat(responses, Matchers.containsString("Violation: NO_CRLF_AFTER_PREAMBLE"));
    }

    @Test
    public void testBadMultiPart() throws Exception
    {
        //a bad multipart where one of the fields has no name
        Path testTmpDir = workDir.getEmptyPathDir();

        ContextHandler contextHandler = new ContextHandler();
        contextHandler.setContextPath("/foo");
        contextHandler.setResourceBase(".");
        contextHandler.setHandler(new BadMultiPartRequestHandler(testTmpDir));
        _server.stop();
        _server.setHandler(contextHandler);
        _server.start();

        String multipart = "--AaB03x\r\n" +
            "content-disposition: form-data; name=\"xxx\"\r\n" +
            "\r\n" +
            "Joe Blow\r\n" +
            "--AaB03x\r\n" +
            "content-disposition: form-data;  filename=\"foo.upload\"\r\n" +
            "Content-Type: text/plain;charset=ISO-8859-1\r\n" +
            "\r\n" +
            "000000000000000000000000000000000000000000000000000\r\n" +
            "--AaB03x--\r\n";

        String request = "GET /foo/x.html HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: multipart/form-data; boundary=\"AaB03x\"\r\n" +
            "Content-Length: " + multipart.getBytes().length + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            multipart;

        LocalEndPoint endPoint = _connector.connect();
        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            endPoint.addInput(request);
            assertTrue(endPoint.getResponse().startsWith("HTTP/1.1 500"));
        }

        // Wait for the cleanup of the multipart files.
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
        {
            while (getFileCount(testTmpDir) > 0)
            {
                Thread.yield();
            }
        });
    }

    @Test
    public void testBadUtf8ParamExtraction() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            try
            {
                // This throws an exception if attempted
                request.getParameter("param");
                return false;
            }
            catch (BadMessageException e)
            {
                // Should still be able to get the raw query.
                String rawQuery = request.getQueryString();
                return rawQuery.equals("param=aaa%E7bbb");
            }
        };

        //Send a request with query string with illegal hex code to cause
        //an exception parsing the params
        String request = "GET /?param=aaa%E7bbb HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        LOG.info("Expecting NotUtf8Exception in state 36...");
        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testEncodedParamExtraction() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            try
            {
                // This throws an exception if attempted
                request.getParameter("param");
                return false;
            }
            catch (BadMessageException e)
            {
                return e.getCode() == 415;
            }
        };

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\n" +
            "Content-Length: 10\n" +
            "Content-Encoding: gzip\n" +
            "Connection: close\n" +
            "\n" +
            "0123456789\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testContentLengthExceedsMaxInteger() throws Exception
    {
        final long HUGE_LENGTH = (long)Integer.MAX_VALUE * 10L;

        _handler._checker = (request, response) ->
            request.getContentLength() == (-1) && // per HttpServletRequest javadoc this must return (-1);
                request.getContentLengthLong() == HUGE_LENGTH;

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/octet-stream\n" +
            "Content-Length: " + HUGE_LENGTH + "\n" +
            "Connection: close\n" +
            "\n" +
            "<insert huge amount of content here>\n";

        System.out.println(request);

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    /**
     * The Servlet spec and API cannot parse Content-Length that exceeds Long.MAX_VALUE
     */
    @Test
    public void testContentLengthExceedsMaxLong() throws Exception
    {
        String hugeLength = Long.MAX_VALUE + "0";

        _handler._checker = (request, response) ->
            request.getHeader("Content-Length").equals(hugeLength) &&
                request.getContentLength() == (-1) && // per HttpServletRequest javadoc this must return (-1);
                request.getContentLengthLong() == (-1); // exact behavior here not specified in Servlet javadoc

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/octet-stream\n" +
            "Content-Length: " + hugeLength + "\n" +
            "Connection: close\n" +
            "\n" +
            "<insert huge amount of content here>\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testIdentityParamExtraction() throws Exception
    {
        _handler._checker = (request, response) -> "bar".equals(request.getParameter("foo"));

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded; charset=utf-8\n" +
            "Content-Length: 7\n" +
            "Content-Encoding: identity\n" +
            "Connection: close\n" +
            "\n" +
            "foo=bar\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testEncodedNotParams() throws Exception
    {
        _handler._checker = (request, response) -> request.getParameter("param") == null;

        //Send a request with encoded form content
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/octet-stream\n" +
            "Content-Length: 10\n" +
            "Content-Encoding: gzip\n" +
            "Connection: close\n" +
            "\n" +
            "0123456789\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testInvalidHostHeader() throws Exception
    {
        // Use a contextHandler with vhosts to force call to Request.getServerName()
        ContextHandler context = new ContextHandler();
        context.addVirtualHosts(new String[]{"something"});
        _server.stop();
        _server.setHandler(context);
        _server.start();

        // Request with illegal Host header
        String request = "GET / HTTP/1.1\n" +
            "Host: whatever.com:xxxx\n" +
            "Content-Type: text/html;charset=utf8\n" +
            "Connection: close\n" +
            "\n";

        String responses = _connector.getResponse(request);
        assertThat(responses, Matchers.startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testContentTypeEncoding() throws Exception
    {
        final ArrayList<String> results = new ArrayList<>();
        _handler._checker = (request, response) ->
        {
            results.add(request.getContentType());
            results.add(request.getCharacterEncoding());
            return true;
        };

        LocalEndPoint endp = _connector.executeRequest(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/test\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html;charset=utf8\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html; charset=\"utf8\"\n" +
                "\n" +

                "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Content-Type: text/html; other=foo ; blah=\"charset=wrong;\" ; charset =   \" x=z; \"   ; more=values \n" +
                "Connection: close\n" +
                "\n"
        );

        endp.getResponse();
        endp.getResponse();
        endp.getResponse();
        endp.getResponse();

        int i = 0;
        assertEquals("text/test", results.get(i++));
        assertNull(results.get(i++));

        assertEquals("text/html;charset=utf8", results.get(i++));
        assertEquals("utf-8", results.get(i++));

        assertEquals("text/html; charset=\"utf8\"", results.get(i++));
        assertEquals("utf-8", results.get(i++));

        assertTrue(results.get(i++).startsWith("text/html"));
        assertEquals(" x=z; ", results.get(i));
    }

    @Test
    public void testHostPort() throws Exception
    {
        final ArrayList<String> results = new ArrayList<>();
        _handler._checker = (request, response) ->
        {
            results.add(request.getRequestURL().toString());
            results.add(request.getRemoteAddr());
            results.add(request.getServerName());
            results.add(String.valueOf(request.getServerPort()));
            return true;
        };

        String response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: myhost\n" +
                "Connection: close\n" +
                "\n");
        int i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("80", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: myhost:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET http://myhost:8888/ HTTP/1.0\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET http://myhost:8888/ HTTP/1.1\n" +
                "Host: wrong:666\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://myhost:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("myhost", results.get(i++));
        assertEquals("8888", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: 1.2.3.4\n" +
                "Connection: close\n" +
                "\n");
        i = 0;

        assertThat(response, containsString("200 OK"));
        assertEquals("http://1.2.3.4/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("1.2.3.4", results.get(i++));
        assertEquals("80", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: 1.2.3.4:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://1.2.3.4:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("1.2.3.4", results.get(i++));
        assertEquals("8888", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://[::1]/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("80", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("http://[::1]:8888/", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]\n" +
                "x-forwarded-for: remote\n" +
                "x-forwarded-proto: https\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("https://[::1]/", results.get(i++));
        assertEquals("remote", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("443", results.get(i));

        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "x-forwarded-for: remote\n" +
                "x-forwarded-proto: https\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("https://[::1]:8888/", results.get(i++));
        assertEquals("remote", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i));
    }

    @Test
    public void testIPv6() throws Exception
    {
        final ArrayList<String> results = new ArrayList<>();
        final InetAddress local = Inet6Address.getByAddress("localIPv6", new byte[]{
            0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0, 8
        });
        final InetSocketAddress localAddr = new InetSocketAddress(local, 32768);
        _handler._checker = new RequestTester()
        {
            @Override
            public boolean check(HttpServletRequest request, HttpServletResponse response)
            {
                ((Request)request).setRemoteAddr(localAddr);
                results.add(request.getRemoteAddr());
                results.add(request.getRemoteHost());
                results.add(Integer.toString(request.getRemotePort()));
                results.add(request.getServerName());
                results.add(Integer.toString(request.getServerPort()));
                results.add(request.getLocalAddr());
                results.add(Integer.toString(request.getLocalPort()));
                return true;
            }
        };

        _normalizeAddress = true;
        String response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "\n");
        int i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("[1:2:3:4:5:6:7:8]", results.get(i++));
        assertEquals("localIPv6", results.get(i++));
        assertEquals("32768", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("0", results.get(i));

        _normalizeAddress = false;
        results.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: [::1]:8888\n" +
                "Connection: close\n" +
                "\n");
        i = 0;
        assertThat(response, containsString("200 OK"));
        assertEquals("1:2:3:4:5:6:7:8", results.get(i++));
        assertEquals("localIPv6", results.get(i++));
        assertEquals("32768", results.get(i++));
        assertEquals("[::1]", results.get(i++));
        assertEquals("8888", results.get(i++));
        assertEquals("0.0.0.0", results.get(i++));
        assertEquals("0", results.get(i));
    }

    @Test
    public void testContent() throws Exception
    {
        final AtomicInteger length = new AtomicInteger();

        _handler._checker = (request, response) ->
        {
            int len = request.getContentLength();
            ServletInputStream in = request.getInputStream();
            for (int i = 0; i < len; i++)
            {
                int b = in.read();
                if (b < 0)
                    return false;
            }
            if (in.read() > 0)
                return false;

            length.set(len);
            return true;
        };

        StringBuilder content = new StringBuilder();

        for (int l = 0; l < 1024; l++)
        {
            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: multipart/form-data-test\r\n" +
                "Content-Length: " + l + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                content;
            LOG.debug("test l={}", l);
            String response = _connector.getResponse(request);
            LOG.debug(response);
            assertThat(response, containsString(" 200 OK"));
            assertEquals(l, length.get());
            content.append("x");
        }
    }

    @Test
    public void testEncodedForm() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            String actual = request.getParameter("name2");
            return "test2".equals(actual);
        };

        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testEncodedFormUnknownMethod() throws Exception
    {
        _handler._checker = (request, response) -> request.getParameter("name1") == null && request.getParameter("name2") == null && request.getParameter("name3") == null;

        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "UNKNOWN / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testEncodedFormExtraMethod() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            String actual = request.getParameter("name2");
            return "test2".equals(actual);
        };

        _connector.getConnectionFactory(HttpConnectionFactory.class).getHttpConfiguration().addFormEncodedMethod("Extra");
        String content = "name1=test&name2=test2&name3=&name4=test";
        String request = "EXTRA / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void test8859EncodedForm() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            // Should be "testä"
            // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS
            request.setCharacterEncoding(StandardCharsets.ISO_8859_1.name());
            String actual = request.getParameter("name2");
            return "test\u00e4".equals(actual);
        };

        String content = "name1=test&name2=test%E4&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testUTF8EncodedForm() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            // http://www.ltg.ed.ac.uk/~richard/utf-8.cgi?input=00e4&mode=hex
            // Should be "testä"
            // "test" followed by a LATIN SMALL LETTER A WITH DIAERESIS
            String actual = request.getParameter("name2");
            return "test\u00e4".equals(actual);
        };

        String content = "name1=test&name2=test%C3%A4&name3=&name4=test";
        String request = "POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
            "Content-Length: " + content.length() + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            content;
        String response = _connector.getResponse(request);
        assertThat(response, containsString(" 200 OK"));
    }

    @Test
    public void testPartialRead() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                Reader reader = request.getReader();
                byte[] b = ("read=" + reader.read() + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String requests = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "\r\n" +
            "0123456789\r\n" +
            "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "ABCDEFGHIJ\r\n";

        LocalEndPoint endp = _connector.executeRequest(requests);
        String responses = endp.getResponse() + endp.getResponse();

        int index = responses.indexOf("read=" + (int)'0');
        assertTrue(index > 0);

        index = responses.indexOf("read=" + (int)'A', index + 7);
        assertTrue(index > 0);
    }

    @Test
    public void testQueryAfterRead()
        throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                Reader reader = request.getReader();
                String in = IO.toString(reader);
                String param = request.getParameter("param");

                byte[] b = ("read='" + in + "' param=" + param + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String request = "POST /?param=right HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: application/x-www-form-urlencoded\r\n" +
            "Content-Length: " + 11 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "param=wrong\r\n";

        String responses = _connector.getResponse(request);

        assertTrue(responses.indexOf("read='param=wrong' param=right") > 0);
    }

    @Test
    public void testSessionAfterRedirect() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                response.sendRedirect("/foo");
                try
                {
                    request.getSession(true);
                    fail("Session should not be created after response committed");
                }
                catch (IllegalStateException e)
                {
                    //expected
                }
                catch (Exception e)
                {
                    fail("Session creation after response commit should throw IllegalStateException");
                }
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();
        String response = _connector.getResponse("GET / HTTP/1.1\n" +
            "Host: myhost\n" +
            "Connection: close\n" +
            "\n");
        assertThat(response, containsString(" 302 Found"));
        assertThat(response, containsString("Location: http://myhost/foo"));
    }

    @Test
    public void testPartialInput() throws Exception
    {
        Handler handler = new AbstractHandler()
        {
            @Override
            public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
            {
                baseRequest.setHandled(true);
                InputStream in = request.getInputStream();
                byte[] b = ("read=" + in.read() + "\n").getBytes(StandardCharsets.UTF_8);
                response.setContentLength(b.length);
                response.getOutputStream().write(b);
                response.flushBuffer();
            }
        };
        _server.stop();
        _server.setHandler(handler);
        _server.start();

        String requests = "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "\r\n" +
            "0123456789\r\n" +
            "GET / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Content-Type: text/plane\r\n" +
            "Content-Length: " + 10 + "\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            "ABCDEFGHIJ\r\n";

        LocalEndPoint endp = _connector.executeRequest(requests);
        String responses = endp.getResponse() + endp.getResponse();

        int index = responses.indexOf("read=" + (int)'0');
        assertTrue(index > 0);

        index = responses.indexOf("read=" + (int)'A', index + 7);
        assertTrue(index > 0);
    }

    @Test
    public void testConnectionClose() throws Exception
    {
        String response;

        _handler._checker = (request, response1) ->
        {
            response1.getOutputStream().println("Hello World");
            return true;
        };

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: Other, close\n" +
                "\n"
        );

        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, not(containsString("Connection: close")));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "Connection: Other, close\n" +
                "\n"
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Hello World"));

        response = _connector.getResponse(
            "GET / HTTP/1.0\n" +
                "Host: whatever\n" +
                "Connection: Other,,keep-alive\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: keep-alive"));
        assertThat(response, containsString("Hello World"));

        _handler._checker = (request, response12) ->
        {
            response12.setHeader("Connection", "TE");
            response12.addHeader("Connection", "Other");
            response12.getOutputStream().println("Hello World");
            return true;
        };

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "\n",
            200, TimeUnit.MILLISECONDS
        );
        assertThat(response, containsString("200"));
        assertThat(response, containsString("Connection: TE"));
        assertThat(response, containsString("Connection: Other"));

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertThat(response, containsString("200 OK"));
        assertThat(response, containsString("Connection: close"));
        assertThat(response, containsString("Hello World"));
    }

    @Test
    public void testCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<>();

        _handler._checker = (request, response) ->
        {
            Cookie[] ca = request.getCookies();
            if (ca != null)
                cookies.addAll(Arrays.asList(ca));
            response.getOutputStream().println("Hello World");
            return true;
        };

        String response;

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(0, cookies.size());

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: name=quoted=\"\\\"badly\\\"\"\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("quoted=\"\\\"badly\\\"\"", cookies.get(0).getValue());

        cookies.clear();
        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(2, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        cookies.clear();
        LocalEndPoint endp = _connector.executeRequest(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n" +
                "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "Connection: close\n" +
                "\n"
        );
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));

        assertEquals(4, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertSame(cookies.get(0), cookies.get(2));
        assertSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        endp = _connector.executeRequest(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"quoted=;value\"\n" +
                "\n" +
                "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: name=value; other=\"othervalue\"\n" +
                "Connection: close\n" +
                "\n"
        );
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        response = endp.getResponse();
        assertThat(response, Matchers.startsWith("HTTP/1.1 200 OK"));
        assertEquals(4, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
        assertEquals("other", cookies.get(1).getName());
        assertEquals("quoted=;value", cookies.get(1).getValue());

        assertNotSame(cookies.get(0), cookies.get(2));
        assertNotSame(cookies.get(1), cookies.get(3));

        cookies.clear();
        response = _connector.getResponse(
            "GET /other HTTP/1.1\n" +
                "Host: whatever\n" +
                "Other: header\n" +
                "Cookie: __utmz=14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("__utmz", cookies.get(0).getName());
        assertEquals("14316.133020.1.1.utr=gna.de|ucn=(real)|utd=reral|utct=/games/hen-one,gnt-50-ba-keys:key,2072262.html", cookies.get(0).getValue());
    }

    @Test
    public void testBadCookies() throws Exception
    {
        final ArrayList<Cookie> cookies = new ArrayList<>();

        _handler._checker = (request, response) ->
        {
            Cookie[] ca = request.getCookies();
            if (ca != null)
                cookies.addAll(Arrays.asList(ca));
            response.getOutputStream().println("Hello World");
            return true;
        };

        String response;

        response = _connector.getResponse(
            "GET / HTTP/1.1\n" +
                "Host: whatever\n" +
                "Cookie: Path=value\n" +
                "Cookie: name=value\n" +
                "Connection: close\n" +
                "\n"
        );
        assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        assertEquals(1, cookies.size());
        assertEquals("name", cookies.get(0).getName());
        assertEquals("value", cookies.get(0).getValue());
    }

    @Test
    public void testCookieLeak() throws Exception
    {
        CookieRequestTester tester = new CookieRequestTester();
        _handler._checker = tester;

        String[] cookies = new String[10];
        tester.setCookieArray(cookies);
        LocalEndPoint endp = _connector.connect();
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: other=cookie\r\n" +
            "\r\n");
        endp.getResponse();
        assertEquals("cookie", cookies[0]);
        assertNull(cookies[1]);

        cookies = new String[10];
        tester.setCookieArray(cookies);
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        endp.getResponse();
        assertEquals("value", cookies[0]);
        assertNull(cookies[1]);

        endp = _connector.connect();
        cookies = new String[10];
        tester.setCookieArray(cookies);
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "\r\n");
        endp.getResponse();
        assertEquals("value", cookies[0]);
        assertNull(cookies[1]);

        cookies = new String[10];
        tester.setCookieArray(cookies);
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: \r\n" +
            "Connection: close\r\n" +
            "\r\n");
        endp.getResponse();
        assertNull(cookies[0]);
        assertNull(cookies[1]);

        endp = _connector.connect();
        cookies = new String[10];
        tester.setCookieArray(cookies);
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Cookie: other=cookie\r\n" +
            "\r\n");
        endp.getResponse();
        assertEquals("value", cookies[0]);
        assertEquals("cookie", cookies[1]);
        assertNull(cookies[2]);

        cookies = new String[10];
        tester.setCookieArray(cookies);
        endp.addInput("POST / HTTP/1.1\r\n" +
            "Host: whatever\r\n" +
            "Cookie: name=value\r\n" +
            "Cookie:\r\n" +
            "Connection: close\r\n" +
            "\r\n");
        endp.getResponse();
        assertEquals("value", cookies[0]);
        assertNull(cookies[1]);
    }

    @Test
    public void testHashDOSKeys() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            // Expecting maxFormKeys limit and Closing HttpParser exceptions...
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", -1);
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys", 1000);

            StringBuilder buf = new StringBuilder(4000000);
            buf.append("a=b");

            // The evil keys file is not distributed - as it is dangerous
            File evilKeys = new File("/tmp/keys_mapping_to_zero_2m");
            if (evilKeys.exists())
            {
                // Using real evil keys!
                try (BufferedReader in = new BufferedReader(new FileReader(evilKeys)))
                {
                    String key;
                    while ((key = in.readLine()) != null)
                    {
                        buf.append("&").append(key).append("=").append("x");
                    }
                }
            }
            else
            {
                // we will just create a lot of keys and make sure the limit is applied
                for (int i = 0; i < 2000; i++)
                {
                    buf.append("&").append("K").append(i).append("=").append("x");
                }
            }
            buf.append("&c=d");

            _handler._checker = (request, response) -> "b".equals(request.getParameter("a")) && request.getParameter("c") == null;

            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
                "Content-Length: " + buf.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                buf;

            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            String rawResponse = _connector.getResponse(request);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), is(400));
            assertThat("Response body content", response.getContent(), containsString(BadMessageException.class.getName()));
            assertThat("Response body content", response.getContent(), containsString(IllegalStateException.class.getName()));
            long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertTrue((now - start) < 5000);
        }
    }

    @Test
    public void testHashDOSSize() throws Exception
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpChannel.class))
        {
            LOG.info("Expecting maxFormSize limit and too much data exceptions...");
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormContentSize", 3396);
            _server.setAttribute("org.eclipse.jetty.server.Request.maxFormKeys", 1000);

            StringBuilder buf = new StringBuilder(4000000);
            buf.append("a=b");
            // we will just create a lot of keys and make sure the limit is applied
            for (int i = 0; i < 500; i++)
            {
                buf.append("&").append("K").append(i).append("=").append("x");
            }
            buf.append("&c=d");

            _handler._checker = (request, response) -> "b".equals(request.getParameter("a")) && request.getParameter("c") == null;

            String request = "POST / HTTP/1.1\r\n" +
                "Host: whatever\r\n" +
                "Content-Type: " + MimeTypes.Type.FORM_ENCODED.asString() + "\r\n" +
                "Content-Length: " + buf.length() + "\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                buf;

            long start = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            String rawResponse = _connector.getResponse(request);
            HttpTester.Response response = HttpTester.parseResponse(rawResponse);
            assertThat("Response.status", response.getStatus(), is(400));
            assertThat("Response body content", response.getContent(), containsString(BadMessageException.class.getName()));
            assertThat("Response body content", response.getContent(), containsString(IllegalStateException.class.getName()));
            long now = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
            assertTrue((now - start) < 5000);
        }
    }

    @Test
    public void testNotSupportedCharacterEncoding()
    {
        Request request = new Request(null, null);
        assertThrows(UnsupportedEncodingException.class, () -> request.setCharacterEncoding("doesNotExist"));
    }

    @Test
    public void testGetterSafeFromNullPointerException()
    {
        Request request = new Request(null, null);

        assertNull(request.getAuthType());
        assertNull(request.getAuthentication());

        assertNull(request.getContentType());

        assertNull(request.getCookies());
        assertNull(request.getContext());
        assertNull(request.getContextPath());

        assertNull(request.getHttpFields());
        assertNull(request.getHttpURI());

        assertNotNull(request.getScheme());
        assertNotNull(request.getServerName());
        request.getServerPort();

        assertNotNull(request.getAttributeNames());
        assertFalse(request.getAttributeNames().hasMoreElements());

        request.getParameterMap();
        assertNull(request.getQueryString());
        assertNotNull(request.getQueryParameters());
        assertEquals(0, request.getQueryParameters().size());
        assertNotNull(request.getParameterMap());
        assertEquals(0, request.getParameterMap().size());
    }

    @Test
    public void testEncoding() throws Exception
    {
        _handler._checker = (request, response) -> "/foo/bar".equals(request.getPathInfo());
        String request = "GET /f%6f%6F/b%u0061r HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousParameters() throws Exception
    {
        _handler._checker = (request, response) -> true;
        String request = "GET /ambiguous/..;/path HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousSegments() throws Exception
    {
        _handler._checker = (request, response) -> true;
        String request = "GET /ambiguous/%2e%2e/path HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousSeparators() throws Exception
    {
        _handler._checker = (request, response) -> true;
        String request = "GET /ambiguous/%2f/path HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(new UriCompliance("Test", EnumSet.noneOf(UriCompliance.Violation.class)));
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testAmbiguousPaths() throws Exception
    {
        _handler._checker = (request, response) ->
        {
            response.getOutputStream().println("servletPath=" + request.getServletPath());
            response.getOutputStream().println("pathInfo=" + request.getPathInfo());
            return true;
        };
        String request = "GET /unnormal/.././path/ambiguous%2f%2e%2e/%2e;/info HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";

        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.from(EnumSet.of(
            UriCompliance.Violation.AMBIGUOUS_PATH_SEPARATOR,
            UriCompliance.Violation.AMBIGUOUS_PATH_SEGMENT,
            UriCompliance.Violation.AMBIGUOUS_PATH_PARAMETER)));
        assertThat(_connector.getResponse(request), Matchers.allOf(
            startsWith("HTTP/1.1 200"),
            containsString("pathInfo=/path/info")));
    }

    @Test
    public void testAmbiguousEncoding() throws Exception
    {
        _handler._checker = (request, response) -> true;
        String request = "GET /ambiguous/encoded/%25/path HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));

        UriCompliance custom = new UriCompliance("Custom", EnumSet.complementOf(
            EnumSet.of(UriCompliance.Violation.AMBIGUOUS_PATH_ENCODING)));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(custom);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
    }

    @Test
    public void testAmbiguousDoubleSlash() throws Exception
    {
        _handler._checker = (request, response) -> true;
        String request = "GET /ambiguous/doubleSlash// HTTP/1.0\r\n" +
            "Host: whatever\r\n" +
            "\r\n";
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.DEFAULT);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986_UNAMBIGUOUS);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 400"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.LEGACY);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.RFC3986);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
        _connector.getBean(HttpConnectionFactory.class).getHttpConfiguration().setUriCompliance(UriCompliance.UNSAFE);
        assertThat(_connector.getResponse(request), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void testPushBuilder()
    {
        String uri = "/foo/something";
        Request request = new TestRequest(null, null);
        request.getResponse().getHttpFields().add(new HttpCookie.SetCookieHttpField(new HttpCookie("good", "thumbsup", 100), CookieCompliance.RFC6265));
        request.getResponse().getHttpFields().add(new HttpCookie.SetCookieHttpField(new HttpCookie("bonza", "bewdy", 1), CookieCompliance.RFC6265));
        request.getResponse().getHttpFields().add(new HttpCookie.SetCookieHttpField(new HttpCookie("bad", "thumbsdown", 0), CookieCompliance.RFC6265));
        HttpFields.Mutable fields = HttpFields.build();
        fields.add(HttpHeader.AUTHORIZATION, "Basic foo");
        request.setMetaData(new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_1_0, fields));
        assertTrue(request.isPushSupported());
        PushBuilder builder = request.newPushBuilder();
        assertNotNull(builder);
        assertEquals("GET", builder.getMethod());
        assertThrows(NullPointerException.class, () -> builder.method(null));
        assertThrows(IllegalArgumentException.class, () -> builder.method(""));
        assertThrows(IllegalArgumentException.class, () -> builder.method("   "));
        assertThrows(IllegalArgumentException.class, () -> builder.method("POST"));
        assertThrows(IllegalArgumentException.class, () -> builder.method("PUT"));
        assertThrows(IllegalArgumentException.class, () -> builder.method("DELETE"));
        assertThrows(IllegalArgumentException.class, () -> builder.method("CONNECT"));
        assertThrows(IllegalArgumentException.class, () -> builder.method("OPTIONS"));
        assertThrows(IllegalArgumentException.class, () -> builder.method("TRACE"));
        assertEquals(TestRequest.TEST_SESSION_ID, builder.getSessionId());
        builder.path("/foo/something-else.txt");
        assertEquals("/foo/something-else.txt", builder.getPath());
        assertEquals("Basic foo", builder.getHeader("Authorization"));
        assertThat(builder.getHeader("Cookie"), containsString("bonza"));
        assertThat(builder.getHeader("Cookie"), containsString("good"));
        assertThat(builder.getHeader("Cookie"), containsString("maxpos"));
        assertThat(builder.getHeader("Cookie"), not(containsString("bad")));
    }

    @Test
    public void testPushBuilderWithIdNoAuth()
    {
        String uri = "/foo/something";
        Request request = new TestRequest(null, null)
        {
            @Override
            public Principal getUserPrincipal()
            {
                return () -> "test";
            }
        };
        HttpFields.Mutable fields = HttpFields.build();
        request.setMetaData(new MetaData.Request("GET", HttpURI.from(uri), HttpVersion.HTTP_1_0, fields));
        assertTrue(request.isPushSupported());
        PushBuilder builder = request.newPushBuilder();
        assertNotNull(builder);
    }

    @Test
    public void testServletPathMapping()
    {
        ServletPathSpec spec;
        String uri;
        MatchedPath matched;
        ServletPathMapping m;

        spec = null;
        uri = null;
        matched = null;
        m = new ServletPathMapping(spec, null, uri, matched);
        assertThat(m.getMappingMatch(), nullValue());
        assertThat(m.getMatchValue(), is(""));
        assertThat(m.getPattern(), nullValue());
        assertThat(m.getServletName(), is(""));
        assertThat(m.getServletPath(), nullValue());
        assertThat(m.getPathInfo(), nullValue());

        spec = new ServletPathSpec("");
        uri = "/";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "Something", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.CONTEXT_ROOT));
        assertThat(m.getMatchValue(), is(""));
        assertThat(m.getPattern(), is(""));
        assertThat(m.getServletName(), is("Something"));
        assertThat(m.getServletPath(), is(""));
        assertThat(m.getPathInfo(), is("/"));

        spec = new ServletPathSpec("/");
        uri = "/some/path";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "Default", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.DEFAULT));
        assertThat(m.getMatchValue(), is(""));
        assertThat(m.getPattern(), is("/"));
        assertThat(m.getServletName(), is("Default"));
        assertThat(m.getServletPath(), is("/some/path"));
        assertThat(m.getPathInfo(), nullValue());

        spec = new ServletPathSpec("/foo/*");
        uri = "/foo/bar";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "FooServlet", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.PATH));
        assertThat(m.getMatchValue(), is("foo"));
        assertThat(m.getPattern(), is("/foo/*"));
        assertThat(m.getServletName(), is("FooServlet"));
        assertThat(m.getServletPath(), is("/foo"));
        assertThat(m.getPathInfo(), is("/bar"));

        uri = "/foo/";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "FooServlet", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.PATH));
        assertThat(m.getMatchValue(), is("foo"));
        assertThat(m.getPattern(), is("/foo/*"));
        assertThat(m.getServletName(), is("FooServlet"));
        assertThat(m.getServletPath(), is("/foo"));
        assertThat(m.getPathInfo(), is("/"));

        uri = "/foo";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "FooServlet", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.PATH));
        assertThat(m.getMatchValue(), is("foo"));
        assertThat(m.getPattern(), is("/foo/*"));
        assertThat(m.getServletName(), is("FooServlet"));
        assertThat(m.getServletPath(), is("/foo"));
        assertThat(m.getPathInfo(), nullValue());

        spec = new ServletPathSpec("*.jsp");
        uri = "/foo/bar.jsp";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "JspServlet", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.EXTENSION));
        assertThat(m.getMatchValue(), is("foo/bar"));
        assertThat(m.getPattern(), is("*.jsp"));
        assertThat(m.getServletName(), is("JspServlet"));
        assertThat(m.getServletPath(), is("/foo/bar.jsp"));
        assertThat(m.getPathInfo(), nullValue());

        spec = new ServletPathSpec("/catalog");
        uri = "/catalog";
        matched = spec.matched(uri);
        m = new ServletPathMapping(spec, "CatalogServlet", uri, matched);
        assertThat(m.getMappingMatch(), is(MappingMatch.EXACT));
        assertThat(m.getMatchValue(), is("catalog"));
        assertThat(m.getPattern(), is("/catalog"));
        assertThat(m.getServletName(), is("CatalogServlet"));
        assertThat(m.getServletPath(), is("/catalog"));
        assertThat(m.getPathInfo(), nullValue());
    }

    @Test
    public void testRegexPathMapping()
    {
        RegexPathSpec spec;
        ServletPathMapping m;

        spec = new RegexPathSpec("^/.*$");
        m = new ServletPathMapping(spec, "Something", "/some/path", spec.matched("/some/path"));
        assertThat(m.getMappingMatch(), nullValue());
        assertThat(m.getPattern(), is(spec.getDeclaration()));
        assertThat(m.getServletName(), is("Something"));
        assertThat(m.getServletPath(), is("/some/path"));
        assertThat(m.getPathInfo(), nullValue());
        assertThat(m.getMatchValue(), is("some/path"));

        spec = new RegexPathSpec("^/some(/.*)?$");
        m = new ServletPathMapping(spec, "Something", "/some/path", spec.matched("/some/path"));
        assertThat(m.getMappingMatch(), nullValue());
        assertThat(m.getPattern(), is(spec.getDeclaration()));
        assertThat(m.getServletName(), is("Something"));
        assertThat(m.getServletPath(), is("/some"));
        assertThat(m.getPathInfo(), is("/path"));
        assertThat(m.getMatchValue(), is("some"));

        m = new ServletPathMapping(spec, "Something", "/some", spec.matched("/some"));
        assertThat(m.getMappingMatch(), nullValue());
        assertThat(m.getPattern(), is(spec.getDeclaration()));
        assertThat(m.getServletName(), is("Something"));
        assertThat(m.getServletPath(), is("/some"));
        assertThat(m.getPathInfo(), nullValue());
        assertThat(m.getMatchValue(), is("some"));
    }

    private static long getFileCount(Path path)
    {
        try (Stream<Path> s = Files.list(path))
        {
            return s.count();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to get file list count: " + path, e);
        }
    }

    interface RequestTester
    {
        boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException;
    }

    private static class CookieRequestTester implements RequestTester
    {
        private String[] _cookieValues;

        public void setCookieArray(String[] cookieValues)
        {
            _cookieValues = cookieValues;
        }

        @Override
        public boolean check(HttpServletRequest request, HttpServletResponse response) throws IOException
        {
            Arrays.fill(_cookieValues, null);

            Cookie[] cookies = request.getCookies();
            for (int i = 0; cookies != null && i < cookies.length; i++)
            {
                _cookieValues[i] = cookies[i].getValue();
            }
            return true;
        }
    }

    private static class TestRequest extends Request
    {
        public static final String TEST_SESSION_ID = "abc123";
        Response _response = new Response(null, null);
        Cookie c1;
        Cookie c2;

        public TestRequest(HttpChannel channel, HttpInput input)
        {
            super(channel, input);
            c1 = new Cookie("maxpos", "xxx");
            c1.setMaxAge(1);
            c2 = new Cookie("maxneg", "yyy");
            c2.setMaxAge(-1);
        }

        @Override
        public boolean isPushSupported()
        {
            return true;
        }

        @Override
        public HttpSession getSession()
        {
            Session session = new Session(new SessionHandler(), new SessionData(TEST_SESSION_ID, "", "0.0.0.0", 0, 0, 0, 300));
            session.setResident(true); //necessary for session methods to not throw ISE
            return session;
        }

        @Override
        public Principal getUserPrincipal()
        {
            return () -> "user";
        }

        @Override
        public Response getResponse()
        {
            return _response;
        }

        @Override
        public Cookie[] getCookies()
        {
            return new Cookie[]{c1, c2};
        }
    }

    private static class RequestHandler extends AbstractHandler
    {
        private RequestTester _checker;

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);

            if (request.getContentLength() > 0 &&
                !request.getContentType().startsWith(MimeTypes.Type.FORM_ENCODED.asString()) &&
                !request.getContentType().startsWith("multipart/form-data"))
                assertNotNull(IO.toString(request.getInputStream()));

            if (_checker != null && _checker.check(request, response))
                response.setStatus(200);
            else
                response.sendError(500);
        }
    }

    private static class MultiPartRequestHandler extends AbstractHandler
    {
        RequestTester checker;
        File tmpDir;

        public MultiPartRequestHandler(File tmpDir, RequestTester checker)
        {
            this.tmpDir = tmpDir;
            this.checker = checker;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            if ("/cleanup".equals(target))
            {
                response.setStatus(200);
                return;
            }

            try
            {
                MultipartConfigElement mpce = new MultipartConfigElement(tmpDir.getAbsolutePath(), -1, -1, 2);
                request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, mpce);

                String field1 = request.getParameter("field1");
                assertNotNull(field1);

                Part foo = request.getPart("stuff");
                assertNotNull(foo);
                assertTrue(foo.getSize() > 0);
                response.setStatus(200);
                List<String> violations = (List<String>)request.getAttribute(HttpCompliance.VIOLATIONS_ATTR);
                if (violations != null)
                {
                    for (String v : violations)
                    {
                        response.addHeader("Violation", v);
                    }
                }

                if (checker != null && !checker.check(request, response))
                    response.sendError(500);
            }
            catch (IllegalStateException e)
            {
                //expected exception because no multipart config is set up
                assertTrue(e.getMessage().startsWith("No multipart config"));
                response.setStatus(200);
            }
            catch (Exception e)
            {
                response.sendError(500);
            }
        }
    }

    private static class BadMultiPartRequestHandler extends AbstractHandler
    {
        final Path tmpDir;

        public BadMultiPartRequestHandler(Path tmpDir)
        {
            this.tmpDir = tmpDir;
        }

        @Override
        public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException
        {
            ((Request)request).setHandled(true);
            if ("/cleanup".equals(target))
            {
                response.setStatus(200);
                return;
            }

            try
            {
                MultipartConfigElement mpce = new MultipartConfigElement(tmpDir.toString(), -1, -1, 2);
                request.setAttribute(Request.__MULTIPART_CONFIG_ELEMENT, mpce);

                //We should get an error when we getParams if there was a problem parsing the multipart
                request.getPart("xxx");
                //A 200 response is actually wrong here
            }
            catch (RuntimeException e)
            {
                response.sendError(500);
            }
        }
    }
}
