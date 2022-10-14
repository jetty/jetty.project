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

package org.eclipse.jetty.http;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.http.HttpParser.State;
import org.eclipse.jetty.logging.StacklessLogging;
import org.eclipse.jetty.toolchain.test.Net;
import org.eclipse.jetty.util.BufferUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import static org.hamcrest.Matchers.nullValue;
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

            assertNull(HttpMethod.lookAheadGet(m.asString().substring(0, 2).getBytes(), 0, 2));
            assertNull(HttpMethod.lookAheadGet(m.asString().getBytes(), 0, m.asString().length()));
            assertNull(HttpMethod.lookAheadGet((m.asString() + "FOO").getBytes(), 0, m.asString().length() + 3));
            assertEquals(m, HttpMethod.lookAheadGet(("\n" + m.asString() + " ").getBytes(), 1, m.asString().length() + 2));
            assertEquals(m, HttpMethod.lookAheadGet(("\n" + m.asString() + " /foo").getBytes(), 1, m.asString().length() + 6));
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

    @Test
    public void testLineParseMockIP()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /mock/127.0.0.1 HTTP/1.1\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/mock/127.0.0.1", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse0()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo HTTP/1.0\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse1RFC2616()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999\r\n");

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

    @Test
    public void testLineParse1()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("GET /999\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testLineParse2RFC2616()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222  \r\n");

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

    @Test
    public void testLineParse2()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /222  \r\n");

        _versionOrReason = null;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("HTTP/0.9 not supported", _bad);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testLineParse3()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /fo\u0690 HTTP/1.0\r\n" + "\r\n", StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/fo\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLineParse4()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /foo?param=\u0690 HTTP/1.0\r\n" + "\r\n", StandardCharsets.UTF_8);

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/foo?param=\u0690", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testLongURLParse()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("POST /123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/ HTTP/1.0\r\n" + "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("POST", _methodOrVersion);
        assertEquals("/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/123456789abcdef/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testAllowedLinePreamble()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("\r\n\r\nGET / HTTP/1.0\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("/", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testDisallowedLinePreamble()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("\r\n \r\nGET / HTTP/1.0\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("Illegal character SPACE=' '", _bad);
    }

    @Test
    public void testConnect()
    {
        ByteBuffer buffer = BufferUtil.toBuffer("CONNECT 192.168.1.2:80 HTTP/1.1\r\n" + "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("CONNECT", _methodOrVersion);
        assertEquals("192.168.1.2:80", _uriOrStatus);
        assertEquals("HTTP/1.1", _versionOrReason);
        assertEquals(-1, _headers);
    }

    @Test
    public void testSimple()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testFoldedField2616()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name: value\r\n" +
                " extra\r\n" +
                "Name2: \r\n" +
                "\tvalue2\r\n" +
                "\r\n");

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

    @Test
    public void testFoldedField7230()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name: value\r\n" +
                " extra\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Line Folding not supported"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testWhiteSpaceInName()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "N ame: value\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, 4096, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);

        assertThat(_bad, Matchers.notNullValue());
        assertThat(_bad, containsString("Illegal character"));
    }

    @Test
    public void testWhiteSpaceAfterName()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name : value\r\n" +
                "\r\n");

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

    @Test
    public void testNoValue()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name0: \r\n" +
                "Name1:\r\n" +
                "\r\n");

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
        assertEquals("Name0", _hdr[1]);
        assertEquals("", _val[1]);
        assertEquals("Name1", _hdr[2]);
        assertEquals("", _val[2]);
        assertEquals(2, _headers);
    }

    @Test
    public void testTrailingSpacesInHeaderNameNoCustom0()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No Content\r\n" +
                "Access-Control-Allow-Headers : Origin\r\n" +
                "Other: value\r\n" +
                "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("204", _uriOrStatus);
        assertEquals("No Content", _versionOrReason);
        assertThat(_bad, containsString("Illegal character "));
    }

    @Test
    public void testNoColon7230()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Name\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertThat(_bad, containsString("Illegal character"));
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testHeaderParseDirect()
    {
        ByteBuffer b0 = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "HostHeader: notHost\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");
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

    @Test
    public void testHeaderParseCRLF()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "HostHeader: notHost\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");
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

    @Test
    public void testHeaderParseLF()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\n" +
                "Host: localhost\n" +
                "Header1: value1\n" +
                "Header2:   value 2a value 2b  \n" +
                "Header3: 3\n" +
                "Header4:value4\n" +
                "Server5: notServer\n" +
                "HostHeader: notHost\n" +
                "Connection: close\n" +
                "Accept-Encoding: gzip, deflated\n" +
                "Accept: unknown\n" +
                "\n");
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

    @Test
    public void testQuoted()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\n" +
                "Name0: \"value0\"\t\n" +
                "Name1: \"value\t1\"\n" +
                "Name2: \"value\t2A\",\"value,2B\"\t\n" +
                "\n");
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

    @Test
    public void testEncodedHeader()
    {
        ByteBuffer buffer = BufferUtil.allocate(4096);
        BufferUtil.flipToFill(buffer);
        BufferUtil.put(BufferUtil.toBuffer("GET "), buffer);
        buffer.put("/foo/\u0690/".getBytes(StandardCharsets.UTF_8));
        BufferUtil.put(BufferUtil.toBuffer(" HTTP/1.0\r\n"), buffer);
        BufferUtil.put(BufferUtil.toBuffer("Header1: "), buffer);
        buffer.put("\u00e6 \u00e6".getBytes(StandardCharsets.ISO_8859_1));
        BufferUtil.put(BufferUtil.toBuffer("  \r\nHeader2: "), buffer);
        buffer.put((byte)-1);
        BufferUtil.put(BufferUtil.toBuffer("\r\n\r\n"), buffer);
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

    @Test
    public void testResponseBufferUpgradeFrom()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 Upgrade\r\n" +
                "Connection: upgrade\r\n" +
                "Content-Length: 0\r\n" +
                "Sec-WebSocket-Accept: 4GnyoUP4Sc1JD+2pCbNYAhFYVVA\r\n" +
                "\r\n" +
                "FOOGRADE");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        while (!parser.isState(State.END))
        {
            parser.parseNext(buffer);
        }

        assertThat(BufferUtil.toUTF8String(buffer), Matchers.is("FOOGRADE"));
    }

    @Test
    public void testBadMethodEncoding()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "G\u00e6T / HTTP/1.0\r\nHeader0: value0\r\n\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test
    public void testBadVersionEncoding()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / H\u00e6P/1.0\r\nHeader0: value0\r\n\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertThat(_bad, Matchers.notNullValue());
    }

    @Test
    public void testBadHeaderEncoding()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "H\u00e6der0: value0\r\n" +
                "\n\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
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

    @Test
    public void testHeaderTab()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Header: value\talternate\r\n" +
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

    @Test
    public void testCaseSensitiveMethod()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.RFC7230_LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("GET", _methodOrVersion);
        assertThat(_complianceViolation, contains(CASE_INSENSITIVE_METHOD));
    }

    @Test
    public void testCaseSensitiveMethodLegacy()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "gEt / http/1.0\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, -1, HttpCompliance.LEGACY);
        parseAll(parser, buffer);
        assertNull(_bad);
        assertEquals("gEt", _methodOrVersion);
        assertThat(_complianceViolation, Matchers.empty());
    }

    @Test
    public void testCaseInsensitiveHeader()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0\r\n" +
                "HOST: localhost\r\n" +
                "cOnNeCtIoN: ClOsE\r\n" +
                "\r\n");
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

    @Test
    public void testCaseInSensitiveHeaderLegacy()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / http/1.0\r\n" +
                "HOST: localhost\r\n" +
                "cOnNeCtIoN: ClOsE\r\n" +
                "\r\n");
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

    @Test
    public void testSplitHeaderParse()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "XXXXSPLIT / HTTP/1.0\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Header2:   value 2a  \r\n" +
                "Header3: 3\r\n" +
                "Header4:value4\r\n" +
                "Server5: notServer\r\n" +
                "\r\nZZZZ");
        buffer.position(2);
        buffer.limit(buffer.capacity() - 2);
        buffer = buffer.slice();

        for (int i = 0; i < buffer.capacity() - 4; i++)
        {
            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);

            buffer.position(2);
            buffer.limit(2 + i);

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

    @Test
    public void testChunkParse()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +
                "\r\n");
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

    @Test
    public void testBadChunkParse()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked, identity\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +
                "\r\n");
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);

        assertEquals("GET", _methodOrVersion);
        assertEquals("/chunk", _uriOrStatus);
        assertEquals("HTTP/1.0", _versionOrReason);
        assertThat(_bad, containsString("Bad Transfer-Encoding"));
    }

    @Test
    public void testChunkParseTrailer()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +
                "Trailer: value\r\n" +
                "\r\n");
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

    @Test
    public void testChunkParseTrailers()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +
                "Trailer: value\r\n" +
                "Foo: bar\r\n" +
                "\r\n");
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

    @Test
    public void testChunkParseBadTrailer()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +
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

    @Test
    public void testChunkParseNoTrailer()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n");
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

    @Test
    public void testEarlyEOF()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /uri HTTP/1.0\r\n" +
                "Content-Length: 20\r\n" +
                "\r\n" +
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

    @Test
    public void testChunkEarlyEOF()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /chunk HTTP/1.0\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n");
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

    @Test
    public void testMultiParse()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Header1: value1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "a;\r\n" +
                "0123456789\r\n" +
                "1a\r\n" +
                "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
                "0\r\n" +

                "\r\n" +

                "POST /foo HTTP/1.0\r\n" +
                "Connection: Keep-Alive\r\n" +
                "Header2: value2\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +

                "PUT /doodle HTTP/1.0\r\n" +
                "Connection: close\r\n" +
                "Header3: value3\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "0123456789\r\n");

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

    @Test
    public void testMultiParseEarlyEOF()
    {
        ByteBuffer buffer0 = BufferUtil.toBuffer(
            "GET /mp HTTP/1.0\r\n" +
                "Connection: Keep-Alive\r\n");

        ByteBuffer buffer1 = BufferUtil.toBuffer("Header1: value1\r\n" +
            "Transfer-Encoding: chunked\r\n" +
            "\r\n" +
            "a;\r\n" +
            "0123456789\r\n" +
            "1a\r\n" +
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ\r\n" +
            "0\r\n" +

            "\r\n" +

            "POST /foo HTTP/1.0\r\n" +
            "Connection: Keep-Alive\r\n" +
            "Header2: value2\r\n" +
            "Content-Length: 0\r\n" +
            "\r\n" +

            "PUT /doodle HTTP/1.0\r\n" +
            "Connection: close\r\n" + "Header3: value3\r\n" +
            "Content-Length: 10\r\n" +
            "\r\n" +
            "0123456789\r\n");

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

    @Test
    public void testResponseParse0()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 Correct\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789\r\n");

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

    @Test
    public void testResponseParse1()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 Not-Modified\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("304", _uriOrStatus);
        assertEquals("Not-Modified", _versionOrReason);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponseParse2()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 204 No-Content\r\n" +
                "Header: value\r\n" +
                "\r\n" +

                "HTTP/1.1 200 Correct\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789\r\n");

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

    @Test
    public void testResponseParse3()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200\r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789\r\n");

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

    @Test
    public void testResponseParse4()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 \r\n" +
                "Content-Length: 10\r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789\r\n");

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

    @Test
    public void testResponseEOFContent()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 \r\n" +
                "Content-Type: text/plain\r\n" +
                "\r\n" +
                "0123456789\r\n");

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.atEOF();
        parser.parseNext(buffer);

        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("200", _uriOrStatus);
        assertNull(_versionOrReason);
        assertEquals(12, _content.length());
        assertEquals("0123456789\r\n", _content);
        assertTrue(_headerCompleted);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testResponse304WithContentLength()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 304 found\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n");

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

    @Test
    public void testResponse101WithTransferEncoding()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 101 switching protocols\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n");

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

    @Test
    public void testResponseReasonIso88591()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 302 déplacé temporairement\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n", StandardCharsets.ISO_8859_1);

        HttpParser.ResponseHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("HTTP/1.1", _methodOrVersion);
        assertEquals("302", _uriOrStatus);
        assertEquals("déplacé temporairement", _versionOrReason);
    }

    @Test
    public void testSeekEOF()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
                "\r\n" + // extra CRLF ignored
                "HTTP/1.1 400 OK\r\n");  // extra data causes close ??

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

    @Test
    public void testNoURI()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testNoURI2()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET \r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testUnknownReponseVersion()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HPPT/7.7 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testNoStatus()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testNoStatus2()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 \r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testBadRequestVersion()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HPPT/7.7\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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
            "GET / HTTP/1.01\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n" +
                "\r\n");

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

    @Test
    public void testBadCR()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
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

    @Test
    public void testBadContentLength0()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Content-Length: abc\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadContentLength1()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Content-Length: 9999999999999999999999999999999999999999999999\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testBadContentLength2()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.0\r\n" +
                "Content-Length: 1.5\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);

        parser.parseNext(buffer);
        assertEquals("GET", _methodOrVersion);
        assertEquals("Invalid Content-Length Value", _bad);
        assertFalse(buffer.hasRemaining());
        assertEquals(HttpParser.State.CLOSE, parser.getState());
        parser.atEOF();
        parser.parseNext(BufferUtil.EMPTY_BUFFER);
        assertEquals(HttpParser.State.CLOSED, parser.getState());
    }

    @Test
    public void testMultipleContentLengthWithLargerThenCorrectValue()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1\r\n" +
                "Content-Length: 2\r\n" +
                "Content-Length: 1\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
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

    @Test
    public void testMultipleContentLengthWithCorrectThenLargerValue()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST / HTTP/1.1\r\n" +
                "Content-Length: 1\r\n" +
                "Content-Length: 2\r\n" +
                "Connection: close\r\n" +
                "\r\n" +
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

    @Test
    public void testTransferEncodingChunkedThenContentLength()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n" +
                "1\r\n" +
                "X\r\n" +
                "0\r\n" +
                "\r\n");

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

    @Test
    public void testContentLengthThenTransferEncodingChunked()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "POST /chunk HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Content-Length: 1\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "1\r\n" +
                "X\r\n" +
                "0\r\n" +
                "\r\n");

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

    @Test
    public void testHost()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: host\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("host", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testUriHost11()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.1\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @Test
    public void testUriHost10()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET http://host/ HTTP/1.0\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_bad);
        assertEquals("http://host/", _uriOrStatus);
        assertEquals(0, _port);
    }

    @Test
    public void testNoHost()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("No Host", _bad);
    }

    @Test
    public void testIPHost()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: 192.168.0.1\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testIPv6Host()
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: [::1]\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(0, _port);
    }

    @Test
    public void testBadIPv6Host()
    {
        try (StacklessLogging s = new StacklessLogging(HttpParser.class))
        {
            ByteBuffer buffer = BufferUtil.toBuffer(
                "GET / HTTP/1.1\r\n" +
                    "Host: [::1\r\n" +
                    "Connection: close\r\n" +
                    "\r\n");

            HttpParser.RequestHandler handler = new Handler();
            HttpParser parser = new HttpParser(handler);
            parser.parseNext(buffer);
            assertThat(_bad, containsString("Bad"));
        }
    }

    @Test
    public void testHostPort()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: myhost:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("myhost", _host);
        assertEquals(8888, _port);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Host: whatever.com:xxxx\r\n",
        "Host: myhost:testBadPort\r\n",
        "Host: a b c d\r\n",
        "Host: hosta, hostb, hostc\r\n",
        "Host: hosta,hostb,hostc\r\n",
        "Host: hosta\r\nHost: hostb\r\nHost: hostc\r\n"
    })
    public void testBadHost(String hostline)
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                hostline +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertThat(_bad, containsString("Bad Host"));
    }

    @Test
    public void testIPHostPort()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: 192.168.0.1:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("192.168.0.1", _host);
        assertEquals(8888, _port);
    }

    @Test
    public void testIPv6HostPort()
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: [::1]:8888\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertEquals("[::1]", _host);
        assertEquals(8888, _port);
    }

    @Test
    public void testEmptyHostPort()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host:\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parser.parseNext(buffer);
        assertNull(_host);
        assertNull(_bad);
    }

    @Test
    public void testRequestMaxHeaderBytesURITooLong()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
                "GET /long/nested/path/uri HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        int maxHeaderBytes = 5;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("414", _bad);
    }

    @Test
    public void testRequestMaxHeaderBytesCumulative()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
                "GET /nested/path/uri HTTP/1.1\r\n" +
                "Host: example.com\r\n" +
                "X-Large-Header: lorem-ipsum-dolor-sit\r\n" +
                "Connection: close\r\n" +
                "\r\n");

        int maxHeaderBytes = 64;
        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler, maxHeaderBytes);

        parseAll(parser, buffer);
        assertEquals("431", _bad);
    }

    @Test
    @SuppressWarnings("ReferenceEquality")
    public void testCachedField()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: www.smh.com.au\r\n" +
                "\r\n");

        HttpParser.RequestHandler handler = new Handler();
        HttpParser parser = new HttpParser(handler);
        parseAll(parser, buffer);
        assertEquals("www.smh.com.au", parser.getFieldCache().get("Host: www.smh.com.au").getValue());
        HttpField field = _fields.get(0);

        buffer.position(0);
        parseAll(parser, buffer);
        assertSame(field, _fields.get(0));
    }

    @Test
    public void testParseRequest()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Header1: value1\r\n" +
                "Connection: close\r\n" +
                "Accept-Encoding: gzip, deflated\r\n" +
                "Accept: unknown\r\n" +
                "\r\n");

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

    @Test
    public void testHTTP2Preface()
    {
        ByteBuffer buffer = BufferUtil.toBuffer(
            "PRI * HTTP/2.0\r\n" +
                "\r\n" +
                "SM\r\n" +
                "\r\n");

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

    @Test
    public void testForHTTP09HeaderCompleteTrueDoesNotEmitContentComplete()
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
        ByteBuffer buffer = BufferUtil.toBuffer("GET /path\r\n");
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

    @Test
    public void testForContentLengthZeroHeaderCompleteTrueDoesNotEmitContentComplete()
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
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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

    @Test
    public void testForEmptyChunkedContentHeaderCompleteTrueDoesNotEmitContentComplete()
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
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n" +
                "\r\n");
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

    @Test
    public void testForContentLengthZeroContentCompleteTrueDoesNotEmitMessageComplete()
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
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertFalse(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testForEmptyChunkedContentContentCompleteTrueDoesNotEmitMessageComplete()
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
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "0\r\n" +
                "\r\n");
        boolean handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(buffer.hasRemaining());
        assertFalse(_messageCompleted);

        // Need to parse more to advance the parser.
        handle = parser.parseNext(buffer);
        assertTrue(handle);
        assertTrue(_messageCompleted);
    }

    @Test
    public void testHeaderAfterContentLengthZeroContentCompleteTrue()
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

        String header = "Header: Foobar\r\n";
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +
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

    @Test
    public void testSmallContentLengthContentCompleteTrue()
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

        String header = "Header: Foobar\r\n";
        ByteBuffer buffer = BufferUtil.toBuffer(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n" +
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

    @Test
    public void testHeaderAfterSmallContentLengthContentCompleteTrue()
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
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 1\r\n" +
                "\r\n" +
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

    @Test
    public void testEOFContentContentCompleteTrue()
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
            "HTTP/1.1 200 OK\r\n" +
                "\r\n" +
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

    @Test
    public void testHEADRequestHeaderCompleteTrue()
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
            "HTTP/1.1 200 OK\r\n" +
                "\r\n");
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

    @Test
    public void testNoContentHeaderCompleteTrue()
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
            "HTTP/1.1 304 Not Modified\r\n" +
                "\r\n");
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

    @Test
    public void testCRLFAfterResponseHeaderCompleteTrue()
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
            "HTTP/1.1 304 Not Modified\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 303 See Other\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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

    @Test
    public void testCRLFAfterResponseContentCompleteTrue()
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
            "HTTP/1.1 304 Not Modified\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 303 See Other\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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

    @Test
    public void testCRLFAfterResponseMessageCompleteFalse()
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
            "HTTP/1.1 304 Not Modified\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +
                "\r\n" +
                "\r\n" +
                "HTTP/1.1 303 See Other\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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

    @Test
    public void testSPAfterResponseMessageCompleteFalse()
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
            "HTTP/1.1 304 Not Modified\r\n" +
                "\r\n" +
                " " + // Single SP.
                "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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
            "HTTP/1.1 200 OK\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n" +
                " " + // Single SP.
                "HTTP/1.1 303 See Other\r\n" +
                "Content-Length: 0\r\n" +
                "\r\n");
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
    private List<HttpField> _fields = new ArrayList<>();
    private List<HttpField> _trailers = new ArrayList<>();
    private String[] _hdr;
    private String[] _val;
    private int _headers;
    private boolean _early;
    private boolean _headerCompleted;
    private boolean _contentCompleted;
    private boolean _messageCompleted;
    private final List<ComplianceViolation> _complianceViolation = new ArrayList<>();

    private class Handler implements HttpParser.RequestHandler, HttpParser.ResponseHandler, ComplianceViolation.Listener
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

            if (field instanceof HostPortHttpField)
            {
                HostPortHttpField hpfield = (HostPortHttpField)field;
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
        public void badMessage(BadMessageException failure)
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
        public void onComplianceViolation(ComplianceViolation.Mode mode, ComplianceViolation violation, String reason)
        {
            _complianceViolation.add(violation);
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
