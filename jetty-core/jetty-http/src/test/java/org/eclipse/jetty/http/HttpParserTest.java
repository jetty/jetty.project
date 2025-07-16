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
import static org.eclipse.jetty.http.HttpCompliance.Violation.MULTILINE_FIELD_VALUE;
import static org.eclipse.jetty.http.HttpCompliance.Violation.TRANSFER_ENCODING_WITH_CONTENT_LENGTH;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
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

    public static Stream<Arguments> httpMethodValues()
    {
        return List.of(HttpMethod.values())
            .stream()
            .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("httpMethodValues")
    public void testHttpMethod(HttpMethod m)
    {
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString().substring(0, 2))));
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString())));
        assertNull(HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + "FOO")));
        assertEquals(m, HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + " ")));
        assertEquals(m, HttpMethod.lookAheadGet(BufferUtil.toBuffer(m.asString() + " /foo/bar")));

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParseMockIP(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /mock/127.0.0.1 HTTP/1.1" + eoln + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/mock/127.0.0.1", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse0(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo HTTP/1.0" + eoln + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse1RFC2616(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/999", _uriOrStatus);
        assertEquals("HTTP/0.9", _versionOrReason);
        assertEquals(-1, _headers);
        assertThat(_complianceViolation, contains(HttpCompliance.Violation.HTTP_0_9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse1(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse2RFC2616(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222 " + eoln);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse2(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222 " + eoln);

        _versionOrReason = null;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse3(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0" + eoln + eoln, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/fo\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse4(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0" + eoln + eoln, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo?param=\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLineParse5(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /ctx/testLoginPage;jsessionid=123456789;other HTTP/1.0" + eoln + eoln, StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/ctx/testLoginPage;jsessionid=123456789;other", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLongURLParse(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/ HTTP/1.0" + eoln + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testAllowedLinePreamble(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(eoln + eoln + "GET / HTTP/1.0" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testDisallowedLinePreamble(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(eoln + " " + eoln + "GET / HTTP/1.0" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("Illegal character SPACE=' '", _bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testConnect(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1" + eoln + eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("CONNECT", _methodOrVersion);
        assertEquals("192.168.1.2:80", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testSimple(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testLowerCaseVersion(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.1" + eoln +
                "Host: localhost" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderCacheNearMiss(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Connection: closed" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderCacheSplitNearMiss(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Connection: close");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        assertFalse(parser.parseNext(buffer));

        buffer = BufferUtil.toBuffer(
            "d" + eoln +
                eoln);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testFoldedField2616(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Name: value" + eoln +
                " extra" + eoln +
                "Name2: " + eoln +
                "\tvalue2" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.nullValue());
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals(2, _headers);
        assertEquals("Name", _hdr[1]);
        assertEquals("value extra", _val[1]);
        assertEquals("Name2", _hdr[2]);
        assertEquals("value2", _val[2]);
        assertThat(_complianceViolation, contains(MULTILINE_FIELD_VALUE, MULTILINE_FIELD_VALUE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testFoldedField7230(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Name: value" + eoln +
                " extra" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Line Folding not supported"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testWhiteSpaceInName(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "N ame: value" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testWhiteSpaceAfterName(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Name : value" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    public static Stream<Arguments> headerFieldValues()
    {
        List<Arguments> cases = new ArrayList<>();

        for (HttpCompliance httpCompliance : List.of(HttpCompliance.RFC7230, HttpCompliance.RFC2616))
        {
            cases.add(Arguments.of(httpCompliance, "Value", "Value"));
            cases.add(Arguments.of(httpCompliance, " Value", "Value"));
            // HTAB is valid OWS when at the start or end of a value.
            // But is preserved as field-content within a value
            cases.add(Arguments.of(httpCompliance, "\tValue", "Value"));
            cases.add(Arguments.of(httpCompliance, "\t \tValue", "Value"));
            cases.add(Arguments.of(httpCompliance, "\t\t\tValue", "Value"));
            cases.add(Arguments.of(httpCompliance, "Value\t", "Value"));
            cases.add(Arguments.of(httpCompliance, "Va\tlue", "Va\tlue"));
            // SPACE is valid OWS when at the start or end of value.
            // But is preserved as field-content within a value
            cases.add(Arguments.of(httpCompliance, "   Value", "Value"));
            cases.add(Arguments.of(httpCompliance, "   Value   ", "Value"));
            cases.add(Arguments.of(httpCompliance, "V a l u e", "V a l u e"));
            cases.add(Arguments.of(httpCompliance, " V a l u e ", "V a l u e"));
            // Mix of WS
            cases.add(Arguments.of(httpCompliance, " \t V a l u e \t ", "V a l u e"));
            // Characters above US-ASCII (should be replaced)
            // OBS: This character 0x8B falls into the `obs-text` portion of ABNF, even though it's not visible, and is a control character.
            cases.add(Arguments.of(httpCompliance, ((char)0x8B) + "Value", ((char)0x8B) + "Value"));
            // ... like Unicode (should be sanitized replaced)
            cases.add(Arguments.of(httpCompliance, "(€)Euro", "(?)Euro"));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("headerFieldValues")
    public void testHeaderFieldValue(HttpCompliance compliance, String rawValue, String expectedValue)
    {
        String request =
            """
                GET / HTTP/1.1\r
                Host: localhost\r
                Name: %s\r
                Connection: close\r
                \r
                """.formatted(rawValue);

        ByteBuffer buffer = BufferUtil.toBuffer(request);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, compliance);
        _bad = null;
        parseAll(parser, buffer);

        assertThat(_bad, nullValue());

        assertEquals(2, _headers);
        assertEquals("Name", _hdr[1]);
        assertEquals(expectedValue, _val[1]);
    }

    public static Stream<Arguments> illegalFieldCharacter()
    {
        List<Arguments> cases = new ArrayList<>();

        for (HttpCompliance httpCompliance : List.of(HttpCompliance.RFC7230, HttpCompliance.RFC2616))
        {
            // BELL (control character) is replaced with '?'
            cases.add(Arguments.of(httpCompliance, ((char)0x07) + "Value", "Illegal character CNTL=0x7"));
            // DEL (control character) is replaced with '?'
            cases.add(Arguments.of(httpCompliance, ((char)0x7F) + "Value", "Illegal character CNTL=0x7f"));
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("illegalFieldCharacter")
    public void testIllegalFieldCharacter(HttpCompliance compliance, String rawValue, String expectedError)
    {
        String request =
            """
                GET / HTTP/1.1\r
                Host: localhost\r
                Name: %s\r
                Connection: close\r
                \r
                """.formatted(rawValue);

        ByteBuffer buffer = BufferUtil.toBuffer(request);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, compliance);
        _bad = null;
        parseAll(parser, buffer);

        assertThat(_bad, is(expectedError));
    }

    public static Stream<Arguments> whiteSpaceBeforeRequest()
    {
        List<Arguments> cases = new ArrayList<>();

        for (HttpCompliance httpCompliance : List.of(HttpCompliance.RFC7230, HttpCompliance.RFC2616))
        {
            cases.add(Arguments.of(httpCompliance, " ", "Illegal character SPACE"));
            cases.add(Arguments.of(httpCompliance, "\t", "Illegal character HTAB"));
            cases.add(Arguments.of(httpCompliance, "\n", null));
            cases.add(Arguments.of(httpCompliance, "\r", "Bad EOL"));
            cases.add(Arguments.of(httpCompliance, "\r\n", null));
            cases.add(Arguments.of(httpCompliance, "\r\n\r\n", null));
            cases.add(Arguments.of(httpCompliance, "\r\n \r\n", "Illegal character SPACE"));
            cases.add(Arguments.of(httpCompliance, "\r\n\t\r\n", "Illegal character HTAB"));
            cases.add(Arguments.of(httpCompliance, "\r\t\n", "Bad EOL"));
            cases.add(Arguments.of(httpCompliance, "\r\r\n", "Bad EOL"));
            cases.add(Arguments.of(httpCompliance, "\t\r\t\r\n", "Illegal character HTAB"));
            cases.add(Arguments.of(httpCompliance, " \t \r \t \n\n", "Illegal character SPACE"));
            cases.add(Arguments.of(httpCompliance, " \r \t \r\n\r\n\r\n", "Illegal character SPACE"));
        }

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("whiteSpaceBeforeRequest")
    public void testWhiteSpaceBeforeRequest(HttpCompliance compliance, String whitespace, String expected)
    {
        String request =
            """
                %sGET / HTTP/1.1\r
                Host: localhost\r
                Name: value\r
                Connection: close\r
                \r
                """.formatted(whitespace);

        ByteBuffer buffer = BufferUtil.toBuffer(request);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, compliance);
        _bad = null;
        parseAll(parser, buffer);

        if (expected == null)
            assertThat(_bad, is(nullValue()));
        else
            assertThat(_bad, containsString(expected));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoValue(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Name0:  " + eoln +
                "Name1:" + eoln +
                "Authorization:  " + eoln +
                "Authorization:" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler()
        {
            @Override
            public void badMessage(HttpException failure)
            {
                ((Throwable)failure).printStackTrace();
                super.badMessage(failure);
            }
        };
        HttpParser parser = new HttpParser(handler);
        parser.setHeaderCacheSize(1024);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testTrailingSpacesInHeaderNameNoCustom0(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No Content" + eoln +
                "Access-Control-Allow-Headers : Origin" + eoln +
                "Other: value" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No Content", _versionOrReason);
        assertThat(_bad, containsString("Illegal character "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoColon7230(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Name" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderParseDirect(String eoln)
    {
        ByteBuffer b0 = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Header1: value1" + eoln +
                "Header2:   value 2a  " + eoln +
                "Header3: 3" + eoln +
                "Header4:value4" + eoln +
                "Server5: notServer" + eoln +
                "HostHeader: notHost" + eoln +
                "Connection: close" + eoln +
                "Accept-Encoding: gzip, deflated" + eoln +
                "Accept: unknown" + eoln +
                eoln);
        ByteBuffer buffer = BufferUtil.allocateDirect(b0.capacity());
        int pos = BufferUtil.flipToFill(buffer);
        BufferUtil.put(b0, buffer);
        BufferUtil.flipToFlush(buffer, pos);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderParseCRLF(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Header1: value1" + eoln +
                "Header2:   value 2a  " + eoln +
                "Header3: 3" + eoln +
                "Header4:value4" + eoln +
                "Server5: notServer" + eoln +
                "HostHeader: notHost" + eoln +
                "Connection: close" + eoln +
                "Accept-Encoding: gzip, deflated" + eoln +
                "Accept: unknown" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderParse(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Header1: value1" + eoln +
                "Header2:   value 2a value 2b  " + eoln +
                "Header3: 3" + eoln +
                "Header4:value4" + eoln +
                "Server5: notServer" + eoln +
                "HostHeader: notHost" + eoln +
                "Connection: close" + eoln +
                "Accept-Encoding: gzip, deflated" + eoln +
                "Accept: unknown" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testQuoted(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Name0: \"value0\"\t" + eoln +
                "Name1: \"value\t1\"" + eoln +
                "Name2: \"value\t2A\",\"value,2B\"\t" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testEncodedHeader(String eoln)
    {
        ByteBuffer buffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(buffer);
        BufferUtil.put(BufferUtil.toBuffer("GET "), buffer);
        buffer.put("/foo/\u0690/".getBytes(StandardCharsets.UTF_8));
        BufferUtil.put(BufferUtil.toBuffer(" HTTP/1.0" + eoln), buffer);
        BufferUtil.put(BufferUtil.toBuffer("Header1: "), buffer);
        buffer.put("\u00e6 \u00e6".getBytes(StandardCharsets.ISO_8859_1));
        BufferUtil.put(BufferUtil.toBuffer("  " + eoln + "Header2: "), buffer);
        buffer.put((byte)-1);
        BufferUtil.put(BufferUtil.toBuffer(eoln + eoln), buffer);
        BufferUtil.flipToFlush(buffer, 0);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseBufferUpgradeFrom(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 Upgrade" + eoln +
                "Connection: upgrade" + eoln +
                "Content-Length: 0" + eoln +
                "Sec-WebSocket-Accept: 4GnyoUP4Sc1JD+2pCbNYAhFYVVA" + eoln +
                eoln +
                "FOOGRADE");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        while (!parser.isState(State.END))
        {
            parser.parseNext(buffer);
        }

        assertThat(BufferUtil.toUTF8String(buffer), Matchers.is("FOOGRADE"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadMethodEncoding(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "G\u00e6T / HTTP/1.0" + eoln + "Header0: value0" + eoln + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadVersionEncoding(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / H\u00e6P/1.0" + eoln + "Header0: value0" + eoln + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadHeaderEncoding(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "H\u00e6der0: value0" + eoln +
                "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {
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
    })
    public void testBadHeaderNames(String bad)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" + bad + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(bad, _bad, Matchers.notNullValue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderTab(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: localhost" + eoln +
                "Header: value\talternate" + eoln +
                "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("Host", _hdr[0]);
        assertEquals("localhost", _val[0]);
        assertEquals("Header", _hdr[1]);
        assertEquals("value\talternate", _val[1]);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCaseSensitiveMethod(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0" + eoln +
                "Host: localhost" + eoln +
                "Connection: close" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertThat(_complianceViolation, contains(CASE_INSENSITIVE_METHOD));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCaseSensitiveMethodLegacy(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0" + eoln +
                "Host: localhost" + eoln +
                "Connection: close" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("gEt", _methodOrVersion);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCaseInsensitiveHeader(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0" + eoln +
                "HOST: localhost" + eoln +
                "cOnNeCtIoN: ClOsE" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.RFC7230_LEGACY);
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

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCaseInSensitiveHeaderLegacy(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0" + eoln +
                "HOST: localhost" + eoln +
                "cOnNeCtIoN: ClOsE" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.LEGACY);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testSplitHeaderParse(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "XXXXSPLIT / HTTP/1.0" + eoln +
                "Host: localhost" + eoln +
                "Header1: value1" + eoln +
                "Header2:   value 2a  " + eoln +
                "Header3: 3" + eoln +
                "Header4:value4" + eoln +
                "Server5: notServer" + eoln +
                eoln +
                "ZZZZ");
        buffer.position(2);
        buffer.limit(buffer.capacity() - 2);
        buffer = buffer.slice();

        for (int i = 0; i < buffer.capacity() - 4; i++)
        {
            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkParse(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
            "Header1: value1" + eoln +
            "Transfer-Encoding: chunked" + eoln +
            eoln +
            "a;" + eoln +
            "0123456789" + eoln +
            "1a" + eoln +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
            "0" + eoln +
            eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadChunkLength(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "xx" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln +
                eoln
        );
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadTransferEncoding(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
            "Header1: value1" + eoln +
            "Transfer-Encoding: chunked, identity" + eoln +
            eoln +
            "a;" + eoln +
            "0123456789" + eoln +
            "1a" + eoln +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
            "0" + eoln +
            eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertThat(_bad, containsString("Bad Transfer-Encoding"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkParseTrailer(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "1a" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln +
                "Trailer: value" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkParseTrailers(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "1a" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln +
                "Trailer: value" + eoln +
                "Foo: bar" + eoln +
                eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkParseBadTrailer(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "1a" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln +
                "Trailer: value");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
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
        assertTrue(_early);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkParseNoTrailer(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "1a" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testEarlyEOF(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /uri HTTP/1.0" + eoln +
                "Content-Length: 20" + eoln +
                eoln +
                "0123456789");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/uri", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals("0123456789", _content);

        assertTrue(_early);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testChunkEarlyEOF(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln);
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parseAll(parser, buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testMultiParse(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0" + eoln +
                "Connection: Keep-Alive" + eoln +
                "Header1: value1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "a;" + eoln +
                "0123456789" + eoln +
                "1a" + eoln +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
                "0" + eoln +

                eoln +

                "POST /foo HTTP/1.0" + eoln +
                "Connection: Keep-Alive" + eoln +
                "Header2: value2" + eoln +
                "Content-Length: 0" + eoln +
                eoln +

                "PUT /doodle HTTP/1.0" + eoln +
                "Connection: close" + eoln +
                "Header3: value3" + eoln +
                "Content-Length: 10" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testMultiParseEarlyEOF(String eoln)
    {
        ByteBuffer buffer0 = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0" + eoln +
                "Connection: Keep-Alive" + eoln);

        ByteBuffer buffer1 = BufferUtil.toBuffer("Header1: value1" + eoln +
            "Transfer-Encoding: chunked" + eoln +
            eoln +
            "a;" + eoln +
            "0123456789" + eoln +
            "1a" + eoln +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ" + eoln +
            "0" + eoln +

            eoln +

            "POST /foo HTTP/1.0" + eoln +
            "Connection: Keep-Alive" + eoln +
            "Header2: value2" + eoln +
            "Content-Length: 0" + eoln +
            eoln +

            "PUT /doodle HTTP/1.0" + eoln +
            "Connection: close" + eoln + "Header3: value3" + eoln +
            "Content-Length: 10" + eoln +
            eoln +
            "0123456789" + eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer0);
        parser.atEOF();
        parser.parseNext(buffer1);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseParse0(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 Correct" + eoln +
                "Content-Length: 10" + eoln +
                "Content-Type: text/plain" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertEquals("Correct", _versionOrReason);
        assertEquals(10, _content.length());
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseParse1(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not-Modified" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("Not-Modified", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseParse2(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No-Content" + eoln +
                "Header: value" + eoln +
                eoln +

                "HTTP/1.1 200 Correct" + eoln +
                "Content-Length: 10" + eoln +
                "Content-Type: text/plain" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseParse3(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200" + eoln +
                "Content-Length: 10" + eoln +
                "Content-Type: text/plain" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseParse4(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 " + eoln +
                "Content-Length: 10" + eoln +
                "Content-Type: text/plain" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(_content.length(), 10);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseEOFContent(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 " + eoln +
                "Content-Type: text/plain" + eoln +
                eoln +
                "0123456789" + eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(10 + eoln.length(), _content.length());
        assertEquals("0123456789" + eoln, _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponse304WithContentLength(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 found" + eoln +
                "Content-Length: 10" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("found", _versionOrReason);
        assertNull(_content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponse101WithTransferEncoding(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 switching protocols" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testResponseReasonIso88591(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 302 déplacé temporairement" + eoln +
                "Content-Length: 0" + eoln +
                eoln, StandardCharsets.ISO_8859_1);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("302", _uriOrStatus);
        assertEquals("déplacé temporairement", _versionOrReason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testSeekEOF(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln +
                eoln + // extra CRLF ignored
                "HTTP/1.1 400 OK" + eoln);  // extra data causes close ??

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoURI(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoURI2(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET " + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("No URI", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testUnknownRequestVersion(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP" + eoln +
                "Host: localhost" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("Unknown Version", _bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testUnknownResponseVersion(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HPPT/7.7 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoStatus(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoStatus2(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 " + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("No Status", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadRequestVersion(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HPPT/7.7" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertNull(_methodOrVersion);
        assertEquals("Unknown Version", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());

        buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.01" + eoln +
                "Content-Length: 0" + eoln +
                "Connection: close" + eoln +
                eoln);

        handler = new Handler();
        parser = new HttpParser(handler);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadCR(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0" + eoln +
                "Content-Length: 0\r" +
                "Connection: close\r" +
                "\r");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
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
        parser = new HttpParser(handler);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testMultipleContentLengthWithLargerThenCorrectValue(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1" + eoln +
                "Content-Length: 2" + eoln +
                "Content-Length: 1" + eoln +
                "Connection: close" + eoln +
                eoln +
                "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testMultipleContentLengthWithCorrectThenLargerValue(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1" + eoln +
                "Content-Length: 1" + eoln +
                "Content-Length: 2" + eoln +
                "Connection: close" + eoln +
                eoln +
                "X");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("Multiple Content-Lengths", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testTransferEncodingChunkedThenContentLength(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1" + eoln +
                "Host: localhost" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                "Content-Length: 1" + eoln +
                eoln +
                "1" + eoln +
                "X" + eoln +
                "0" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertThat(_complianceViolation, contains(TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testContentLengthThenTransferEncodingChunked(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1" + eoln +
                "Host: localhost" + eoln +
                "Content-Length: 1" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "1" + eoln +
                "X" + eoln +
                "0" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC2616_LEGACY);
        parseAll(parser, buffer);

        assertEquals("POST", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals("X", _content);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);

        assertThat(_complianceViolation, contains(TRANSFER_ENCODING_WITH_CONTENT_LENGTH));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHost(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: host" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("host", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testUriHost11(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.1" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testUriHost10(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.0" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoHost(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testIPHost(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: 192.168.0.1" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testIPv6Host(String eoln)
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: [::1]" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(URIUtil.UNDEFINED_PORT, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testBadIPv6Host(String eoln)
    {
        try (StacklessLogging ignored = new StacklessLogging(HttpParser.class))
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.1" + eoln +
                    "Host: [::1" + eoln +
                    "Connection: close" + eoln +
                    eoln);

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);
            parser.parseNext(buffer);
            assertThat(_bad, containsString("Bad"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHostPort(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: myhost:8888" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testIPHostPort(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: 192.168.0.1:8888" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(8888, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testIPv6HostPort(String eoln)
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: [::1]:8888" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(8888, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testEmptyHostPort(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host:" + eoln +
                "Connection: close" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_host);
        assertNull(_bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testRequestMaxHeaderBytesURITooLong(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /long/nested/path/uri HTTP/1.1" + eoln +
                "Host: example.com" + eoln +
                "Connection: close" + eoln +
                eoln);

        int maxHeaderBytes = 5;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("414", _bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testRequestMaxHeaderBytesCumulative(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /nested/path/uri HTTP/1.1" + eoln +
                "Host: example.com" + eoln +
                "X-Large-Header: lorem-ipsum-dolor-sit" + eoln +
                "Connection: close" + eoln +
                eoln);

        int maxHeaderBytes = 64;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("431", _bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    @SuppressWarnings("ReferenceEquality")
    public void testCachedField(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: www.smh.com.au" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("www.smh.com.au", parser.getFieldCache().get("Host: www.smh.com.au").getValue());
        HttpField field = _fields.get(0);

        buffer.position(0);
        parseAll(parser, buffer);
        assertSame(field, _fields.get(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testParseRequest(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1" + eoln +
                "Host: localhost" + eoln +
                "Header1: value1" + eoln +
                "Connection: close" + eoln +
                "Accept-Encoding: gzip, deflated" + eoln +
                "Accept: unknown" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);

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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHTTP2Preface(String eoln)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "PRI * HTTP/2.0" + eoln +
                eoln +
                "SM" + eoln +
                eoln);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
        assertEquals("PRI", _methodOrVersion);
        assertEquals("*", _uriOrStatus);
        assertEquals("HTTP/2.0", _versionOrReason);
        assertEquals(-1, _headers);
        assertNull(_bad);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testForHTTP09HeaderCompleteTrueDoesNotEmitContentComplete(String eoln)
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
        ByteBuffer buffer = BufferUtil.toBuffer("GET /path" + eoln);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testForContentLengthZeroHeaderCompleteTrueDoesNotEmitContentComplete(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testForEmptyChunkedContentHeaderCompleteTrueDoesNotEmitContentComplete(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertFalse(_contentCompleted);
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_contentCompleted);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testForContentLengthZeroContentCompleteTrueDoesNotEmitMessageComplete(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testForEmptyChunkedContentContentCompleteTrueDoesNotEmitMessageComplete(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Transfer-Encoding: chunked" + eoln +
                eoln +
                "0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderAfterContentLengthZeroContentCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        String header = "Header: Foobar" + eoln;
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln +
                header);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testSmallContentLengthContentCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        String header = "Header: Foobar" + eoln;
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 1" + eoln +
                eoln +
                "0" +
                header);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHeaderAfterSmallContentLengthContentCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 1" + eoln +
                eoln +
                "0");
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testEOFContentContentCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                eoln +
                "0");
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testHEADRequestHeaderCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);
        parser.setHeadResponse(true);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testNoContentHeaderCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        // HTTP 304 does not have a body.
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCRLFAfterResponseHeaderCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 303 See Other" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCRLFAfterResponseContentCompleteTrue(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 303 See Other" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testCRLFAfterResponseMessageCompleteFalse(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln +
                eoln +
                eoln +
                "HTTP/1.1 303 See Other" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
    @ValueSource(strings = {"\r\n", "\n"})
    public void testSPAfterResponseMessageCompleteFalse(String eoln)
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
        HttpParser parser = new HttpParser(handler);

        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not Modified" + eoln +
                eoln +
                " " + // Single SP.
                "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        boolean handle = parser.parseNext(buffer);
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
            "HTTP/1.1 200 OK" + eoln +
                "Content-Length: 0" + eoln +
                eoln +
                " " + // Single SP.
                "HTTP/1.1 303 See Other" + eoln +
                "Content-Length: 0" + eoln +
                eoln);
        parser = new HttpParser(handler);
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
}
