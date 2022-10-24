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

package org.eclipse.jetty.ee9.nested;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.eclipse.jetty.http.CookieCompliance;
import org.eclipse.jetty.http.HttpCookie;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.http.MetaData;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.server.Components;
import org.eclipse.jetty.server.ConnectionMetaData;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.HttpConfiguration;
import org.eclipse.jetty.server.HttpConnectionFactory;
import org.eclipse.jetty.server.HttpStream;
import org.eclipse.jetty.server.LocalConnector;
import org.eclipse.jetty.server.NetworkConnector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.TunnelSupport;
import org.eclipse.jetty.session.DefaultSessionCache;
import org.eclipse.jetty.session.DefaultSessionIdManager;
import org.eclipse.jetty.session.NullSessionDataStore;
import org.eclipse.jetty.session.Session;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Scheduler;
import org.eclipse.jetty.util.thread.TimerScheduler;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class ResponseTest
{
    private Server _server;
    private ContextHandler _context;
    private HttpChannel _channel;
    private ByteBuffer _content = BufferUtil.allocate(16 * 1024);

    @BeforeEach
    public void init() throws Exception
    {
        BufferUtil.clear(_content);

        _server = new Server();
        _context = new ContextHandler(_server);
        Scheduler scheduler = new TimerScheduler();
        HttpConfiguration config = new HttpConfiguration();
        LocalConnector connector = new LocalConnector(_server, null, scheduler, null, 1, new HttpConnectionFactory(config));
        _server.addConnector(connector);
        _context.setHandler(new DumpHandler());
        _server.start();

        SocketAddress local = InetSocketAddress.createUnresolved("myhost", 8888);
        EndPoint endPoint = new ByteArrayEndPoint(scheduler, 5000)
        {
            @Override
            public SocketAddress getLocalSocketAddress()
            {
                return local;
            }
        };

        ConnectionMetaData connectionMetaData = new MockConnectionMetaData(connector, endPoint)
        {
            @Override
            public SocketAddress getLocalSocketAddress()
            {
                return local;
            }
        };
        _channel = new HttpChannel(_context, connectionMetaData)
        {
            @Override
            protected HttpInput newHttpInput()
            {
                return new HttpInput(this)
                {
                    @Override
                    public boolean consumeAll()
                    {
                        return false;
                    }
                };
            }
        };
    }

    @AfterEach
    public void destroy() throws Exception
    {
        _server.stop();
        _server.join();
    }

    @Test
    public void testContentType() throws Exception
    {
        Response response = getResponse();

        assertNull(response.getContentType());

        response.setHeader("Content-Type", "text/something");
        assertEquals("text/something", response.getContentType());

        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=iso-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=iso-8859-1", response.getContentType());
        response.setHeader("name", "foo");

        Iterator<String> en = response.getHeaders("name").iterator();
        assertEquals("foo", en.next());
        assertFalse(en.hasNext());
        response.addHeader("name", "bar");
        en = response.getHeaders("name").iterator();
        assertEquals("foo", en.next());
        assertEquals("bar", en.next());
        assertFalse(en.hasNext());

        response.recycle();

        response.setContentType("text/html");
        assertEquals("text/html", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2;charset=utf-8");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();
        response.setContentType("text/xml;charset=ISO-8859-7");
        response.getWriter();
        assertEquals("text/xml;charset=ISO-8859-7", response.getContentType());
        response.setContentType("text/html;charset=UTF-8");
        assertEquals("text/html;charset=ISO-8859-7", response.getContentType());

        response.recycle();
        response.setContentType("text/html;charset=US-ASCII");
        response.getWriter();
        assertEquals("text/html;charset=US-ASCII", response.getContentType());

        response.recycle();
        response.setContentType("text/html; charset=UTF-8");
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());

        response.recycle();
        response.setContentType("text/json");
        response.getWriter();
        assertEquals("text/json", response.getContentType());

        response.recycle();
        response.setContentType("text/json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter();
        assertEquals("text/json;charset=utf-8", response.getContentType());
    }

    @SuppressWarnings("InjectedReferences") // to allow for invalid encoding strings in this testcase
    @Test
    public void testBadCharacterEncoding() throws IOException
    {
        Response response = getResponse();

        assertNull(response.getContentType());

        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar;charset=abc");
        assertEquals("foo/bar;charset=abc", response.getContentType());

        response.recycle();
        response.setContentType("foo/bar;charset=abc");
        response.setCharacterEncoding("xyz");
        assertEquals("foo/bar;charset=xyz", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setContentType("foo/bar");
        response.setCharacterEncoding(null);
        assertEquals("foo/bar", response.getContentType());

        response.recycle();
        response.setCharacterEncoding("xyz");
        response.setCharacterEncoding(null);
        response.setContentType("foo/bar");
        assertEquals("foo/bar", response.getContentType());
        response.recycle();
        response.addHeader("Content-Type", "text/something");
        assertEquals("text/something", response.getContentType());

        response.recycle();
        response.addHeader("Content-Type", "application/json");
        response.getWriter();
        assertEquals("application/json", response.getContentType());
    }

    @Test
    public void testInferredCharset() throws Exception
    {
        // Inferred from encoding.properties
        Response response = getResponse();

        assertNull(response.getContentType());

        response.setHeader("Content-Type", "application/xhtml+xml");
        assertEquals("application/xhtml+xml", response.getContentType());
        response.getWriter();
        assertEquals("application/xhtml+xml;charset=utf-8", response.getContentType());
        assertEquals("utf-8", response.getCharacterEncoding());
    }

    @Test
    public void testAssumedCharset() throws Exception
    {
        Response response = getResponse();

        // Assumed from known types
        assertNull(response.getContentType());
        response.setHeader("Content-Type", "text/json");
        assertEquals("text/json", response.getContentType());
        response.getWriter();
        assertEquals("text/json", response.getContentType());
        assertEquals("utf-8", response.getCharacterEncoding());

        response.recycle();

        // Assumed from encoding.properties
        assertNull(response.getContentType());
        response.setHeader("Content-Type", "application/vnd.api+json");
        assertEquals("application/vnd.api+json", response.getContentType());
        response.getWriter();
        assertEquals("application/vnd.api+json", response.getContentType());
        assertEquals("utf-8", response.getCharacterEncoding());
    }

    @Test
    public void testStrangeContentType() throws Exception
    {
        Response response = getResponse();

        assertNull(response.getContentType());

        response.recycle();
        response.setContentType("text/html;charset=utf-8;charset=UTF-8");
        response.getWriter();
        assertEquals("text/html;charset=utf-8;charset=UTF-8", response.getContentType());
        assertEquals("utf-8", response.getCharacterEncoding().toLowerCase(Locale.ENGLISH));
    }

    @Test
    public void testLocale()
    {
        Response response = getResponse();

        ContextHandler context = _context;
        context.addLocaleEncoding(Locale.ENGLISH.toString(), "ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(), "ISO-8859-2");

        response.setLocale(java.util.Locale.ITALIAN);
        assertNull(response.getContentType());
        response.setContentType("text/plain");
        assertEquals("text/plain;charset=ISO-8859-2", response.getContentType());

        response.recycle();
        response.setContentType("text/plain");
        response.setCharacterEncoding("utf-8");
        response.setLocale(java.util.Locale.ITALIAN);
        assertEquals("text/plain;charset=utf-8", response.getContentType());
        assertTrue(response.toString().indexOf("charset=utf-8") > 0);
    }

    @Test
    public void testLocaleFormat() throws Exception
    {
        Response response = getResponse();

        ContextHandler context = _context;
        context.addLocaleEncoding(Locale.ENGLISH.toString(), "ISO-8859-1");
        context.addLocaleEncoding(Locale.ITALIAN.toString(), "ISO-8859-2");

        response.setLocale(java.util.Locale.ITALIAN);

        PrintWriter out = response.getWriter();

        out.format("TestA1 %,.2f%n", 1234567.89);
        out.format("TestA2 %,.2f%n", 1234567.89);

        out.format((java.util.Locale)null, "TestB1 %,.2f%n", 1234567.89);
        out.format((java.util.Locale)null, "TestB2 %,.2f%n", 1234567.89);

        out.format(Locale.ENGLISH, "TestC1 %,.2f%n", 1234567.89);
        out.format(Locale.ENGLISH, "TestC2 %,.2f%n", 1234567.89);

        out.format(Locale.ITALIAN, "TestD1 %,.2f%n", 1234567.89);
        out.format(Locale.ITALIAN, "TestD2 %,.2f%n", 1234567.89);

        out.close();

        /* Test A */
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestA1 1.234.567,89"));
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestA2 1.234.567,89"));

        /* Test B */
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestB1 1.234.567,89"));
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestB2 1.234.567,89"));

        /* Test C */
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestC1 1,234,567.89"));
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestC2 1,234,567.89"));

        /* Test D */
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestD1 1.234.567,89"));
        assertThat(BufferUtil.toString(_content), Matchers.containsString("TestD2 1.234.567,89"));
    }

    @Test
    @Disabled // TODO
    public void testResponseCharacterEncoding() throws Exception
    {
        //test setting the default response character encoding
        Response response = getResponse();
        assertThat("utf-16", Matchers.equalTo(response.getCharacterEncoding()));

        response.recycle();

        //test that explicit overrides default
        response = getResponse();
        response.setCharacterEncoding("ascii");
        assertThat("ascii", Matchers.equalTo(response.getCharacterEncoding()));
        //getWriter should not change explicit character encoding
        response.getWriter();
        assertThat("ascii", Matchers.equalTo(response.getCharacterEncoding()));

        response.recycle();

        //test that assumed overrides default
        response = getResponse();
        response.setContentType("application/json");
        assertThat("utf-8", Matchers.equalTo(response.getCharacterEncoding()));
        response.getWriter();
        //getWriter should not have modified character encoding
        assertThat("utf-8", Matchers.equalTo(response.getCharacterEncoding()));

        response.recycle();

        //test that inferred overrides default
        response = getResponse();
        response.setContentType("application/xhtml+xml");
        assertThat("utf-8", Matchers.equalTo(response.getCharacterEncoding()));
        //getWriter should not have modified character encoding
        response.getWriter();
        assertThat("utf-8", Matchers.equalTo(response.getCharacterEncoding()));

        response.recycle();

        //test that without a default or any content type, use iso-8859-1
        response = getResponse();
        assertThat("iso-8859-1", Matchers.equalTo(response.getCharacterEncoding()));
        //getWriter should not have modified character encoding
        response.getWriter();
        assertThat("iso-8859-1", Matchers.equalTo(response.getCharacterEncoding()));
    }

    @Test
    public void testLocaleAndContentTypeEncoding() throws Exception
    {
        _server.stop();
        MimeTypes.getInferredEncodings().put("text/html", "iso-8859-1");
        _context.addLocaleEncoding("ja", "euc-jp");
        _context.addLocaleEncoding("zh_CN", "gb18030");
        _server.start();

        Response response = getResponse();

        response.setContentType("text/html");
        assertEquals("iso-8859-1", response.getCharacterEncoding());

        // setLocale should change character encoding based on
        // locale-encoding-mapping-list
        response.setLocale(Locale.JAPAN);
        assertEquals("euc-jp", response.getCharacterEncoding());

        // setLocale should change character encoding based on
        // locale-encoding-mapping-list
        response.setLocale(Locale.CHINA);
        assertEquals("gb18030", response.getCharacterEncoding());

        // setContentType here doesn't define character encoding
        response.setContentType("text/html");
        assertEquals("gb18030", response.getCharacterEncoding());

        // setCharacterEncoding should still be able to change encoding
        response.setCharacterEncoding("utf-8");
        assertEquals("utf-8", response.getCharacterEncoding());

        // setLocale should not override explicit character encoding request
        response.setLocale(Locale.JAPAN);
        assertEquals("utf-8", response.getCharacterEncoding());

        // setContentType should still be able to change encoding
        response.setContentType("text/html;charset=gb18030");
        assertEquals("gb18030", response.getCharacterEncoding());

        // setCharacterEncoding should still be able to change encoding
        response.setCharacterEncoding("utf-8");
        assertEquals("utf-8", response.getCharacterEncoding());

        // getWriter should freeze the character encoding
        PrintWriter pw = response.getWriter();
        assertEquals("utf-8", response.getCharacterEncoding());

        // setCharacterEncoding should no longer be able to change the encoding
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("utf-8", response.getCharacterEncoding());

        // setLocale should not override explicit character encoding request
        response.setLocale(Locale.JAPAN);
        assertEquals("utf-8", response.getCharacterEncoding());
    }

    @Test
    public void testContentTypeCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setContentType("foo/bar");
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();

        response.setContentType("text/html");
        response.setCharacterEncoding("UTF-8");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testContentEncodingViaContentTypeChange() throws Exception
    {
        Response response = getResponse();
        response.setContentType("text/html;charset=Shift_Jis");
        assertEquals("Shift_Jis", response.getCharacterEncoding());

        response.setContentType("text/xml");
        assertEquals("Shift_Jis", response.getCharacterEncoding());
    }

    @Test
    public void testCharacterEncodingContentType() throws Exception
    {
        Response response = getResponse();
        response.setCharacterEncoding("utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf-8");
        response.setContentType("text/html");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=UTF-8");
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=UTF-8", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("ISO-8859-1");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());

        response.recycle();
        response.reopen();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; charset=utf-8");
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
        response.setCharacterEncoding("iso-8859-1");
        assertEquals("text/xml;charset=utf-8", response.getContentType());

        response.recycle();
        response.reopen();
        response.setCharacterEncoding("utf-16");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-16", response.getContentType());
        response.getOutputStream();
        response.setCharacterEncoding("utf-8");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.flushBuffer();
        response.setCharacterEncoding("utf-16");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
    }

    @Test
    public void testResetWithNewSession() throws Exception
    {
        Response response = getResponse();
        Request request = response.getHttpChannel().getRequest();

        _server.stop();
        SessionHandler sessionHandler = new SessionHandler();
        sessionHandler.setUsingCookies(true);
        _context.setHandler(sessionHandler);
        _server.start();
        request.setSessionManager(sessionHandler.getSessionManager());
        HttpSession session = request.getSession(true);

        assertThat(session, not(nullValue()));
        assertTrue(session.isNew());

        HttpField setCookie = response.getHttpFields().getField(HttpHeader.SET_COOKIE);
        assertThat(setCookie, not(nullValue()));
        assertThat(setCookie.getValue(), startsWith("JSESSIONID"));
        assertThat(setCookie.getValue(), containsString(session.getId()));
        response.setHeader("Some", "Header");
        response.addCookie(new Cookie("Some", "Cookie"));
        response.getOutputStream().print("X");
        assertThat(response.getHttpFields().size(), is(4));

        response.reset();

        setCookie = response.getHttpFields().getField(HttpHeader.SET_COOKIE);
        assertThat(setCookie, not(nullValue()));
        assertThat(setCookie.getValue(), startsWith("JSESSIONID"));
        assertThat(setCookie.getValue(), containsString(session.getId()));
        assertThat(response.getHttpFields().size(), is(2));
        response.getWriter();
    }

    @Test
    public void testResetContentTypeWithoutCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf-8");
        response.setContentType("wrong/answer");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.getWriter();
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
    }

    @Test
    public void testResetContentTypeWithCharacterEncoding() throws Exception
    {
        Response response = getResponse();

        response.setContentType("wrong/answer;charset=utf-8");
        response.setContentType("foo/bar");
        assertEquals("foo/bar;charset=utf-8", response.getContentType());
        response.setContentType("wrong/answer;charset=utf-8");
        response.getWriter();
        response.setContentType("foo2/bar2;charset=utf-16");
        assertEquals("foo2/bar2;charset=utf-8", response.getContentType());
    }

    interface ServletOutputStreamCase
    {
        /**
         * Run case against established ServletOutputStream.
         *
         * @param outputStream the ServletOutputStream from HttpServletResponse.getOutputStream()
         * @return the expected results
         */
        String run(ServletOutputStream outputStream) throws IOException;
    }

    public static Stream<Arguments> outputStreamCases()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal (non CRLF) Cases for ServletOutputStream
        cases.add(Arguments.of(
            "print(boolean)",
            (ServletOutputStreamCase)out ->
            {
                out.print(true);
                out.print("/");
                out.print(false);

                return "true/false";
            })
        );
        cases.add(Arguments.of(
            "print(char)",
            (ServletOutputStreamCase)out ->
            {
                out.print('a');
                out.print('b');
                out.print('c');

                return "abc";
            })
        );
        cases.add(Arguments.of(
            "print(double)",
            (ServletOutputStreamCase)out ->
            {
                double d1 = 3.14;
                out.print(d1);
                return "3.14";
            })
        );
        cases.add(Arguments.of(
            "print(double) - NaN",
            (ServletOutputStreamCase)out ->
            {
                double d1 = Double.NaN;
                out.print(d1);
                return "NaN";
            })
        );
        cases.add(Arguments.of(
            "print(double) - Negative Infinity",
            (ServletOutputStreamCase)out ->
            {
                double d1 = Double.NEGATIVE_INFINITY;
                out.print(d1);
                return "-Infinity";
            })
        );
        cases.add(Arguments.of(
            "print(float)",
            (ServletOutputStreamCase)out ->
            {
                float f1 = 3.14159f;
                out.print(f1);
                return "3.14159";
            })
        );
        cases.add(Arguments.of(
            "print(float) - NaN",
            (ServletOutputStreamCase)out ->
            {
                float f1 = Float.NaN;
                out.print(f1);
                return "NaN";
            })
        );
        cases.add(Arguments.of(
            "print(float) - Negative Infinity",
            (ServletOutputStreamCase)out ->
            {
                float f1 = Float.NEGATIVE_INFINITY;
                out.print(f1);
                return "-Infinity";
            })
        );
        cases.add(Arguments.of(
            "print(int) - positive",
            (ServletOutputStreamCase)out ->
            {
                int i = 123456789;
                out.print(i);
                return "123456789";
            })
        );
        cases.add(Arguments.of(
            "print(int) - negative",
            (ServletOutputStreamCase)out ->
            {
                int i = -987654321;
                out.print(i);
                return "-987654321";
            })
        );
        cases.add(Arguments.of(
            "print(int) - zero",
            (ServletOutputStreamCase)out ->
            {
                int i = 0;
                out.print(i);
                return "0";
            })
        );
        cases.add(Arguments.of(
            "print(long)",
            (ServletOutputStreamCase)out ->
            {
                long l = 111222333444555666L;
                out.print(l);
                return "111222333444555666";
            })
        );
        cases.add(Arguments.of(
            "print(long) - max_long",
            (ServletOutputStreamCase)out ->
            {
                long l = Long.MAX_VALUE;
                out.print(l);
                return "9223372036854775807";
            })
        );
        cases.add(Arguments.of(
            "print(String)",
            (ServletOutputStreamCase)out ->
            {
                out.print("ABC");
                return "ABC";
            })
        );

        // Normal (CRLF) Cases for ServletOutputStream
        cases.add(Arguments.of(
            "println()",
            (ServletOutputStreamCase)out ->
            {
                out.println();
                return "\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(boolean)",
            (ServletOutputStreamCase)out ->
            {
                out.println(false);

                return "false\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(char)",
            (ServletOutputStreamCase)out ->
            {
                out.println('a');

                return "a\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(double)",
            (ServletOutputStreamCase)out ->
            {
                double d1 = 3.14;
                out.println(d1);
                return "3.14\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(double) - NaN",
            (ServletOutputStreamCase)out ->
            {
                double d1 = Double.NaN;
                out.println(d1);
                return "NaN\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(double) - Negative Infinity",
            (ServletOutputStreamCase)out ->
            {
                double d1 = Double.NEGATIVE_INFINITY;
                out.println(d1);
                return "-Infinity\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(float)",
            (ServletOutputStreamCase)out ->
            {
                float f1 = 3.14159f;
                out.println(f1);
                return "3.14159\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(float) - NaN",
            (ServletOutputStreamCase)out ->
            {
                float f1 = Float.NaN;
                out.println(f1);
                return "NaN\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(float) - Negative Infinity",
            (ServletOutputStreamCase)out ->
            {
                float f1 = Float.NEGATIVE_INFINITY;
                out.println(f1);
                return "-Infinity\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(int) - positive",
            (ServletOutputStreamCase)out ->
            {
                int i = 123456789;
                out.println(i);
                return "123456789\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(int) - negative",
            (ServletOutputStreamCase)out ->
            {
                int i = -987654321;
                out.println(i);
                return "-987654321\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(int) - zero",
            (ServletOutputStreamCase)out ->
            {
                int i = 0;
                out.println(i);
                return "0\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(long)",
            (ServletOutputStreamCase)out ->
            {
                long l = 111222333444555666L;
                out.println(l);
                return "111222333444555666\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(long) - max_long",
            (ServletOutputStreamCase)out ->
            {
                long l = Long.MAX_VALUE;
                out.println(l);
                return "9223372036854775807\r\n";
            })
        );
        cases.add(Arguments.of(
            "println(String)",
            (ServletOutputStreamCase)out ->
            {
                out.println("ABC");
                return "ABC\r\n";
            })
        );

        // Special Cases for ServletOutputStream
        cases.add(Arguments.of(
            "print(String) - empty", // from Issue #3545
            (ServletOutputStreamCase)out ->
            {
                out.print("ABC");
                out.print(""); // should not result in "null"
                return "ABC";
            })
        );
        cases.add(Arguments.of(
            "print(String) - with empty and CRLF", // from Issue #3545
            (ServletOutputStreamCase)out ->
            {
                out.print("ABC");
                out.print("");
                out.println();
                return "ABC\r\n";
            })
        );
        cases.add(Arguments.of(
            "print(String) - unicode", // from issue #3207
            (ServletOutputStreamCase)out ->
            {
                String expected = "";
                out.print("ABC");
                expected += "ABC";
                out.println("XYZ");
                expected += "XYZ\r\n";
                String s = "\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC".repeat(100);
                out.println(s);
                expected += s + "\r\n";
                return expected;
            })
        );

        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("outputStreamCases")
    public void testServletOutputStream(@SuppressWarnings("unused") String description,
                                        ServletOutputStreamCase outputStreamConsumer) throws IOException
    {
        Response response = getResponse();
        response.setCharacterEncoding(UTF_8.name());

        String expectedResults;

        try (ServletOutputStream outputStream = response.getOutputStream())
        {
            expectedResults = outputStreamConsumer.run(outputStream);
            assertNotNull(expectedResults, "Testcase invalid - expected results may not be null");
            outputStream.flush();
        }

        assertEquals(expectedResults, BufferUtil.toString(_content, UTF_8));
    }

    interface ServletPrintWriterCase
    {
        /**
         * Run case against established Servlet PrintWriter.
         *
         * @param writer the Servlet PrintWriter from HttpServletResponse.getWriter();
         * @return the expected results
         */
        String accept(PrintWriter writer) throws IOException;
    }

    public static Stream<Arguments> writerCases()
    {
        List<Arguments> cases = new ArrayList<>();

        // Normal (append) Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "append(char)",
            (ServletPrintWriterCase)writer ->
            {
                writer.append('a');
                writer.append('b').append('c');
                return "abc";
            })
        );
        cases.add(Arguments.of(
            "append(CharSequence)",
            (ServletPrintWriterCase)writer ->
            {
                CharSequence charSequence = "xyz";
                writer.append(charSequence);
                return "xyz";
            })
        );
        cases.add(Arguments.of(
            "append(CharSequence, int, int)",
            (ServletPrintWriterCase)writer ->
            {
                CharSequence charSequence = "Not written in Javascript";
                writer.append(charSequence, 4, 19);
                return "written in Java";
            })
        );

        // Normal (Format) Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "format(Locale, String, Object[])",
            (ServletPrintWriterCase)writer ->
            {
                // Dec 07, 2020
                LocalDate jetty10ReleaseDate = LocalDate.of(2020, 12, 7);

                String format = "Jetty 10 was released on %1$tB %1$te,%1$tY";
                Locale locale = Locale.ITALY;
                writer.format(locale, format, jetty10ReleaseDate);
                return String.format(locale, format, jetty10ReleaseDate);
            })
        );

        cases.add(Arguments.of(
            "format(String, Object[])",
            (ServletPrintWriterCase)writer ->
            {
                // Dec 07, 2020
                LocalDate jetty10ReleaseDate = LocalDate.of(2020, 12, 7);
                String format = "Jetty 10 was released on %1$tB %1$te,%1$tY";
                writer.format(format, jetty10ReleaseDate);
                return String.format(Locale.getDefault(), format, jetty10ReleaseDate);
            })
        );

        // Normal (non CRLF) Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "print(boolean)",
            (ServletPrintWriterCase)writer ->
            {
                writer.print(true);
                writer.print("/");
                writer.print(false);

                return "true/false";
            })
        );
        cases.add(Arguments.of(
            "print(char)",
            (ServletPrintWriterCase)writer ->
            {
                writer.print('a');
                writer.print('b');
                writer.print('c');

                return "abc";
            })
        );
        cases.add(Arguments.of(
            "print(char[])",
            (ServletPrintWriterCase)writer ->
            {
                char[] charArray = new char[]{'a', 'b', 'c'};
                writer.print(charArray);
                return "abc";
            })
        );
        cases.add(Arguments.of(
            "print(double)",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = 3.14;
                writer.print(d1);
                return "3.14";
            })
        );
        cases.add(Arguments.of(
            "print(double) - NaN",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = Double.NaN;
                writer.print(d1);
                return "NaN";
            })
        );
        cases.add(Arguments.of(
            "print(double) - Negative Infinity",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = Double.NEGATIVE_INFINITY;
                writer.print(d1);
                return "-Infinity";
            })
        );
        cases.add(Arguments.of(
            "print(float)",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = 3.14159f;
                writer.print(f1);
                return "3.14159";
            })
        );
        cases.add(Arguments.of(
            "print(float) - NaN",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = Float.NaN;
                writer.print(f1);
                return "NaN";
            })
        );
        cases.add(Arguments.of(
            "print(float) - Negative Infinity",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = Float.NEGATIVE_INFINITY;
                writer.print(f1);
                return "-Infinity";
            })
        );
        cases.add(Arguments.of(
            "print(int) - positive",
            (ServletPrintWriterCase)writer ->
            {
                int i = 123456789;
                writer.print(i);
                return "123456789";
            })
        );
        cases.add(Arguments.of(
            "print(int) - negative",
            (ServletPrintWriterCase)writer ->
            {
                int i = -987654321;
                writer.print(i);
                return "-987654321";
            })
        );
        cases.add(Arguments.of(
            "print(int) - zero",
            (ServletPrintWriterCase)writer ->
            {
                int i = 0;
                writer.print(i);
                return "0";
            })
        );
        cases.add(Arguments.of(
            "print(long)",
            (ServletPrintWriterCase)writer ->
            {
                long l = 111222333444555666L;
                writer.print(l);
                return "111222333444555666";
            })
        );
        cases.add(Arguments.of(
            "print(long) - max_long",
            (ServletPrintWriterCase)writer ->
            {
                long l = Long.MAX_VALUE;
                writer.print(l);
                return "9223372036854775807";
            })
        );
        cases.add(Arguments.of(
            "print(Object) - foo",
            (ServletPrintWriterCase)writer ->
            {
                Object bar = new Object()
                {
                    @Override
                    public String toString()
                    {
                        return "((Bar))";
                    }
                };

                writer.print(bar);
                return "((Bar))";
            })
        );
        cases.add(Arguments.of(
            "print(String)",
            (ServletPrintWriterCase)writer ->
            {
                writer.print("ABC");
                return "ABC";
            })
        );

        // Normal (Format / Convenience) Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "printf(Locale, String, Object[])",
            (ServletPrintWriterCase)writer ->
            {
                // Dec 07, 2020
                LocalDate jetty10ReleaseDate = LocalDate.of(2020, 12, 7);

                String format = "Jetty 10 was released on %1$tB %1$te,%1$tY";
                Locale locale = Locale.ITALY;
                writer.printf(locale, format, jetty10ReleaseDate);
                return String.format(locale, format, jetty10ReleaseDate);
            })
        );

        cases.add(Arguments.of(
            "printf(String, Object[])",
            (ServletPrintWriterCase)writer ->
            {
                // Dec 07, 2020
                LocalDate jetty10ReleaseDate = LocalDate.of(2020, 12, 7);
                String format = "Jetty 10 was released on %1$tB %1$te,%1$tY";
                writer.printf(format, jetty10ReleaseDate);
                return String.format(Locale.getDefault(), format, jetty10ReleaseDate);
            })
        );

        // Using Servlet PrintWriter.print() methods results in the system specific line separator.
        // Eg: just "\n" for Linux, but "\r\n" for Windows.

        String lineSep = System.lineSeparator();

        // Normal (CRLF) Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "println()",
            (ServletPrintWriterCase)writer ->
            {
                writer.println();
                return lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(boolean)",
            (ServletPrintWriterCase)writer ->
            {
                writer.println(false);

                return "false" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(char)",
            (ServletPrintWriterCase)writer ->
            {
                writer.println('a');
                return "a" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(double)",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = 3.14;
                writer.println(d1);
                return "3.14" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(double) - NaN",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = Double.NaN;
                writer.println(d1);
                return "NaN" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(double) - Negative Infinity",
            (ServletPrintWriterCase)writer ->
            {
                double d1 = Double.NEGATIVE_INFINITY;
                writer.println(d1);
                return "-Infinity" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(float)",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = 3.14159f;
                writer.println(f1);
                return "3.14159" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(float) - NaN",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = Float.NaN;
                writer.println(f1);
                return "NaN" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(float) - Negative Infinity",
            (ServletPrintWriterCase)writer ->
            {
                float f1 = Float.NEGATIVE_INFINITY;
                writer.println(f1);
                return "-Infinity" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(int) - positive",
            (ServletPrintWriterCase)writer ->
            {
                int i = 123456789;
                writer.println(i);
                return "123456789" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(int) - negative",
            (ServletPrintWriterCase)writer ->
            {
                int i = -987654321;
                writer.println(i);
                return "-987654321" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(int) - zero",
            (ServletPrintWriterCase)writer ->
            {
                int i = 0;
                writer.println(i);
                return "0" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(long)",
            (ServletPrintWriterCase)writer ->
            {
                long l = 111222333444555666L;
                writer.println(l);
                return "111222333444555666" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(long) - max_long",
            (ServletPrintWriterCase)writer ->
            {
                long l = Long.MAX_VALUE;
                writer.println(l);
                return "9223372036854775807" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(Object) - foo",
            (ServletPrintWriterCase)writer ->
            {
                Object zed = new Object()
                {
                    @Override
                    public String toString()
                    {
                        return "((Zed))";
                    }
                };

                writer.println(zed);
                return "((Zed))" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "println(String)",
            (ServletPrintWriterCase)writer ->
            {
                writer.println("ABC");
                return "ABC"  + lineSep;
            })
        );

        // Special Cases for Servlet PrintWriter
        cases.add(Arguments.of(
            "print(String) - empty", // from Issue #3545
            (ServletPrintWriterCase)writer ->
            {
                writer.print("ABC");
                writer.print(""); // should not result in "null"
                return "ABC";
            })
        );
        cases.add(Arguments.of(
            "print(String) - with empty and CRLF", // from Issue #3545
            (ServletPrintWriterCase)writer ->
            {
                writer.print("ABC");
                writer.print("");
                writer.println();
                return "ABC" + lineSep;
            })
        );
        cases.add(Arguments.of(
            "print(String) - unicode", // from issue #3207
            (ServletPrintWriterCase)writer ->
            {
                String expected = "";
                writer.print("ABC");
                expected += "ABC";
                writer.println("XYZ");
                expected += "XYZ" + lineSep;
                String s = "\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC\u20AC".repeat(100);
                writer.println(s);
                expected += s + lineSep;
                return expected;
            })
        );

        return cases.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("writerCases")
    public void testServletPrintWriter(@SuppressWarnings("unused") String description,
                                        ServletPrintWriterCase writerConsumer) throws IOException
    {
        Response response = getResponse();
        response.setCharacterEncoding(UTF_8.name());

        String expectedResults;

        try (PrintWriter writer = response.getWriter())
        {
            expectedResults = writerConsumer.accept(writer);
            assertNotNull(expectedResults, "Testcase invalid - expected results may not be null");
            writer.flush();
        }

        assertEquals(expectedResults, BufferUtil.toString(_content, UTF_8));
    }

    @Test
    public void testContentTypeWithOther() throws Exception
    {
        Response response = getResponse();

        response.setContentType("foo/bar; other=xyz");
        assertEquals("foo/bar; other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=xyz;charset=iso-8859-1", response.getContentType());
        response.setContentType("foo2/bar2");
        assertEquals("foo2/bar2;charset=iso-8859-1", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("uTf-8");
        response.setContentType("text/html; other=xyz");
        assertEquals("text/html; other=xyz;charset=utf-8", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz;charset=utf-8", response.getContentType());
        response.setContentType("text/xml");
        assertEquals("text/xml;charset=utf-8", response.getContentType());
    }

    @Test
    public void testContentTypeWithCharacterEncodingAndOther() throws Exception
    {
        Response response = getResponse();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; charset=utf-8 other=xyz");
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; charset=utf-8 other=xyz", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("text/html; other=xyz charset=utf-8");
        assertEquals("text/html; other=xyz charset=utf-8;charset=utf-16", response.getContentType());
        response.getWriter();
        assertEquals("text/html; other=xyz charset=utf-8;charset=utf-16", response.getContentType());

        response.recycle();

        response.setCharacterEncoding("utf16");
        response.setContentType("foo/bar; other=pq charset=utf-8 other=xyz");
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=utf-16", response.getContentType());
        response.getWriter();
        assertEquals("foo/bar; other=pq charset=utf-8 other=xyz;charset=utf-16", response.getContentType());
    }

    public static Stream<Object[]> sendErrorTestCodes()
    {
        List<Object[]> data = new ArrayList<>();
        data.add(new Object[]{404, null, "Not Found"});
        data.add(new Object[]{500, "Database Error", "Database Error"});
        data.add(new Object[]{406, "Super Nanny", "Super Nanny"});
        return data.stream();
    }

    @ParameterizedTest
    @MethodSource(value = "sendErrorTestCodes")
    public void testStatusCodes(int code, String message, String expectedMessage) throws Exception
    {
        Response response = getResponse();
        assertThat(response.getHttpChannel().getState().handling(), is(HttpChannelState.Action.DISPATCH));

        if (message == null)
            response.sendError(code);
        else
            response.sendError(code, message);

        assertTrue(response.getHttpOutput().isClosed());
        assertEquals(code, response.getStatus());
        assertEquals(null, response.getReason());

        response.setHeader("Should-Be-Ignored", "value");
        assertFalse(response.getHttpFields().contains("Should-Be-Ignored"));

        assertEquals(expectedMessage, response.getHttpChannel().getRequest().getAttribute(RequestDispatcher.ERROR_MESSAGE));
        assertThat(response.getHttpChannel().getState().unhandle(), is(HttpChannelState.Action.SEND_ERROR));
        assertThat(response.getHttpChannel().getState().unhandle(), is(HttpChannelState.Action.COMPLETE));
    }

    @ParameterizedTest
    @MethodSource(value = "sendErrorTestCodes")
    public void testStatusCodesNoErrorHandler(int code, String message, String expectedMessage) throws Exception
    {
        _server.removeBean(_server.getBean(ErrorHandler.class));
        testStatusCodes(code, message, expectedMessage);
    }

    @Test
    @Disabled // TODO
    public void testWriteCheckError() throws Exception
    {
        Response response = getResponse();

        PrintWriter writer = response.getWriter();
        writer.println("test");
        writer.flush();
        assertFalse(writer.checkError());

        Throwable cause = new IOException("problem at mill");
        _channel.abort(cause);
        writer.println("test");
        assertTrue(writer.checkError());

        writer.println("test"); // this should not cause an Exception
        assertTrue(writer.checkError());
    }

    @Test
    public void testEncodeRedirect() throws Exception
    {
        SessionHandler sessionHandler = addSessionHandler();

        Response response = getResponse();
        Request request = response.getHttpChannel().getRequest();
        request.onDispatch(HttpURI.build(request.getHttpURI()).host("myhost").port(8888), "/path/info");

        assertEquals("http://myhost:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));

        request.setSessionManager(sessionHandler.getSessionManager());
        request.setRequestedSessionId("12345");
        request.setRequestedSessionIdFromCookie(false);
        assertNotNull(request.getSession(true));
        assertThat(request.getSession(false).getId(), is("12345"));

        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost:8888/other/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        sessionHandler.setCheckingRemoteSessionIdEncoding(true);
        assertEquals("http://myhost:8888/path/info;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        assertEquals("http://myhost/path/info;param?query=0&more=1#target", response.encodeURL("http://myhost/path/info;param?query=0&more=1#target"));

        // TODO assertEquals("http://myhost:8888/other/info;param?query=0&more=1#target", response.encodeURL("http://myhost:8888/other/info;param?query=0&more=1#target"));

        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888"));
        assertEquals("https://myhost:8888/;jsessionid=12345", response.encodeURL("https://myhost:8888"));
        assertEquals("mailto:/foo", response.encodeURL("mailto:/foo"));
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888/"));
        assertEquals("http://myhost:8888/;jsessionid=12345", response.encodeURL("http://myhost:8888/;jsessionid=7777"));
        assertEquals("http://myhost:8888/;param;jsessionid=12345?query=0&more=1#target", response.encodeURL("http://myhost:8888/;param?query=0&more=1#target"));
        assertEquals("http://other:8888/path/info;param?query=0&more=1#target", response.encodeURL("http://other:8888/path/info;param?query=0&more=1#target"));
        sessionHandler.setCheckingRemoteSessionIdEncoding(false);
        assertEquals("/foo;jsessionid=12345", response.encodeURL("/foo"));
        assertEquals("/;jsessionid=12345", response.encodeURL("/"));
        assertEquals("/foo.html;jsessionid=12345#target", response.encodeURL("/foo.html#target"));
        assertEquals(";jsessionid=12345", response.encodeURL(""));
    }

    private SessionHandler addSessionHandler() throws Exception
    {
        _server.stop();
        SessionHandler handler = new SessionHandler();
        DefaultSessionCache sessionCache = new DefaultSessionCache(handler.getSessionManager());
        NullSessionDataStore dataStore = new NullSessionDataStore();
        sessionCache.setSessionDataStore(dataStore);
        DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(_server)
        {
            @Override
            public boolean isIdInUse(String id)
            {
                return "12345".equals(id);
            }
        };

        _server.addBean(sessionIdManager);
        sessionIdManager.setWorkerName(null);
        handler.getSessionManager().setSessionCache(sessionCache);
        handler.getSessionManager().setSessionIdManager(sessionIdManager);
        handler.setCheckingRemoteSessionIdEncoding(false);
        _context.setHandler(handler);
        _server.start();
        return handler;
    }

    public static Stream<Arguments> redirects()
    {
        return Stream.of(
            // No cookie
            Arguments.of("http://myhost:8888/other/location;jsessionid=12345?name=value", "http://myhost:8888/other/location;jsessionid=12345?name=value", false),
            Arguments.of("/other/location;jsessionid=12345?name=value", "http://@HOST@@PORT@/other/location;jsessionid=12345?name=value", false),
            Arguments.of("./location;jsessionid=12345?name=value", "http://@HOST@@PORT@/path/location;jsessionid=12345?name=value", false),

            // From cookie
            Arguments.of("/other/location", "http://@HOST@@PORT@/other/location", true),
            Arguments.of("/other/l%20cation", "http://@HOST@@PORT@/other/l%20cation", true),
            Arguments.of("location", "http://@HOST@@PORT@/path/location", true),
            Arguments.of("./location", "http://@HOST@@PORT@/path/location", true),
            Arguments.of("../location", "http://@HOST@@PORT@/location", true),
            Arguments.of("/other/l%20cation", "http://@HOST@@PORT@/other/l%20cation", true),
            Arguments.of("l%20cation", "http://@HOST@@PORT@/path/l%20cation", true),
            Arguments.of("./l%20cation", "http://@HOST@@PORT@/path/l%20cation", true),
            Arguments.of("../l%20cation", "http://@HOST@@PORT@/l%20cation", true),
            Arguments.of("../locati%C3%abn", "http://@HOST@@PORT@/locati%C3%abn", true),
            Arguments.of("../other%2fplace", "http://@HOST@@PORT@/other%2fplace", true),
            Arguments.of("http://somehost.com/other/location", "http://somehost.com/other/location", true)
        );
    }

    @ParameterizedTest
    @MethodSource("redirects")
    @Disabled // TODO
    public void testSendRedirect(String destination, String expected, boolean cookie)
        throws Exception
    {
        SessionHandler sessionHandler = addSessionHandler();
        _server.stop();
        _context.setContextPath("/path");
        _server.start();

        int[] ports = new int[]{8080, 80};
        String[] hosts = new String[]{null, "myhost", "192.168.0.1", "[0::1]"};
        for (int port : ports)
        {
            for (String host : hosts)
            {
                Response response = getResponse();
                Request request = response.getHttpChannel().getRequest();

                HttpURI.Mutable uri = HttpURI.build(request.getHttpURI(),
                    "/path/info;param;jsessionid=12345?query=0&more=1#target");
                uri.scheme("http");
                if (host != null)
                    uri.host(host).port(port);
                request.onDispatch(uri, "/path/info");

                request.setSessionManager(sessionHandler.getSessionManager());
                request.setRequestedSessionId("12345");
                request.setRequestedSessionIdFromCookie(cookie);

                Session session = sessionHandler.getSessionManager().getSession("12345");
                if (session == null)
                    request.getSession(true);
                else
                    request.setCoreSession(session);

                assertThat(request.getSession(false).getId(), is("12345"));

                response.sendRedirect(destination);

                String location = response.getHeader("Location");

                expected = expected
                    .replace("@HOST@", host == null ? request.getLocalAddr() : host)
                    .replace("@PORT@", host == null ? ":8888" : (port == 80 ? "" : (":" + port)));
                assertThat(host + ":" + port, location, equalTo(expected));
            }
        }
    }

    @Test
    public void testSendRedirectRelative()
        throws Exception
    {
        String[][] tests = {
            // No cookie
            {
                "http://myhost:8888/other/location;jsessionid=12345?name=value",
                "http://myhost:8888/other/location;jsessionid=12345?name=value"
            },
            {"/other/location;jsessionid=12345?name=value", "/other/location;jsessionid=12345?name=value"},
            {"./location;jsessionid=12345?name=value", "/path/location;jsessionid=12345?name=value"},

            // From cookie
            {"/other/location", "/other/location"},
            {"/other/l%20cation", "/other/l%20cation"},
            {"location", "/path/location"},
            {"./location", "/path/location"},
            {"../location", "/location"},
            {"/other/l%20cation", "/other/l%20cation"},
            {"l%20cation", "/path/l%20cation"},
            {"./l%20cation", "/path/l%20cation"},
            {"../l%20cation", "/l%20cation"},
            {"../locati%C3%abn", "/locati%C3%abn"},
            {"../other%2fplace", "/other%2fplace"},
            {"http://somehost.com/other/location", "http://somehost.com/other/location"},
        };

        int[] ports = new int[]{8080, 80};
        String[] hosts = new String[]{null, "myhost", "192.168.0.1", "[0::1]"};
        for (int port : ports)
        {
            for (String host : hosts)
            {
                for (int i = 0; i < tests.length; i++)
                {
                    // System.err.printf("%s %d %s%n",host,port,tests[i][0]);

                    Response response = getResponse();
                    Request request = response.getHttpChannel().getRequest();
                    request.getHttpChannel().getHttpConfiguration().setRelativeRedirectAllowed(true);

                    HttpURI.Mutable uri = HttpURI.build(request.getHttpURI());
                    uri.scheme("http");
                    if (host != null)
                        uri.authority(host, port);
                    uri.pathQuery("/path/info;param;jsessionid=12345?query=0&more=1#target");
                    request.onDispatch(uri, "/info");
                    request.setRequestedSessionId("12345");
                    request.setRequestedSessionIdFromCookie(i > 2);
                    SessionHandler handler = new SessionHandler();

                    NullSessionDataStore dataStore = new NullSessionDataStore();
                    DefaultSessionCache sessionCache = new DefaultSessionCache(handler.getSessionManager());
                    handler.getSessionManager().setSessionCache(sessionCache);
                    sessionCache.setSessionDataStore(dataStore);
                    DefaultSessionIdManager sessionIdManager = new DefaultSessionIdManager(_server);
                    sessionIdManager.setWorkerName(null);
                    handler.getSessionManager().setSessionIdManager(sessionIdManager);
                    request.setSessionManager(handler.getSessionManager());
                    handler.setCheckingRemoteSessionIdEncoding(false);

                    response.sendRedirect(tests[i][0]);

                    String location = response.getHeader("Location");

                    String expected = tests[i][1]
                        .replace("@HOST@", host == null ? request.getLocalAddr() : host)
                        .replace("@PORT@", host == null ? ":8888" : (port == 80 ? "" : (":" + port)));
                    assertEquals(expected, location, "test-" + i + " " + host + ":" + port);
                }
            }
        }
    }

    @Test
    public void testInvalidSendRedirect()
    {
        // Request is /path/info, so we need 3 ".." for an invalid redirect.
        Response response = getResponse();
        assertThrows(IllegalStateException.class, () -> response.sendRedirect("../../../invalid"));
    }

    @Test
    public void testSetBufferSizeAfterHavingWrittenContent() throws Exception
    {
        Response response = getResponse();
        response.setBufferSize(20 * 1024);
        response.getWriter().print("hello");

        assertThrows(IllegalStateException.class, () -> response.setBufferSize(21 * 1024));
    }

    @Test
    public void testZeroContent() throws Exception
    {
        Response response = getResponse();
        PrintWriter writer = response.getWriter();
        response.setContentLength(0);
        assertFalse(response.isCommitted());
        assertFalse(writer.checkError());
        writer.print("");
        // assertFalse(writer.checkError()); TODO check this
        assertTrue(response.isCommitted());
    }

    @Test
    public void testHead() throws Exception
    {
        Server server = new Server(0);
        ContextHandler contextHandler = new ContextHandler(server);
        try
        {
            contextHandler.setHandler(new AbstractHandler()
            {
                @Override
                public void handle(String target, Request baseRequest, HttpServletRequest request, HttpServletResponse response) throws IOException
                {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    PrintWriter w = response.getWriter();
                    w.flush();
                    w.println("Geht");
                    w.flush();
                    w.println("Doch");
                    w.flush();
                    ((Request)request).setHandled(true);
                }
            });
            server.start();

            try (Socket socket = new Socket("localhost", ((NetworkConnector)server.getConnectors()[0]).getLocalPort()))
            {
                socket.setSoTimeout(500000);
                socket.getOutputStream().write("HEAD / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes());
                socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n".getBytes());
                socket.getOutputStream().flush();

                LineNumberReader reader = new LineNumberReader(new InputStreamReader(socket.getInputStream()));
                String line = reader.readLine();
                assertThat(line, startsWith("HTTP/1.1 200 OK"));
                // look for blank line
                while (line != null && line.length() > 0)
                {
                    line = reader.readLine();
                }

                // Read the first line of the GET
                line = reader.readLine();
                assertThat(line, startsWith("HTTP/1.1 200 OK"));

                String last = null;
                while (line != null)
                {
                    last = line;
                    line = reader.readLine();
                }

                assertEquals("Doch", last);
            }
        }
        finally
        {
            server.stop();
        }
    }

    @Test
    public void testAddCookie()
    {
        Response response = getResponse();

        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);

        String set = response.getHttpFields().get("Set-Cookie");

        assertEquals("name=value; Path=/path; Domain=domain; Secure; HttpOnly", set);
    }

    @Test
    public void testAddCookieInInclude() throws Exception
    {
        Response response = getResponse();
        response.include();

        Cookie cookie = new Cookie("naughty", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);

        assertNull(response.getHttpFields().get("Set-Cookie"));
    }

    @Test
    public void testAddCookieSameSiteDefault() throws Exception
    {
        Response response = getResponse();
        _context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, HttpCookie.SameSite.STRICT);
        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);
        String set = response.getHttpFields().get("Set-Cookie");
        assertEquals("name=value; Path=/path; Domain=domain; Secure; HttpOnly; SameSite=Strict", set);

        response.getHttpFields().remove("Set-Cookie");

        //test bad default samesite value
        _context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "FooBar");

        assertThrows(IllegalStateException.class,
            () -> response.addCookie(cookie));
    }

    @Test
    public void testAddCookieComplianceRFC2965()
    {
        Response response = getResponse();
        response.getHttpChannel().getHttpConfiguration().setResponseCookieCompliance(CookieCompliance.RFC2965);

        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");

        response.addCookie(cookie);

        String set = response.getHttpFields().get("Set-Cookie");

        assertEquals("name=value;Version=1;Path=/path;Domain=domain;Secure;HttpOnly;Comment=comment", set);
    }

    /**
     * Testing behavior documented in Chrome bug
     * https://bugs.chromium.org/p/chromium/issues/detail?id=700618
     */
    @Test
    public void testAddCookieJavaxServletHttp() throws Exception
    {
        Response response = getResponse();

        Cookie cookie = new Cookie("foo", URLEncoder.encode("bar;baz", UTF_8.toString()));
        cookie.setPath("/secure");

        response.addCookie(cookie);

        String set = response.getHttpFields().get("Set-Cookie");

        assertEquals("foo=bar%3Bbaz; Path=/secure", set);
    }

    /**
     * Testing behavior documented in Chrome bug
     * https://bugs.chromium.org/p/chromium/issues/detail?id=700618
     */
    @Test
    public void testAddCookieJavaNet() throws Exception
    {
        java.net.HttpCookie cookie = new java.net.HttpCookie("foo", URLEncoder.encode("bar;baz", UTF_8.toString()));
        cookie.setPath("/secure");

        assertEquals("foo=\"bar%3Bbaz\";$Path=\"/secure\"", cookie.toString());
    }

    @Test
    public void testResetContent() throws Exception
    {
        Response response = getResponse();

        Cookie cookie = new Cookie("name", "value");
        cookie.setDomain("domain");
        cookie.setPath("/path");
        cookie.setSecure(true);
        cookie.setComment("comment__HTTP_ONLY__");
        response.addCookie(cookie);

        Cookie cookie2 = new Cookie("name2", "value2");
        cookie2.setDomain("domain");
        cookie2.setPath("/path");
        response.addCookie(cookie2);

        response.setContentType("some/type");
        response.setContentLength(3);
        response.setHeader(HttpHeader.EXPIRES, "never");

        response.setHeader("SomeHeader", "SomeValue");

        response.getOutputStream();

        // reset the content
        response.resetContent();

        // check content is nulled
        assertThat(response.getContentType(), nullValue());
        assertThat(response.getContentLength(), is(-1L));
        assertThat(response.getHeader(HttpHeader.EXPIRES.asString()), nullValue());
        response.getWriter();

        // check arbitrary header still set
        assertThat(response.getHeader("SomeHeader"), is("SomeValue"));

        // check cookies are still there
        Enumeration<String> set = response.getHttpFields().getValues("Set-Cookie");

        assertNotNull(set);
        ArrayList<String> list = Collections.list(set);
        assertThat(list, containsInAnyOrder(
            "name=value; Path=/path; Domain=domain; Secure; HttpOnly",
            "name2=value2; Path=/path; Domain=domain"
        ));

        //get rid of the cookies
        response.reset();

        set = response.getHttpFields().getValues("Set-Cookie");
        assertFalse(set.hasMoreElements());
    }

    @Test
    public void testReplaceHttpCookie()
    {
        Response response = getResponse();

        response.replaceCookie(new HttpCookie("Foo", "123456"));
        response.replaceCookie(new HttpCookie("Foo", "123456", "A", "/path"));
        response.replaceCookie(new HttpCookie("Foo", "123456", "B", "/path"));

        response.replaceCookie(new HttpCookie("Bar", "123456"));
        response.replaceCookie(new HttpCookie("Bar", "123456", null, "/left"));
        response.replaceCookie(new HttpCookie("Bar", "123456", null, "/right"));

        response.replaceCookie(new HttpCookie("Bar", "value", null, "/right"));
        response.replaceCookie(new HttpCookie("Bar", "value", null, "/left"));
        response.replaceCookie(new HttpCookie("Bar", "value"));

        response.replaceCookie(new HttpCookie("Foo", "value", "B", "/path"));
        response.replaceCookie(new HttpCookie("Foo", "value", "A", "/path"));
        response.replaceCookie(new HttpCookie("Foo", "value"));

        String[] expected = new String[]{
            "Foo=value",
            "Foo=value; Path=/path; Domain=A",
            "Foo=value; Path=/path; Domain=B",
            "Bar=value",
            "Bar=value; Path=/left",
            "Bar=value; Path=/right"
        };

        List<String> actual = Collections.list(response.getHttpFields().getValues("Set-Cookie"));
        assertThat("HttpCookie order", actual, hasItems(expected));
    }

    @Test
    public void testReplaceHttpCookieSameSite()
    {
        Response response = getResponse();
        _context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "LAX");
        //replace with no prior does an add
        response.replaceCookie(new HttpCookie("Foo", "123456"));
        String set = response.getHttpFields().get("Set-Cookie");
        assertEquals("Foo=123456; SameSite=Lax", set);
        //check replacement
        response.replaceCookie(new HttpCookie("Foo", "other"));
        set = response.getHttpFields().get("Set-Cookie");
        assertEquals("Foo=other; SameSite=Lax", set);
    }

    @Test
    public void testReplaceParsedHttpCookie()
    {
        Response response = getResponse();

        response.addHeader(HttpHeader.SET_COOKIE.asString(), "Foo=123456");
        response.replaceCookie(new HttpCookie("Foo", "value"));
        List<String> actual = Collections.list(response.getHttpFields().getValues("Set-Cookie"));
        assertThat(actual, hasItems("Foo=value"));

        response.setHeader(HttpHeader.SET_COOKIE, "Foo=123456; domain=Bah; Path=/path");
        response.replaceCookie(new HttpCookie("Foo", "other"));
        actual = Collections.list(response.getHttpFields().getValues("Set-Cookie"));
        assertThat(actual, hasItems("Foo=123456; domain=Bah; Path=/path", "Foo=other"));

        response.replaceCookie(new HttpCookie("Foo", "replaced", "Bah", "/path"));
        actual = Collections.list(response.getHttpFields().getValues("Set-Cookie"));
        assertThat(actual, hasItems("Foo=replaced; Path=/path; Domain=Bah", "Foo=other"));

        response.setHeader(HttpHeader.SET_COOKIE, "Foo=123456; domain=Bah; Expires=Thu, 01-Jan-1970 00:00:00 GMT; Max-Age=0; Secure; HttpOnly; Path=/path");
        response.replaceCookie(new HttpCookie("Foo", "replaced", "Bah", "/path"));
        actual = Collections.list(response.getHttpFields().getValues("Set-Cookie"));
        assertThat(actual, hasItems("Foo=replaced; Path=/path; Domain=Bah"));
    }

    @Test
    public void testReplaceParsedHttpCookieSiteDefault()
    {
        Response response = getResponse();
        _context.setAttribute(HttpCookie.SAME_SITE_DEFAULT_ATTRIBUTE, "LAX");

        response.addHeader(HttpHeader.SET_COOKIE.asString(), "Foo=123456");
        response.replaceCookie(new HttpCookie("Foo", "value"));
        String set = response.getHttpFields().get("Set-Cookie");
        assertEquals("Foo=value; SameSite=Lax", set);
    }

    @Test
    public void testFlushAfterFullContent() throws Exception
    {
        Response response = getResponse();
        byte[] data = new byte[]{(byte)0xCA, (byte)0xFE};
        ServletOutputStream output = response.getOutputStream();
        response.setContentLength(data.length);
        // Write the whole content
        output.write(data);
        // Must not throw
        output.flush();
    }

    @Test
    public void testEnsureConsumeAllOrNotPersistentHttp10()
    {
        Response response = getResponse(HttpVersion.HTTP_1_0);
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));

        response = getResponse(HttpVersion.HTTP_1_0);
        response.setHeader(HttpHeader.CONNECTION, "keep-alive");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));

        response = getResponse(HttpVersion.HTTP_1_0);
        response.setHeader(HttpHeader.CONNECTION, "before");
        response.getHttpFields().add(HttpHeader.CONNECTION, "foo, keep-alive, bar");
        response.getHttpFields().add(HttpHeader.CONNECTION, "after");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("before, foo, bar, after, close"));

        response = getResponse(HttpVersion.HTTP_1_0);
        response.setHeader(HttpHeader.CONNECTION, "close");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));
    }

    @Test
    public void testEnsureConsumeAllOrNotPersistentHttp11() throws Exception
    {
        Response response = getResponse(HttpVersion.HTTP_1_1);
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));

        response = getResponse(HttpVersion.HTTP_1_1);
        response.setHeader(HttpHeader.CONNECTION, "keep-alive");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));

        response = getResponse(HttpVersion.HTTP_1_1);
        response.setHeader(HttpHeader.CONNECTION, "close");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("close"));

        response = getResponse(HttpVersion.HTTP_1_1);
        response.setHeader(HttpHeader.CONNECTION, "before, close, after");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("before, close, after"));

        response = getResponse(HttpVersion.HTTP_1_1);
        response.setHeader(HttpHeader.CONNECTION, "before");
        response.getHttpFields().add(HttpHeader.CONNECTION, "middle, close");
        response.getHttpFields().add(HttpHeader.CONNECTION, "after");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("before, middle, close, after"));

        response = getResponse(HttpVersion.HTTP_1_1);
        response.setHeader(HttpHeader.CONNECTION, "one");
        response.getHttpFields().add(HttpHeader.CONNECTION, "two");
        response.getHttpFields().add(HttpHeader.CONNECTION, "three");
        response.getHttpChannel().ensureConsumeAllOrNotPersistent();
        assertThat(response.getHttpFields().get(HttpHeader.CONNECTION), is("one, two, three, close"));
    }

    private Response getResponse()
    {
        return getResponse(HttpVersion.HTTP_1_0);
    }

    private Response getResponse(HttpVersion version)
    {
        _channel.recycle();

        long now = System.currentTimeMillis();

        MetaData.Request reqMeta = new MetaData.Request("GET", HttpURI.from("http://myhost:8888/path/info"), version, HttpFields.EMPTY);

        org.eclipse.jetty.server.Request coreRequest = new MockRequest(reqMeta, now, _context.getServletContext().getCoreContext());
        org.eclipse.jetty.server.Response coreResponse = new MockResponse(coreRequest);

        _channel.onRequest(coreRequest);
        _channel.onProcess(coreResponse, Callback.NOOP);

        BufferUtil.clear(_content);
        return _channel.getResponse();
    }

    private class MockRequest extends Attributes.Mapped implements org.eclipse.jetty.server.Request
    {
        private final MetaData.Request _reqMeta;
        private final long _now;
        private final Context _context;

        public MockRequest(MetaData.Request reqMeta, long now)
        {
            this(reqMeta, now, null);
        }

        public MockRequest(MetaData.Request reqMeta, long now, Context context)
        {
            _reqMeta = reqMeta;
            _now = now;
            _context = context == null ? _server.getContext() : context;
        }

        @Override
        public String getId()
        {
            return "test";
        }

        @Override
        public Components getComponents()
        {
            return null;
        }

        @Override
        public ConnectionMetaData getConnectionMetaData()
        {
            return new MockConnectionMetaData();
        }

        @Override
        public String getMethod()
        {
            return _reqMeta.getMethod();
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _reqMeta.getURI();
        }

        @Override
        public Context getContext()
        {
            return _context;
        }

        @Override
        public String getPathInContext()
        {
            return _reqMeta.getURI().getCanonicalPath();
        }

        @Override
        public HttpFields getHeaders()
        {
            return _reqMeta.getFields();
        }

        @Override
        public HttpFields getTrailers()
        {
            return null;
        }

        @Override
        public long getTimeStamp()
        {
            return _now;
        }

        @Override
        public boolean isSecure()
        {
            return false;
        }

        @Override
        public long getLength()
        {
            return 0;
        }

        @Override
        public Content.Chunk read()
        {
            return Content.Chunk.EOF;
        }

        @Override
        public void demand(Runnable demandCallback)
        {
            demandCallback.run();
        }

        @Override
        public void fail(Throwable failure)
        {
        }

        @Override
        public boolean addErrorListener(Predicate<Throwable> onError)
        {
            return false;
        }

        @Override
        public TunnelSupport getTunnelSupport()
        {
            return null;
        }

        @Override
        public void addHttpStreamWrapper(Function<HttpStream, HttpStream.Wrapper> wrapper)
        {
        }
    }

    private class MockResponse implements org.eclipse.jetty.server.Response
    {
        private final org.eclipse.jetty.server.Request _coreRequest;
        int _status;
        final HttpFields.Mutable _headers;
        boolean _committed;
        boolean _last;

        public MockResponse(org.eclipse.jetty.server.Request coreRequest)
        {
            _coreRequest = coreRequest;
            _headers = HttpFields.build();
        }

        @Override
        public org.eclipse.jetty.server.Request getRequest()
        {
            return _coreRequest;
        }

        @Override
        public int getStatus()
        {
            return _status;
        }

        @Override
        public void setStatus(int code)
        {
            _status = code;
        }

        @Override
        public HttpFields.Mutable getHeaders()
        {
            return _headers;
        }

        @Override
        public Supplier<HttpFields> getTrailersSupplier()
        {
            return null;
        }

        @Override
        public void setTrailersSupplier(Supplier<HttpFields> trailers)
        {
        }

        @Override
        public void write(boolean last, ByteBuffer content, Callback callback)
        {
            if (content != null)
                BufferUtil.append(_content, content);
            _committed = true;
            _last |= last;
            callback.succeeded();
        }

        @Override
        public boolean isCommitted()
        {
            return _committed;
        }

        @Override
        public boolean isCompletedSuccessfully()
        {
            return _last;
        }

        @Override
        public void reset()
        {
        }

        @Override
        public CompletableFuture<Void> writeInterim(int status, HttpFields headers)
        {
                return null;
        }
    }
}
