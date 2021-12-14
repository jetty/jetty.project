//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.util;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class HostPortTest
{
    private static Stream<Arguments> validAuthorityProvider()
    {
        return Stream.of(
            Arguments.of("host", "host", null),
            Arguments.of("127.0.0.1:", "127.0.0.1", null),
            Arguments.of("[0::0::0::0::1]:", "[0::0::0::0::1]", null),
            Arguments.of("host:80", "host", "80"),
            Arguments.of("10.10.10.1", "10.10.10.1", null),
            Arguments.of("10.10.10.1:80", "10.10.10.1", "80"),
            Arguments.of("[0::0::0::1]", "[0::0::0::1]", null),
            Arguments.of("[0::0::0::1]:80", "[0::0::0::1]", "80"),
            Arguments.of("0:1:2:3:4:5:6", "[0:1:2:3:4:5:6]", null),
            Arguments.of("127.0.0.1:65535", "127.0.0.1", "65535"),
            // scheme based normalization https://datatracker.ietf.org/doc/html/rfc3986#section-6.2.3
            Arguments.of("host:", "host", null),
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
            Arguments.of("[::192.9.5.5]", "[::192.9.5.5]", null), // TODO: IPv4 within IPv6 no longer specified in non-obsolete spec
            Arguments.of("[::FFFF:129.144.52.38]:80", "[::FFFF:129.144.52.38]", "80"),  // TODO: IPv4 within IPv6 no longer specified in non-obsolete spec
            Arguments.of("[2010:836B:4179::836B:4179]", "[2010:836B:4179::836B:4179]", null),
            // Modified Examples from above, not using square brackets (valid, but should never have a port)
            Arguments.of("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210", "[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]", null),
            Arguments.of("1080:0:0:0:8:800:200C:417A", "[1080:0:0:0:8:800:200C:417A]", null),
            Arguments.of("3ffe:2a00:100:7031::1", "[3ffe:2a00:100:7031::1]", null),
            Arguments.of("1080::8:800:200C:417A", "[1080::8:800:200C:417A]", null),
            Arguments.of("::192.9.5.5", "[::192.9.5.5]", null),  // TODO: IPv4 within IPv6 no longer specified in non-obsolete spec
            Arguments.of("::FFFF:129.144.52.38", "[::FFFF:129.144.52.38]", null),  // TODO: IPv4 within IPv6 no longer specified in non-obsolete spec
            Arguments.of("2010:836B:4179::836B:4179", "[2010:836B:4179::836B:4179]", null),
            // IDN
            Arguments.of("пример.рф", "пример.рф", null)
        );
    }

    private static Stream<Arguments> invalidAuthorityProvider()
    {
        return Stream.of(
                null,
                "", // TODO: if addressing edge case with absolute-uri and empty Host header
                "host:xxx",
                "127.0.0.1:xxx",
                "[0::0::0::0::1]:xxx",
                "host:-80",
                "127.0.0.1:-80", // negative port
                "[0::0::0::0::1]:-80", // negative port
                "127.0.0.1:65536", // port too big
                ":",
                ":44",
                "'eclipse.org:443'", // bad quoting that made it through
                "eclipse.org:443\"", // bad end quoting that made it through
                "':88'",
                "jetty.eclipse.org:88007386429567428956488", // port too big
                "this:that:or:the:other:222",  // multiple ':'
                "[sheep:cheese:of:rome]:80", // invalid/mimic ipv6 with port
                "[pecorino:romano]" // invalid/mimic ipv6 without port
                // TODO: invalid host name checks? - (see issue #7269)
            /*  "-",
                "-:77",
                "*",
                "*:88",
                "*.eclipse.org", */
            )
            .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("validAuthorityProvider")
    public void testValidAuthority(String authority, String expectedHost, Integer expectedPort)
    {
        HostPort hostPort = new HostPort(authority);
        assertThat(authority, hostPort.getHost(), is(expectedHost));

        if (expectedPort == null)
            assertThat("Port should not be present for [" + authority + "]", hostPort.getPort(), is(HostPort.NO_PORT));
        else
            assertThat("Port for [" + authority + "]", hostPort.getPort(), is(expectedPort));
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
