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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpParser.State;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.URIUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.eclipse.jetty.http.HttpCompliance.Violation.CASE_INSENSITIVE_METHOD;
import static org.eclipse.jetty.http.HttpCompliance.Violation.CASE_SENSITIVE_FIELD_NAME;
import static org.eclipse.jetty.http.HttpCompliance.Violation.HTTP_0_9;
import static org.eclipse.jetty.http.HttpCompliance.Violation.LF_CHUNK_TERMINATION;
import static org.eclipse.jetty.http.HttpCompliance.Violation.LF_HEADER_TERMINATION;
import static org.eclipse.jetty.http.HttpCompliance.Violation.MULTILINE_FIELD_VALUE;
import static org.eclipse.jetty.http.HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

// @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
public class HttpParserTest
{
    /**
     * Parse until {@link State#END} state.
     * If the parser is already in the END state, then it is {@link HttpParser#reset()} and re-parsed.
     *
     * @param parser The parser to test
     * @param buffer the buffer to parse
     * @throws IllegalStateException If the buffers have already been partially parsed.
     */
    public static void parseAll(HttpParser parser, ByteBuffer buffer)
    {
        if (parser.isState(State.END))
            parser.reset();
        if (!parser.isState(State.START))
            throw new IllegalStateException("!START");

        // continue parsing
        int remaining = buffer.remaining();
        while (!parser.isState(State.END) && remaining > 0)
        {
            int wasRemaining = remaining;
            parser.parseNext(buffer);
            remaining = buffer.remaining();
            if (remaining == wasRemaining)
                break;
        }
    }

