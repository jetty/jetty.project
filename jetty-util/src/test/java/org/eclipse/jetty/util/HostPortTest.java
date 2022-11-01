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

package org.eclipse.jetty.util;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HostPortTest
{
    public static Stream<Arguments> validAuthorityProvider()
    {

        return Stream.of(
            Arguments.of("", "", null),
            Arguments.of("host", "host", null),
            Arguments.of("host:80", "host", "80"),
            Arguments.of("10.10.10.1", "10.10.10.1", null),
            Arguments.of("10.10.10.1:80", "10.10.10.1", "80"),
            Arguments.of("127.0.0.1:65535", "127.0.0.1", "65535"),
            // Localhost tests
            Arguments.of("localhost:80", "localhost", "80"),
            Arguments.of("127.0.0.1:80", "127.0.0.1", "80"),
            Arguments.of("::1", "[::1]", null),
            Arguments.of("[::1]:443", "[::1]", "443"),
            // Examples from https://tools.ietf.org/html/rfc2732#section-2
            Arguments.of("[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]:80", "[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]", "80"),
            Arguments.of("[1080:0:0:0:8:800:200C:417A]", "[1080:0:0:0:8:800:200C:417A]", null),
            Arguments.of("[3ffe:2a00:100:7031::1]", "[3ffe:2a00:100:7031::1]", null),
            Arguments.of("[1080::8:800:200C:417A]", "[1080::8:800:200C:417A]", null),
            Arguments.of("[::192.9.5.5]", "[::192.9.5.5]", null),
            Arguments.of("[::FFFF:129.144.52.38]:80", "[::FFFF:129.144.52.38]", "80"),
            Arguments.of("[2010:836B:4179::836B:4179]", "[2010:836B:4179::836B:4179]", null),
            // Modified Examples from above, not using square brackets (valid, but should never have a port)
            Arguments.of("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210", "[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]", null),
            Arguments.of("1080:0:0:0:8:800:200C:417A", "[1080:0:0:0:8:800:200C:417A]", null),
            Arguments.of("3ffe:2a00:100:7031::1", "[3ffe:2a00:100:7031::1]", null),
            Arguments.of("1080::8:800:200C:417A", "[1080::8:800:200C:417A]", null),
            Arguments.of("::192.9.5.5", "[::192.9.5.5]", null),
            Arguments.of("::FFFF:129.144.52.38", "[::FFFF:129.144.52.38]", null),
            Arguments.of("2010:836B:4179::836B:4179", "[2010:836B:4179::836B:4179]", null)
        );
    }

    public static Stream<Arguments> invalidAuthorityProvider()
    {
        return Stream.of(
            null,
            ":80", // no host, port only
            "host:", // no port
            "127.0.0.1:", // no port
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
            "127.0.0.1:65536" // ipv4 + port value too high
        ).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("validAuthorityProvider")
    public void testValidAuthority(String authority, String expectedHost, Integer expectedPort)
    {
        try
        {
            HostPort hostPort = new HostPort(authority);
            assertThat(authority, hostPort.getHost(), is(expectedHost));

            if (expectedPort == null)
                assertThat(authority, hostPort.getPort(), is(0));
            else
                assertThat(authority, hostPort.getPort(), is(expectedPort));
        }
        catch (Exception e)
        {
            if (expectedHost != null)
                e.printStackTrace();
            assertNull(authority, expectedHost);
        }
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorityProvider")
    public void testInvalidAuthority(String authority)
    {
        assertThrows(IllegalArgumentException.class, () ->
        {
            new HostPort(authority);
        });
    }
}
