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
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for class {@link HttpScheme}.
 *
 * @see HttpScheme
 */
public class HttpSchemeTest
{
    @Test
    public void testIsReturningTrue()
    {
        HttpScheme httpScheme = HttpScheme.HTTPS;

        assertTrue(httpScheme.is("https"));
        assertEquals("https", httpScheme.asString());
        assertEquals("https", httpScheme.toString());
    }

    @Test
    public void testIsReturningFalse()
    {
        HttpScheme httpScheme = HttpScheme.HTTP;

        assertFalse(httpScheme.is(",CPL@@4'U4p"));
    }

    @Test
    public void testIsWithNull()
    {
        HttpScheme httpScheme = HttpScheme.HTTPS;

        assertFalse(httpScheme.is(null));
    }

    @Test
    public void testAsByteBuffer()
    {
        HttpScheme httpScheme = HttpScheme.WS;
        ByteBuffer byteBuffer = httpScheme.asByteBuffer();

        assertEquals("ws", httpScheme.asString());
        assertEquals("ws", httpScheme.toString());
        assertEquals(2, byteBuffer.capacity());
        assertEquals(2, byteBuffer.remaining());
        assertEquals(2, byteBuffer.limit());
        assertFalse(byteBuffer.hasArray());
        assertEquals(0, byteBuffer.position());
        assertTrue(byteBuffer.isReadOnly());
        assertFalse(byteBuffer.isDirect());
        assertTrue(byteBuffer.hasRemaining());
    }

    public static Stream<Arguments> appendNormalizedUriCases()
    {
        return Stream.of(
            // Default behaviors of stripping a port number based on scheme
            Arguments.of("http", "example.org", 80, "http://example.org"),
            Arguments.of("https", "example.org", 443, "https://example.org"),
            Arguments.of("ws", "example.org", 80, "ws://example.org"),
            Arguments.of("wss", "example.org", 443, "wss://example.org"),
            // Mismatches between scheme and port
            Arguments.of("http", "example.org", 443, "http://example.org:443"),
            Arguments.of("https", "example.org", 80, "https://example.org:80"),
            Arguments.of("ws", "example.org", 443, "ws://example.org:443"),
            Arguments.of("wss", "example.org", 80, "wss://example.org:80"),
            // Odd ports
            Arguments.of("http", "example.org", 12345, "http://example.org:12345"),
            Arguments.of("https", "example.org", 54321, "https://example.org:54321"),
            Arguments.of("ws", "example.org", 6666, "ws://example.org:6666"),
            Arguments.of("wss", "example.org", 7777, "wss://example.org:7777"),
            // Non-lowercase Schemes
            Arguments.of("HTTP", "example.org", 8181, "http://example.org:8181"),
            Arguments.of("hTTps", "example.org", 443, "https://example.org"),
            Arguments.of("WS", "example.org", 8282, "ws://example.org:8282"),
            Arguments.of("wsS", "example.org", 8383, "wss://example.org:8383"),
            // Undefined Ports
            Arguments.of("http", "example.org", 0, "http://example.org"),
            Arguments.of("https", "example.org", -1, "https://example.org"),
            Arguments.of("ws", "example.org", -80, "ws://example.org"),
            Arguments.of("wss", "example.org", -2, "wss://example.org"),
            // Unrecognized (non-http) schemes
            Arguments.of("foo", "example.org", 0, "foo://example.org"),
            Arguments.of("ssh", "example.org", 22, "ssh://example.org:22"),
            Arguments.of("ftp", "example.org", 21, "ftp://example.org:21"),
            Arguments.of("file", "etc", -1, "file://etc")
        );
    }

    @ParameterizedTest
    @MethodSource("appendNormalizedUriCases")
    public void testAppendNormalizedUri(String scheme, String server, int port, String expectedStr)
    {
        StringBuilder actual = new StringBuilder();
        HttpScheme.appendNormalizedUri(actual, scheme, server, port);
        assertEquals(expectedStr, actual.toString());
    }

    /**
     * Tests of parameters that would trigger an IllegalStateException from
     * {@link HttpScheme#appendNormalizedUri(StringBuilder, String, String, int)}
     */
    public static Stream<Arguments> appendNormalizedUriBadArgs()
    {
        return Stream.of(
            // bad schemes
            Arguments.of(null, "example.org", 0),
            Arguments.of("", "example.org", 0),
            Arguments.of("\t", "example.org", 0),
            Arguments.of("    ", "example.org", 0),
            // bad servers
            Arguments.of("http", null, 0),
            Arguments.of("http", "", 0),
            Arguments.of("http", "\t", 0),
            Arguments.of("http", "    ", 0)
        );
    }

    @ParameterizedTest
    @MethodSource("appendNormalizedUriBadArgs")
    public void testAppendNormalizedUriBadArgs(String scheme, String server, int port)
    {
        StringBuilder actual = new StringBuilder();
        assertThrows(IllegalArgumentException.class, () -> HttpScheme.appendNormalizedUri(actual, scheme, server, port));
    }
}