    @Test
    public void testHttpMethod()
    {
        for (HttpMethod m : HttpMethod.values())
        {
            assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString().substring(0, 2))));
            assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString())));
            assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + "FOO")));
            assertEquals(m, HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + " ")));
            assertEquals(m, HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + " /foo/bar")));
        }

        ByteBuffer b = BufferUtil.allocateDirect(128);
        BufferUtil.append(b, BufferUtil.toBuffer("GET"));
        assertNull(HttpMethod.lookAheadGet(b));

        BufferUtil.append(b, BufferUtil.toBuffer(" "));
        assertEquals(HttpMethod.GET, HttpMethod.lookAheadGet(b));
    }

    @ParameterizedTest
    @ValueSource(strings = {"GET", "POST", "VERSION-CONTROL"})
    public void httpMethodNameTest(String methodName)
    {
        HttpMethod method = HttpMethod.fromString(methodName);
        assertNotNull(method, "Method should have been found: " + methodName);
        assertEquals(methodName.toUpperCase(Locale.US), method.toString());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParseMockIP(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /mock/127.0.0.1 HTTP/1.0" + scenario.eol + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_bad);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/mock/127.0.0.1", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse0(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo HTTP/1.0" + scenario.eol + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse1Http9(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.with("test", HTTP_0_9));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/999", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);
        assertTrue(_complianceViolation.contains(HTTP_0_9));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse1(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.without("no 0.9", HTTP_0_9));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, scenario.isViolation() ? not(empty()) : empty());
    }

    @Test
    public void testLineParse2RFC2616()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222 \r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/222", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);
        assertThat(_complianceViolation, contains(HttpCompliance.Violation.HTTP_0_9));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse2(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222 " + scenario.eol);

        _versionOrReason = null;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.without("no 0.9", HTTP_0_9));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, scenario.isViolation() ? not(empty()) : empty());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse3(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0" + scenario.eol + scenario.eol, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("/fo\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse4(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0" + scenario.eol + scenario.eol, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo?param=\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLineParse5(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /ctx/testLoginPage;jsessionid=123456789;other HTTP/1.0" + scenario.eol + scenario.eol, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("GET", _methodOrVersion);
        assertEquals("/ctx/testLoginPage;jsessionid=123456789;other", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLongURLParse(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/ HTTP/1.0" + scenario.eol + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testAllowedLinePreamble(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(scenario.eol + scenario.eol + "GET / HTTP/1.0" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testDisallowedLinePreamble(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(scenario.eol + " " + scenario.eol + "GET / HTTP/1.0" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("Illegal character SPACE=' '", _bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testConnect(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1" + scenario.eol + scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("CONNECT", _methodOrVersion);
        assertEquals("192.168.1.2:80", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testSimple(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testLowerCaseVersion(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.1" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
    }

    @Test
    public void testHeaderCache()
    {
        assertThat(HttpParser.CACHE.getBest("Content-Type: text/plain\r\n").toString(), is("Content-Type: text/plain"));
        assertThat(HttpParser.CACHE.getBest("Content-Type: text/plain\n").toString(), is("Content-Type: text/plain"));
        assertThat(HttpParser.CACHE.getBest("content-type: text/plain\r\n").toString(), is("Content-Type: text/plain"));
        assertThat(HttpParser.CACHE.getBest("content-type: text/plain\n").toString(), is("Content-Type: text/plain"));

        assertThat(HttpParser.CACHE.getBest("Content-Type: unknown\r\n").toString(), is("Content-Type: \u0000"));
        assertThat(HttpParser.CACHE.getBest("Content-Type: unknown\n").toString(), is("Content-Type: \u0000"));
        assertThat(HttpParser.CACHE.getBest("content-type: unknown\r\n").toString(), is("Content-Type: \u0000"));
        assertThat(HttpParser.CACHE.getBest("content-type: unknown\n").toString(), is("Content-Type: \u0000"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderCacheNearMiss(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Connection: closed" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("closed", _val[1]);
        assertEquals(1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderCacheSplitNearMiss(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Connection: close");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        assertFalse(parser.parseNext(buffer));
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        buffer = BufferUtil.toBuffer(
            "d" + scenario.eol +
                scenario.eol);
        assertTrue(parser.parseNext(buffer));

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("closed", _val[1]);
        assertEquals(1, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testFoldedFieldMultiLine(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Name: value" + scenario.eol +
                " extra" + scenario.eol +
                "Name2: " + scenario.eol +
                "\tvalue2" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.with("test", MULTILINE_FIELD_VALUE));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertThat(_bad, Matchers.nullValue());
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals(2, _headers);
        assertEquals("Name", _hdr[1]);
        assertEquals("value extra", _val[1]);
        assertEquals("Name2", _hdr[2]);
        assertEquals("value2", _val[2]);
        assertTrue(_complianceViolation.contains(MULTILINE_FIELD_VALUE));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWhiteSpaceInName(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "N ame: value" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testWhiteSpaceAfterName(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Name : value" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @Test // TODO: Parameterize Test
    public void testWhiteSpaceBeforeRequest()
    {
        HttpCompliance[] compliances = new HttpCompliance[]
            {
                HttpCompliance.RFC7230, HttpCompliance.RFC2616
            };

        String[][] whitespaces = new String[][]
            {
                {" ", "Illegal character SPACE"},
                {"\t", "Illegal character HTAB"},
                {"\n", null},
                {"\r", "Bad EOL"},
                {"\r\n", null},
                {"\r\n\r\n", null},
                {"\r\n \r\n", "Illegal character SPACE"},
                {"\r\n\t\r\n", "Illegal character HTAB"},
                {"\r\t\n", "Bad EOL"},
                {"\r\r\n", "Bad EOL"},
                {"\t\r\t\r\n", "Illegal character HTAB"},
                {" \t \r \t \n\n", "Illegal character SPACE"},
                {" \r \t \r\n\r\n\r\n", "Illegal character SPACE"}
            };

        for (HttpCompliance compliance : compliances)
        {
            for (int j = 0; j < whitespaces.length; j++)
            {
                String request =
                    whitespaces[j][0] +
                        "GET / HTTP/1.1\r\n" +
                        "Host: localhost\r\n" +
                        "Name: value" + j + "\r\n" +
                        "Connection: close\r\n" +
                        "\r\n";

                ByteBuffer buffer = BufferUtil.toBuffer(request);
                HttpParser.RequestHandler handler = new Handler();
                HttpParser parser = new HttpParser(handler, 4096, compliance);
                _bad = null;
                parseAll(parser, buffer);

                String test = "whitespace.[" + compliance + "].[" + j + "]";
                String expected = whitespaces[j][1];
                if (expected == null)
                    assertThat(test, _bad, is(nullValue()));
                else
                    assertThat(test, _bad, containsString(expected));
            }
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoValue(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Name0:  " + scenario.eol +
                "Name1:" + scenario.eol +
                "Authorization:  " + scenario.eol +
                "Authorization:" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler()
        {
            @Override
            public void badMessage(HttpException failure)
            {
                ((Throwable)failure).printStackTrace();
                super.badMessage(failure);
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.setHeaderCacheSize(1024);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Name0", _hdr[1]);
        assertEquals("", _val[1]);
        assertEquals("Name1", _hdr[2]);
        assertEquals("", _val[2]);
        assertEquals("Authorization", _hdr[3]);
        assertEquals("", _val[3]);
        assertEquals("Authorization", _hdr[4]);
        assertEquals("", _val[4]);
        assertEquals(4, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testTrailingSpacesInHeaderNameNoCustom0(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No Content" + scenario.eol +
                "Access-Control-Allow-Headers : Origin" + scenario.eol +
                "Other: value" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No Content", _versionOrReason);
        assertThat(_bad, containsString("Illegal character "));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoColon7230(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Name" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, scenario.isViolation() ? not(empty()) : empty());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderParseDirect(Scenario scenario)
    {
        ByteBuffer b0 = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Header2:   value 2a  " + scenario.eol +
                "Header3: 3" + scenario.eol +
                "Header4:value4" + scenario.eol +
                "Server5: notServer" + scenario.eol +
                "HostHeader: notHost" + scenario.eol +
                "Connection: close" + scenario.eol +
                "Accept-Encoding: gzip, deflated" + scenario.eol +
                "Accept: unknown" + scenario.eol +
                scenario.eol);
        ByteBuffer buffer = BufferUtil.allocateDirect(b0.capacity());
        int pos = BufferUtil.flipToFill(buffer);
        BufferUtil.put(b0, buffer);
        BufferUtil.flipToFlush(buffer, pos);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderParseCRLF(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Header2:   value 2a  " + scenario.eol +
                "Header3: 3" + scenario.eol +
                "Header4:value4" + scenario.eol +
                "Server5: notServer" + scenario.eol +
                "HostHeader: notHost" + scenario.eol +
                "Connection: close" + scenario.eol +
                "Accept-Encoding: gzip, deflated" + scenario.eol +
                "Accept: unknown" + scenario.eol +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderParse(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Header2:   value 2a value 2b  " + scenario.eol +
                "Header3: 3" + scenario.eol +
                "Header4:value4" + scenario.eol +
                "Server5: notServer" + scenario.eol +
                "HostHeader: notHost" + scenario.eol +
                "Connection: close" + scenario.eol +
                "Accept-Encoding: gzip, deflated" + scenario.eol +
                "Accept: unknown" + scenario.eol +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("Header2", _hdr[2]);
        assertEquals("value 2a value 2b", _val[2]);
        assertEquals("Header3", _hdr[3]);
        assertEquals("3", _val[3]);
        assertEquals("Header4", _hdr[4]);
        assertEquals("value4", _val[4]);
        assertEquals("Server5", _hdr[5]);
        assertEquals("notServer", _val[5]);
        assertEquals("HostHeader", _hdr[6]);
        assertEquals("notHost", _val[6]);
        assertEquals("Connection", _hdr[7]);
        assertEquals("close", _val[7]);
        assertEquals("Accept-Encoding", _hdr[8]);
        assertEquals("gzip, deflated", _val[8]);
        assertEquals("Accept", _hdr[9]);
        assertEquals("unknown", _val[9]);
        assertEquals(9, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testQuoted(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Name0: \"value0\"\t" + scenario.eol +
                "Name1: \"value\t1\"" + scenario.eol +
                "Name2: \"value\t2A\",\"value,2B\"\t" + scenario.eol +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Name0", _hdr[0]);
        assertEquals("\"value0\"", _val[0]);
        assertEquals("Name1", _hdr[1]);
        assertEquals("\"value\t1\"", _val[1]);
        assertEquals("Name2", _hdr[2]);
        assertEquals("\"value\t2A\",\"value,2B\"", _val[2]);
        assertEquals(2, _headers);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEncodedHeader(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(buffer);
        BufferUtil.put(BufferUtil.toBuffer("GET "), buffer);
        buffer.put("/foo/\u0690/".getBytes(StandardCharsets.UTF_8));
        BufferUtil.put(BufferUtil.toBuffer(" HTTP/1.0" + scenario.eol), buffer);
        BufferUtil.put(BufferUtil.toBuffer("Header1: "), buffer);
        buffer.put("\u00e6 \u00e6".getBytes(StandardCharsets.ISO_8859_1));
        BufferUtil.put(BufferUtil.toBuffer("  " + scenario.eol + "Header2: "), buffer);
        buffer.put((byte)-1);
        BufferUtil.put(BufferUtil.toBuffer(scenario.eol + scenario.eol), buffer);
        BufferUtil.flipToFlush(buffer, 0);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/foo/\u0690/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Header1", _hdr[0]);
        assertEquals("\u00e6 \u00e6", _val[0]);
        assertEquals("Header2", _hdr[1]);
        assertEquals("" + (char)255, _val[1]);
        assertEquals(1, _headers);
        assertNull(_bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseBufferUpgradeFrom(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 Upgrade" + scenario.eol +
                "Connection: upgrade" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Sec-WebSocket-Accept: 4GnyoUP4Sc1JD+2pCbNYAhFYVVA" + scenario.eol +
                scenario.eol +
                "FOOGRADE");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        while (!parser.isState(State.END))
        {
            parser.parseNext(buffer);
            if (scenario.expectBad())
            {
                assertThat(_bad, containsString("LF line terminator"));
                return;
            }
        }

        assertThat(BufferUtil.toUTF8String(buffer), Matchers.is("FOOGRADE"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadMethodEncoding(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "G\u00e6T / HTTP/1.0" + scenario.eol + "Header0: value0" + scenario.eol + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        assertThat(_bad, containsString("Illegal character"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadVersionEncoding(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / H\u00e6P/1.0" + scenario.eol + "Header0: value0" + scenario.eol + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadHeaderEncoding(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "H\u00e6der0: value0" + scenario.eol +
                "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test // TODO: Parameterize Test
    public void testBadHeaderNames()
    {
        String[] bad = new String[]
            {
                "Foo\\Bar: value\r\n",
                "Foo@Bar: value\r\n",
                "Foo,Bar: value\r\n",
                "Foo}Bar: value\r\n",
                "Foo{Bar: value\r\n",
                "Foo=Bar: value\r\n",
                "Foo>Bar: value\r\n",
                "Foo<Bar: value\r\n",
                "Foo)Bar: value\r\n",
                "Foo(Bar: value\r\n",
                "Foo?Bar: value\r\n",
                "Foo\"Bar: value\r\n",
                "Foo/Bar: value\r\n",
                "Foo]Bar: value\r\n",
                "Foo[Bar: value\r\n"
            };

        for (String s : bad)
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.0\r\n" + s + "\r\n");

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);
            parseAll(parser, buffer);
            assertThat(s, _bad, Matchers.notNullValue());
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderTab(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header: value\talternate" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header", _hdr[1]);
        assertEquals("value\talternate", _val[1]);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCaseSensitiveMethod(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, scenario.compliance.with("test", CASE_INSENSITIVE_METHOD));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertTrue(_complianceViolation.contains(CASE_INSENSITIVE_METHOD));
    }

    @Test
    public void testCaseSensitiveMethodLegacy()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("""
            gEt / http/1.0\r
            Host: localhost\r
            Connection: close\r
            \r
            """);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("gEt", _methodOrVersion);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testCaseInsensitiveHeader()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("""
            GET / http/1.0\r
            HOST: localhost\r
            cOnNeCtIoN: ClOsE\r
            \r
            """);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[1]);
        assertEquals("close", _val[1]);
        assertEquals(1, _headers);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testCaseInSensitiveHeaderLegacy()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("""
            GET / http/1.0\r
            HOST: localhost\r
            cOnNeCtIoN: ClOsE\r
            \r
            """);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.LEGACY);
        parser.setHeaderCacheCaseSensitive(true);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("HOST", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("cOnNeCtIoN", _hdr[1]);
        assertEquals("ClOsE", _val[1]);
        assertEquals(1, _headers);
        assertThat(_complianceViolation, contains(CASE_SENSITIVE_FIELD_NAME, CASE_SENSITIVE_FIELD_NAME));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testSplitHeaderParse(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "XXXXSPLIT / HTTP/1.0" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Header2:   value 2a  " + scenario.eol +
                "Header3: 3" + scenario.eol +
                "Header4:value4" + scenario.eol +
                "Server5: notServer" + scenario.eol +
                scenario.eol +
                "ZZZZ");
        buffer.position(2);
        buffer.limit(buffer.capacity() - 2);
        buffer = buffer.slice();

        for (int i = 0; i < buffer.capacity() - 4; i++)
        {
            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler, scenario.compliance);

            buffer.limit(2 + i);
            buffer.position(2);

            if (!parser.parseNext(buffer))
            {
                // consumed all
                assertEquals(0, buffer.remaining());

                // parse the rest
                buffer.limit(buffer.capacity() - 2);
                parser.parseNext(buffer);
            }

            if (scenario.expectBad())
            {
                assertThat(_bad, containsString("LF line terminator"));
                return;
            }

            assertEquals("SPLIT", _methodOrVersion);
            assertEquals("/", _uriOrStatus);
            assertEquals("HTTP/1.0", _versionOrReason);
            assertEquals("Host", _hdr[0]);
            assertEquals("localhost", _val[0]);
            assertEquals("Header1", _hdr[1]);
            assertEquals("value1", _val[1]);
            assertEquals("Header2", _hdr[2]);
            assertEquals("value 2a", _val[2]);
            assertEquals("Header3", _hdr[3]);
            assertEquals("3", _val[3]);
            assertEquals("Header4", _hdr[4]);
            assertEquals("value4", _val[4]);
            assertEquals("Server5", _hdr[5]);
            assertEquals("notServer", _val[5]);
            assertEquals(5, _headers);
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkParse(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
            "Header1: value1" + scenario.eol +
            "Transfer-Encoding: chunked" + scenario.eol +
            scenario.eol +
            "a;" + scenario.eolChunk +
            "0123456789" + scenario.eolChunk +
            "1a" + scenario.eolChunk +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
            "0" + scenario.eolChunk +
            scenario.eolChunk);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertFalse(_early);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadChunkLength(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "xx" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                scenario.eol
        );
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertTrue(_headerCompleted);
        assertTrue(_early);
        assertFalse(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadTransferEncoding(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
            "Header1: value1" + scenario.eol +
            "Transfer-Encoding: chunked, identity" + scenario.eol +
            scenario.eol +
            "a;" + scenario.eolChunk +
            "0123456789" + scenario.eolChunk +
            "1a" + scenario.eolChunk +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
            "0" + scenario.eolChunk +
            scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertThat(_bad, containsString("Bad Transfer-Encoding"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkParseTrailer(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "1a" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                "Trailer: value" + scenario.eolChunk +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
        assertEquals(1, _trailers.size());
        HttpField trailer1 = _trailers.get(0);
        assertEquals("Trailer", trailer1.getName());
        assertEquals("value", trailer1.getValue());

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkParseTrailers(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "1a" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                "Trailer: value" + scenario.eol +
                "Foo: bar" + scenario.eol +
                scenario.eol);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(0, _headers);
        assertEquals("Transfer-Encoding", _hdr[0]);
        assertEquals("chunked", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);
        assertEquals(2, _trailers.size());
        HttpField trailer1 = _trailers.get(0);
        assertEquals("Trailer", trailer1.getName());
        assertEquals("value", trailer1.getValue());
        HttpField trailer2 = _trailers.get(1);
        assertEquals("Foo", trailer2.getName());
        assertEquals("bar", trailer2.getValue());

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkParseBadTrailer(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "1a" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                "Trailer: value");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_early);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkParseNoTrailer(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "1a" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testStartEOF()
    {
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);

        assertTrue(_early);
        assertNull(_bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEarlyEOF(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /uri HTTP/1.0" + scenario.eol +
                "Content-Length: 20" + scenario.eol +
                scenario.eol +
                "0123456789");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.atEOF();
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/uri", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("0123456789", _content);

        assertTrue(_early);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testChunkEarlyEOF(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.atEOF();
        parseAll(parser, buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(1, _headers);
        assertEquals("Header1", _hdr[0]);
        assertEquals("value1", _val[0]);
        assertEquals("0123456789", _content);

        assertTrue(_early);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testMultiParse(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0" + scenario.eol +
                "Connection: Keep-Alive" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "a;" + scenario.eolChunk +
                "0123456789" + scenario.eolChunk +
                "1a" + scenario.eolChunk +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
                "0" + scenario.eolChunk +

                scenario.eol +

                "POST /foo HTTP/1.0" + scenario.eol +
                "Connection: Keep-Alive" + scenario.eol +
                "Header2: value2" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +

                "PUT /doodle HTTP/1.0" + scenario.eol +
                "Connection: close" + scenario.eol +
                "Header3: value3" + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertNull(_content);

        parser.reset();
        init();
        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
        assertEquals("0123456789", _content);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testMultiParseEarlyEOF(Scenario scenario)
    {
        ByteBuffer buffer0 = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0" + scenario.eol +
                "Connection: Keep-Alive" + scenario.eol);

        ByteBuffer buffer1 = BufferUtil.toBuffer("Header1: value1" + scenario.eol +
            "Transfer-Encoding: chunked" + scenario.eol +
            scenario.eol +
            "a;" + scenario.eolChunk +
            "0123456789" + scenario.eolChunk +
            "1a" + scenario.eolChunk +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + scenario.eolChunk +
            "0" + scenario.eolChunk +

            scenario.eol +

            "POST /foo HTTP/1.0" + scenario.eol +
            "Connection: Keep-Alive" + scenario.eol +
            "Header2: value2" + scenario.eol +
            "Content-Length: 0" + scenario.eol +
            scenario.eol +

            "PUT /doodle HTTP/1.0" + scenario.eol +
            "Connection: close" + scenario.eol + "Header3: value3" + scenario.eol +
            "Content-Length: 10" + scenario.eol +
            scenario.eol +
            "0123456789" + scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer0);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        parser.atEOF();
        parser.parseNext(buffer1);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }
        assertEquals("GET", _methodOrVersion);
        assertEquals("/mp", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header1", _hdr[1]);
        assertEquals("value1", _val[1]);
        assertEquals("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ", _content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header2", _hdr[1]);
        assertEquals("value2", _val[1]);
        assertNull(_content);

        parser.reset();
        init();
        parser.parseNext(buffer1);
        assertEquals("PUT", _methodOrVersion);
        assertEquals("/doodle", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(2, _headers);
        assertEquals("Header3", _hdr[1]);
        assertEquals("value3", _val[1]);
        assertEquals("0123456789", _content);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseParse0(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 Correct" + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                "Content-Type: text/plain" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(10, _content.length());
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseParse1(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not-Modified" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("Not-Modified", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseParse2(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No-Content" + scenario.eol +
                "Header: value" + scenario.eol +
                scenario.eol +

                "HTTP/1.1 200 Correct" + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                "Content-Type: text/plain" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No-Content", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.reset();
        init();

        parser.parseNext(buffer);
        parser.atEOF();
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseParse3(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200" + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                "Content-Type: text/plain" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseParse4(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 " + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                "Content-Type: text/plain" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseEOFContent(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 " + scenario.eol +
                "Content-Type: text/plain" + scenario.eol +
                scenario.eol +
                "0123456789" + scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.atEOF();
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(10 + scenario.eol.length(), _content.length());
        assertEquals("0123456789" + scenario.eol, _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponse304WithContentLength(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 found" + scenario.eol +
                "Content-Length: 10" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("found", _versionOrReason);
        assertNull(_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponse101WithTransferEncoding(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 switching protocols" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("101", _uriOrStatus);
        assertEquals("switching protocols", _versionOrReason);
        assertNull(_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xxx", "0", "00", "50", "050", "0200", "1000", "2xx"})
    public void testBadResponseStatus(String status)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("""
                HTTP/1.1 %s %s\r
                Content-Length:0\r
                \r
                """.formatted(status, status), StandardCharsets.ISO_8859_1);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertThat(_bad, is("Bad status"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testResponseReasonIso88591(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 302 déplacé temporairement" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol, StandardCharsets.ISO_8859_1);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("302", _uriOrStatus);
        assertEquals("déplacé temporairement", _versionOrReason);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testSeekEOF(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol +
                scenario.eol + // extra CRLF ignored
                "HTTP/1.1 400 OK" + scenario.eol);  // extra data causes close ??

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("OK", _versionOrReason);
        assertNull(_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        parser.close();
        parser.reset();
        parser.parseNext(buffer);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoURI(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoURI2(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET " + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testUnknownRequestVersion(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP" + scenario.eol +
                "Host: localhost" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("Unknown Version", _bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testUnknownResponseVersion(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HPPT/7.7 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);

        assertNull(_methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoStatus(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoStatus2(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 " + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadRequestVersion(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HPPT/7.7" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());

        buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.01" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        handler = new Handler();
        parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadCR(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + scenario.eol +
                "Content-Length: 0\r" +
                "Connection: close\r" +
                "\r");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("Bad EOL", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());

        buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r" +
                "Content-Length: 0\r" +
                "Connection: close\r" +
                "\r");

        handler = new Handler();
        parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        assertEquals("Bad EOL", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "abc",
        "1.5",
        "9999999999999999999999999999999999999999999999",
        "-10",
        "+10",
        "1.0",
        "1,0",
        "10,",
        "10A"
    })
    public void testBadContentLengths(String contentLength)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /test HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: " + contentLength + "\r\n" +
                "\r\n" +
                "1234567890\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, notNullValue());
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " 10 ",
        "10 ",
        " 10",
        "\t10",
        "\t10\t",
        "10\t",
        " \t \t \t 10"
    })
    public void testContentLengthWithOWS(String contentLength)
    {
        String rawRequest = """
            GET /test HTTP/1.1\r
            Host: localhost\r
            Content-Length: @LEN@\r
            \r
            1234567890
            """.replace("@LEN@", contentLength);
        ByteBuffer buffer = BufferUtil.toBuffer(rawRequest);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/test", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);

        assertEquals(_content.length(), 10);
        assertEquals(parser.getContentLength(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " chunked ",
        "chunked ",
        " chunked",
        "\tchunked",
        "\tchunked\t",
        "chunked\t",
        " \t \t \t chunked"
    })
    public void testTransferEncodingWithOWS(String transferEncoding)
    {
        String rawRequest = """
            GET /test HTTP/1.1\r
            Host: localhost\r
            Transfer-Encoding: @TE@\r
            \r
            1\r
            X\r
            0\r
            \r
            """.replace("@TE@", transferEncoding);
        ByteBuffer buffer = BufferUtil.toBuffer(rawRequest);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/test", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Transfer-Encoding", _hdr[1]);
        assertEquals("chunked", _val[1]);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        " testhost ",
        "testhost ",
        " testhost",
        "\ttesthost",
        "\ttesthost\t",
        "testhost\t",
        " \t \t \t testhost"
    })
    public void testHostWithOWS(String host)
    {
        String rawRequest = """
            GET /test HTTP/1.1\r
            Host: @HOST@\r
            \r
            """.replace("@HOST@", host);
        ByteBuffer buffer = BufferUtil.toBuffer(rawRequest);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/test", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("testhost", _val[0]);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testMultipleContentLengthWithLargerThenCorrectValue(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1" + scenario.eol +
                "Content-Length: 2" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol +
                "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testMultipleContentLengthWithCorrectThenLargerValue(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                "Content-Length: 2" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol +
                "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testTransferEncodingChunkedThenContentLength(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                scenario.eol +
                "1" + scenario.eolChunk +
                "X" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.with("test", TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertTrue(_complianceViolation.contains(TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testContentLengthThenTransferEncodingChunked(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "1" + scenario.eolChunk +
                "X" + scenario.eolChunk +
                "0" + scenario.eolChunk +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance.with("test", TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertTrue(_complianceViolation.contains(TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHost(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: host" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("host", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testUriHost11(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.1" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("No Host", _bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testUriHost10(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.0" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoHost(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("No Host", _bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testIPHost(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: 192.168.0.1" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("192.168.0.1", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testIPv6Host(Scenario scenario)
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: [::1]" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("[::1]", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testBadIPv6Host(Scenario scenario)
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpParser.class))
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.1" + scenario.eol +
                    "Host: [::1" + scenario.eol +
                    "Connection: close" + scenario.eol +
                    scenario.eol);

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler, scenario.compliance);
            parser.parseNext(buffer);
            if (scenario.expectBad())
            {
                assertThat(_bad, containsString("LF line terminator"));
                return;
            }
            assertThat(_bad, containsString("Bad"));
        }
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHostPort(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: myhost:8888" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("myhost", _host);
        assertEquals(8888, _port);
    }

    public static Stream<String> badHostHeaderSource()
    {
        return Stream.of(
            ":80", // no host, port only
            "host:", // no port
            "127.0.0.1:", // no port
            "[0::0::0::0::1", // no IP literal ending bracket
            "0::0::0::0::1]", // no IP literal starting bracket
            "[0::0::0::0::1]:", // no port
            "[0::0::0::1]", // not valid to Java (InetAddress, InetSocketAddress, or URI) : "Expected hex digits or IPv4 address"
            "[0::0::0::1]:80", // not valid to Java (InetAddress, InetSocketAddress, or URI) : "Expected hex digits or IPv4 address"
            "0:1:2:3:4:5:6", // not valid to Java (InetAddress, InetSocketAddress, or URI) : "IPv6 address too short"
            "host:xxx", // invalid port
            "127.0.0.1:xxx", // host + invalid port
            "[0::0::0::0::1]:xxx", // ipv6 + invalid port
            "host:-80", // host + invalid port
            "127.0.0.1:-80", // ipv4 + invalid port
            "[0::0::0::0::1]:-80", // ipv6 + invalid port
            "127.0.0.1:65536", // ipv4 + port value too high
            "a b c d", // whitespace in reg-name
            "a\to\tz", // tabs in reg-name
            "hosta, hostb, hostc", // space sin reg-name
            "[ab:cd:ef:gh:ij:kl:mn]", // invalid ipv6 address
            // Examples of bad Host header values (usually client bugs that shouldn't allow them)
            "Group - Machine", // spaces
            "<calculated when request is sent>",
            "[link](https://example.org/)",
            "example.org/zed", // has slash
            // common hacking attempts, seen as values on the `Host:` request header
            "| ping 127.0.0.1 -n 10",
            "%uf%80%ff%xx%uffff",
            "[${jndi${:-:}ldap${:-:}]", // log4j hacking
            "[${jndi:ldap://example.org:59377/nessus}]", // log4j hacking
            "${ip}", // variation of log4j hack
            "' *; host xyz.hacking.pro; '",
            "'/**/OR/**/1/**/=/**/1",
            "AND (SELECT 1 FROM(SELECT COUNT(*),CONCAT('x',(SELECT (ELT(1=1,1))),'x',FLOOR(RAND(0)*2))x FROM INFORMATION_SCHEMA.CHARACTER_SETS GROUP BY x)a)"
        );
    }

    @ParameterizedTest
    @MethodSource("badHostHeaderSource")
    public void testBadHostReject(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\n" +
                "Host: " + hostline + "\n" +
                "Connection: close\n" +
                "\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertThat(_bad, startsWith("Bad "));
    }

    @ParameterizedTest
    @MethodSource("badHostHeaderSource")
    public void testBadHostAllow(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\n" +
                "Host: " + hostline + "\n" +
                "Connection: close\n" +
                "\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpCompliance httpCompliance = HttpCompliance.from("RFC7230,UNSAFE_HOST_HEADER");
        HttpParser parser = new HttpParser(handler, httpCompliance);
        parser.parseNext(buffer);
        assertNull(_bad);
        assertNotNull(_host);
    }

    public static Stream<Arguments> duplicateHostHeadersSource()
    {
        return Stream.of(
            // different values
            Arguments.of("Host: hosta\nHost: hostb\nHost: hostc"),
            // same values
            Arguments.of("Host: foo\nHost: foo"),
            // separated by another header
            Arguments.of("Host: bar\nX-Zed: zed\nHost: bar")
        );
    }

    @ParameterizedTest
    @MethodSource("duplicateHostHeadersSource")
    public void testDuplicateHostReject(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\n" +
                hostline + "\n" +
                "Connection: close\n" +
                "\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertThat(_bad, startsWith("Duplicate Host Header"));
    }

    @ParameterizedTest
    @MethodSource("duplicateHostHeadersSource")
    public void testDuplicateHostAllow(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\n" +
                hostline + "\n" +
                "Connection: close\n" +
                "\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpCompliance httpCompliance = HttpCompliance.from("RFC7230,DUPLICATE_HOST_HEADERS");
        HttpParser parser = new HttpParser(handler, httpCompliance);
        parser.parseNext(buffer);
        assertNull(_bad);
        assertNotNull(_host);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Host: whatever.com:123",
        "Host: myhost.com",
        "Host: ::", // fake, no value, IPv6 (allowed)
        "Host: a-b-c-d",
        "Host: hosta,hostb,hostc", // commas are allowed
        "Host: [fde3:827b:ea49:0:893:8016:e3ac:9778]:444", // IPv6 with port
        "Host: [fde3:827b:ea49:0:893:8016:e3ac:9778]", // IPv6 without port
    })
    public void testGoodHost(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\n" +
                hostline + "\n" +
                "Connection: close\n" +
                "\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testIPHostPort(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: 192.168.0.1:8888" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("192.168.0.1", _host);
        assertEquals(8888, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testIPv6HostPort(Scenario scenario)
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: [::1]:8888" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("[::1]", _host);
        assertEquals(8888, _port);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEmptyHostPort(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host:" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertNull(_host);
        assertNull(_bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestMaxHeaderBytesURITooLong(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /long/nested/path/uri HTTP/1.1" + scenario.eol +
                "Host: example.com" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        int maxHeaderBytes = 5;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("414", _bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testRequestMaxHeaderBytesCumulative(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /nested/path/uri HTTP/1.1" + scenario.eol +
                "Host: example.com" + scenario.eol +
                "X-Large-Header: lorem-ipsum-dolor-sit" + scenario.eol +
                "Connection: close" + scenario.eol +
                scenario.eol);

        int maxHeaderBytes = 64;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("431", _bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    @SuppressWarnings("ReferenceEquality")
    public void testInsensitiveCachedField(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Content-Type: text/plain;Charset=UTF-8" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        HttpField field = _fields.get(0);
        assertThat(field.getValue(), is("text/plain;charset=utf-8"));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    @SuppressWarnings("ReferenceEquality")
    public void testDynamicCachedField(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: www.smh.com.au" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertEquals("www.smh.com.au", parser.getFieldCache().get("Host: www.smh.com.au").getValue());
        HttpField field = _fields.get(0);

        buffer.position(0);
        parseAll(parser, buffer);
        assertSame(field, _fields.get(0));
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testParseRequest(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + scenario.eol +
                "Host: localhost" + scenario.eol +
                "Header1: value1" + scenario.eol +
                "Connection: close" + scenario.eol +
                "Accept-Encoding: gzip, deflated" + scenario.eol +
                "Accept: unknown" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Connection", _hdr[2]);
        assertEquals("close", _val[2]);
        assertEquals("Accept-Encoding", _hdr[3]);
        assertEquals("gzip, deflated", _val[3]);
        assertEquals("Accept", _hdr[4]);
        assertEquals("unknown", _val[4]);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHTTP2Preface(Scenario scenario)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "PRI * HTTP/2.0" + scenario.eol +
                scenario.eol +
                "SM" + scenario.eol +
                scenario.eol);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parseAll(parser, buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("PRI", _methodOrVersion);
        assertEquals("*", _uriOrStatus);
        assertEquals("HTTP/2.0", _versionOrReason);
        assertEquals(-1, _headers);
        assertNull(_bad);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testForHTTP09HeaderCompleteTrueDoesNotEmitContentComplete(Scenario scenario)
    {
        HttpParser.RequestHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }
        };

        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        ByteBuffer buffer = BufferUtil.toBuffer("GET /path" + scenario.eol);
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/path", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testForContentLengthZeroHeaderCompleteTrueDoesNotEmitContentComplete(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testForEmptyChunkedContentHeaderCompleteTrueDoesNotEmitContentComplete(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "0" + scenario.eolChunk +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }
        assertTrue(handle);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testForContentLengthZeroContentCompleteTrueDoesNotEmitMessageComplete(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);

        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }

        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testForEmptyChunkedContentContentCompleteTrueDoesNotEmitMessageComplete(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Transfer-Encoding: chunked" + scenario.eol +
                scenario.eol +
                "0" + scenario.eolChunk +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        if (scenario.expectEarly())
        {
            assertTrue(_early);
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderAfterContentLengthZeroContentCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        String header = "Header: Foobar" + scenario.eol;
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +
                header);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals(header, BufferUtil.toString(buffer));
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals(header, BufferUtil.toString(buffer));
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testSmallContentLengthContentCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        String header = "Header: Foobar" + scenario.eol;
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                scenario.eol +
                "0" +
                header);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals(header, BufferUtil.toString(buffer));
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals(header, BufferUtil.toString(buffer));
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHeaderAfterSmallContentLengthContentCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 1" + scenario.eol +
                scenario.eol +
                "0");
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testEOFContentContentCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                scenario.eol +
                "0");
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertFalse(handle);
        assertFalse(buffer.hasRemaining());
        assertEquals("0", _content);
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        parser.atEOF();

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testHEADRequestHeaderCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }

            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);
        parser.setHeadResponse(true);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testNoContentHeaderCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }

            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        // HTTP 304 does not have a body.
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCRLFAfterResponseHeaderCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean headerComplete()
            {
                super.headerComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 303 See Other" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("304", _uriOrStatus);
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("200", _uriOrStatus);
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertEquals("303", _uriOrStatus);
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCRLFAfterResponseContentCompleteTrue(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean contentComplete()
            {
                super.contentComplete();
                return true;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 303 See Other" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("304", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("200", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertEquals("303", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCRLFAfterResponseMessageCompleteFalse(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean messageComplete()
            {
                super.messageComplete();
                return false;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +
                scenario.eol +
                scenario.eol +
                "HTTP/1.1 303 See Other" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertFalse(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("304", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertFalse(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("200", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertFalse(handle);
        assertFalse(buffer.hasRemaining());
        assertEquals("303", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testSPAfterResponseMessageCompleteFalse(Scenario scenario)
    {
        HttpParser.ResponseHandler handler = new Handler()
        {
            @Override
            public boolean messageComplete()
            {
                super.messageComplete();
                return false;
            }
        };
        HttpParser parser = new HttpParser(handler, scenario.compliance);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + scenario.eol +
                scenario.eol +
                " " + // Single SP.
                "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        boolean handle = parser.parseNext(buffer);
        if (scenario.expectBad())
        {
            assertThat(_bad, containsString("LF line terminator"));
            return;
        }
        assertFalse(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("304", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertFalse(handle);
        assertFalse(buffer.hasRemaining());
        assertNotNull(_bad);

        buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol +
                " " + // Single SP.
                "HTTP/1.1 303 See Other" + scenario.eol +
                "Content-Length: 0" + scenario.eol +
                scenario.eol);
        parser = new HttpParser(handler, scenario.compliance);
        handle = parser.parseNext(buffer);
        assertFalse(handle);
        assertTrue(buffer.hasRemaining());
        assertEquals("200", _uriOrStatus);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);

        // Parse next response.
        parser.reset();
        init();
        handle = parser.parseNext(buffer);
        assertFalse(handle);
        assertFalse(buffer.hasRemaining());
        assertNotNull(_bad);
    }

    @BeforeEach
    public void init()
    {
        _bad = null;
        _content = null;
        _methodOrVersion = null;
        _uriOrStatus = null;
        _versionOrReason = null;
        _hdr = null;
        _val = null;
        _headers = 0;
        _headerCompleted = false;
        _contentCompleted = false;
        _messageCompleted = false;
        _complianceViolation.clear();
    }

    private String _host;
    private int _port;
    private String _bad;
    private String _content;
    private String _methodOrVersion;
    private String _uriOrStatus;
    private String _versionOrReason;
    private final List<HttpField> _fields = new ArrayList<>();
    private final List<HttpField> _trailers = new ArrayList<>();
    private String[] _hdr;
    private String[] _val;
    private int _headers;
    private boolean _early;
    private boolean _headerCompleted;
    private boolean _contentCompleted;
    private boolean _messageCompleted;
    private final List<ComplianceViolation> _complianceViolation = new ArrayList<>();

    private class Handler implements HttpParser.RequestHandler, HttpParser.ResponseHandler
    {
        @Override
        public boolean content(ByteBuffer ref)
        {
            if (_content == null)
                _content = "";
            String c = BufferUtil.toString(ref, StandardCharsets.UTF_8);
            _content = _content + c;
            ref.position(ref.limit());
            return false;
        }

        @Override
        public void startRequest(String method, String uri, HttpVersion version)
        {
            _fields.clear();
            _trailers.clear();
            _headers = -1;
            _hdr = new String[10];
            _val = new String[10];
            _methodOrVersion = method;
            _uriOrStatus = uri;
            _versionOrReason = version == null ? null : version.asString();
            _messageCompleted = false;
            _headerCompleted = false;
            _early = false;
        }

        @Override
        public void parsedHeader(HttpField field)
        {
            _fields.add(field);
            _hdr[++_headers] = field.getName();
            _val[_headers] = field.getValue();

            if (field instanceof HostPortHttpField hpfield)
            {
                _host = hpfield.getHost();
                _port = hpfield.getPort();
            }
        }

        @Override
        public boolean headerComplete()
        {
            _content = null;
            _headerCompleted = true;
            return false;
        }

        @Override
        public void parsedTrailer(HttpField field)
        {
            _trailers.add(field);
        }

        @Override
        public boolean contentComplete()
        {
            _contentCompleted = true;
            return false;
        }

        @Override
        public boolean messageComplete()
        {
            _messageCompleted = true;
            return true;
        }

        @Override
        public void badMessage(HttpException failure)
        {
            String reason = failure.getReason();
            if (_bad == null)
                _bad = reason == null ? String.valueOf(failure.getCode()) : reason;
        }

        @Override
        public void startResponse(HttpVersion version, int status, String reason)
        {
            _fields.clear();
            _trailers.clear();
            _methodOrVersion = version.asString();
            _uriOrStatus = Integer.toString(status);
            _versionOrReason = reason;
            _headers = -1;
            _hdr = new String[10];
            _val = new String[10];
            _messageCompleted = false;
            _headerCompleted = false;
        }

        @Override
        public void earlyEOF()
        {
            _early = true;
        }

        @Override
        public void onViolation(ComplianceViolation.Event event)
        {
            _complianceViolation.add(event.violation());
        }
    }

    @Test
    public void testHttpHeaderValueParseCsv()
    {
        final List<HttpHeaderValue> list = new ArrayList<>();
        final List<String> unknowns = new ArrayList<>();

        assertTrue(HttpHeaderValue.parseCsvIndex("", list::add, unknowns::add));
        assertThat(list, empty());
        assertThat(unknowns, empty());

        assertTrue(HttpHeaderValue.parseCsvIndex(" ", list::add, unknowns::add));
        assertThat(list, empty());
        assertThat(unknowns, empty());

        assertTrue(HttpHeaderValue.parseCsvIndex(",", list::add, unknowns::add));
        assertThat(list, empty());
        assertThat(unknowns, empty());

        assertTrue(HttpHeaderValue.parseCsvIndex(",,", list::add, unknowns::add));
        assertThat(list, empty());
        assertThat(unknowns, empty());

        assertTrue(HttpHeaderValue.parseCsvIndex(" , , ", list::add, unknowns::add));
        assertThat(list, empty());
        assertThat(unknowns, empty());

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex("close", list::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE));

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex(" close ", list::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE));

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex(",close,", list::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE));

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex(" , close , ", list::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE));

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex(" close,GZIP, chunked    , Keep-Alive   ", list::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE, HttpHeaderValue.GZIP, HttpHeaderValue.CHUNKED, HttpHeaderValue.KEEP_ALIVE));

        list.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex(" close,GZIP, chunked    , Keep-Alive   ", t ->
        {
            if (t.toString().startsWith("c"))
                list.add(t);
            return true;
        }));
        assertThat(list, contains(HttpHeaderValue.CLOSE, HttpHeaderValue.CHUNKED));

        list.clear();
        assertFalse(HttpHeaderValue.parseCsvIndex(" close,GZIP, chunked    , Keep-Alive   ", t ->
        {
            if (HttpHeaderValue.CHUNKED == t)
                return false;
            list.add(t);
            return true;
        }));
        assertThat(list, contains(HttpHeaderValue.CLOSE, HttpHeaderValue.GZIP));

        list.clear();
        unknowns.clear();
        assertTrue(HttpHeaderValue.parseCsvIndex("closed,close, unknown , bytes", list::add, unknowns::add));
        assertThat(list, contains(HttpHeaderValue.CLOSE, HttpHeaderValue.BYTES));
        assertThat(unknowns, contains("closed", "unknown"));

        list.clear();
        unknowns.clear();
        assertFalse(HttpHeaderValue.parseCsvIndex("close, unknown , bytes", list::add, s -> false));
        assertThat(list, contains(HttpHeaderValue.CLOSE));
        assertThat(unknowns, empty());
    }

    public static Stream<Scenario> scenarios()
    {
        List<Scenario> scenarios = new ArrayList<>();
        for (HttpCompliance compliance : new HttpCompliance[] {HttpCompliance.STRICT, HttpCompliance.RFC9110, HttpCompliance.RFC7230, HttpCompliance.RFC2616, HttpCompliance.RFC7230_LEGACY, HttpCompliance.RFC2616_LEGACY})
            for (String eol : new String[] {"\r\n", "\n"})
                for (String eolChunk : new String[] {"\r\n", "\n"})
                    scenarios.add(new Scenario(eol, eolChunk, compliance));
        return scenarios.stream();
    }

    public record Scenario(String eol, String eolChunk, HttpCompliance compliance)
    {
        public boolean isViolation()
        {
            return !eol.equals("\r\n");
        }

        public boolean isChunkViolation()
        {
            return !eolChunk.equals("\r\n");
        }

        public boolean expectBad()
        {
            return isViolation() && !compliance.allows(LF_HEADER_TERMINATION);
        }

        public boolean expectEarly()
        {
            return isChunkViolation() && !compliance.allows(LF_CHUNK_TERMINATION);
        }

        public String toString()
        {
            return "%s[eol=%s, eolChunk=%s, c=%s".formatted(this.getClass().getSimpleName(), isViolation() ? "LF" : "CRLF", isChunkViolation() ? "LF" : "CRLF", compliance.getName());
        }
    }
}
