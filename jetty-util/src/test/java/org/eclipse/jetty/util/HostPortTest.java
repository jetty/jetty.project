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
    public static Stream<Arguments> validAuthorityProvider()
    {
        return Stream.of(
            Arguments.of("", "", 0),
            Arguments.of("host", "host", 0),
            Arguments.of("host:80", "host", 80),
            Arguments.of("10.10.10.1", "10.10.10.1", 0),
            Arguments.of("10.10.10.1:80", "10.10.10.1", 80),
            Arguments.of("127.0.0.1:65535", "127.0.0.1", 65535),
            // Localhost tests
            Arguments.of("localhost:80", "localhost", 80),
            Arguments.of("127.0.0.1:80", "127.0.0.1", 80),
            Arguments.of("::1", "[::1]", 0),
            Arguments.of("[::1]:443", "[::1]", 443),
            // Examples from https://tools.ietf.org/html/rfc2732#section-2
            Arguments.of("[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]:80", "[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]", 80),
            Arguments.of("[1080:0:0:0:8:800:200C:417A]", "[1080:0:0:0:8:800:200C:417A]", 0),
            Arguments.of("[3ffe:2a00:100:7031::1]", "[3ffe:2a00:100:7031::1]", 0),
            Arguments.of("[1080::8:800:200C:417A]", "[1080::8:800:200C:417A]", 0),
            Arguments.of("[::192.9.5.5]", "[::192.9.5.5]", 0),
            Arguments.of("[::FFFF:129.144.52.38]:80", "[::FFFF:129.144.52.38]", 80),
            Arguments.of("[2010:836B:4179::836B:4179]", "[2010:836B:4179::836B:4179]", 0),
            // Modified Examples from above, not using square brackets (valid, but should never have a port)
            Arguments.of("FEDC:BA98:7654:3210:FEDC:BA98:7654:3210", "[FEDC:BA98:7654:3210:FEDC:BA98:7654:3210]", 0),
            Arguments.of("1080:0:0:0:8:800:200C:417A", "[1080:0:0:0:8:800:200C:417A]", 0),
            Arguments.of("3ffe:2a00:100:7031::1", "[3ffe:2a00:100:7031::1]", 0),
            Arguments.of("1080::8:800:200C:417A", "[1080::8:800:200C:417A]", 0),
            Arguments.of("::192.9.5.5", "[::192.9.5.5]", 0),
            Arguments.of("::FFFF:129.144.52.38", "[::FFFF:129.144.52.38]", 0),
            Arguments.of("2010:836B:4179::836B:4179", "[2010:836B:4179::836B:4179]", 0)
        );
    }

    @ParameterizedTest
    @MethodSource("validAuthorityProvider")
    public void testValidAuthority(String authority, String expectedHost, int expectedPort)
    {
        HostPort hostPort = new HostPort(authority);
        assertThat("Host for: " + authority, hostPort.getHost(), is(expectedHost));
        assertThat("Port for: " + authority, hostPort.getPort(), is(expectedPort));
    }

    @ParameterizedTest
    @MethodSource("validAuthorityProvider")
    public void testValidAuthorityViaUnsafe(String authority, String expectedHost, Integer expectedPort)
    {
        HostPort hostPort = HostPort.unsafe(authority);
        assertThat("(unsafe) Host for: " + authority, hostPort.getHost(), is(expectedHost));
        assertThat("(unsafe) Port for: " + authority, hostPort.getPort(), is(expectedPort));
    }

    public static Stream<Arguments> invalidAuthorityProvider()
    {
        return Stream.of(
            Arguments.of(null, "", 0), // null authority
            Arguments.of(":", "", 0), // no host, no port, port delimiter only
            Arguments.of(":::::", ":::::", 0), // host is only port delimiters, no port, port delimiter only
            Arguments.of(":80", "", 80), // no host, port only
            Arguments.of("::::::80", "::::::80", 0), // no host, port only
            Arguments.of("host:", "host", 0), // host, port delimiter, but empty
            Arguments.of("host:::::", "host:::::", 0), // host, port delimiter, but empty
            Arguments.of("127.0.0.1:", "127.0.0.1", 0), // IPv4, port delimiter, but empty
            Arguments.of("[0::0::0::0::1", "[0::0::0::0::1", 0), // no ending bracket for IP literal
            Arguments.of("0::0::0::0::1]", "0::0::0::0::1]", 0), // no starting bracket for IP literal
            Arguments.of("[0::0::0::0::1]:", "[0::0::0::0::1]", 0), // IP literal, port delimiter, but empty
            // forbidden characters in reg-name
            Arguments.of("\"\"", "\"\"", 0), // just quotes
            // not valid to Java (InetAddress, InetSocketAddress, or URI) : "Expected hex digits or IPv4 address"
            Arguments.of("[0::0::0::1]", "[0::0::0::1]", 0),
            Arguments.of("[0::0::0::1]:80", "[0::0::0::1]", 80),
            // not valid to Java (InetAddress, InetSocketAddress, or URI) : "IPv6 address too short"
            Arguments.of("0:1:2:3:4:5:6", "0:1:2:3:4:5:6", 0),
            // Bad ports declarations (should all end up with -1 port)
            Arguments.of("host:xxx", "host:xxx", 0), // invalid port
            Arguments.of("127.0.0.1:xxx", "127.0.0.1:xxx", 0), // host + invalid port
            Arguments.of("[0::0::0::0::1]:xxx", "[0::0::0::0::1]:xxx", 0), // ipv6 + invalid port
            Arguments.of("[0::0::0::0::1].80", "[0::0::0::0::1].80", 0), // ipv6 with bogus port delimiter
            Arguments.of("host:-80", "host:-80", 0), // host + invalid negative port
            Arguments.of("127.0.0.1:-80", "127.0.0.1:-80", 0), // ipv4 + invalid port
            Arguments.of("[0::0::0::0::1]:-80", "[0::0::0::0::1]:-80", 0), // ipv6 + invalid port
            Arguments.of("127.0.0.1:65536", "127.0.0.1:65536", 0), // ipv4 + port value too high
            Arguments.of("example.org:112233445566778899", "example.org:112233445566778899", 0), // ipv4 + port value too high
            // Examples of bad Host header values (usually client bugs that shouldn't allow them to be sent)
            Arguments.of("Group - Machine", "Group - Machine", 0), // spaces
            Arguments.of("<calculated when request is sent>", "<calculated when request is sent>", 0), // spaces and forbidden characters in reg-name
            Arguments.of("[link](https://example.org/)", "[link](https://example.org/)", 0), // forbidden characters in reg-name
            Arguments.of("example.org/zed", "example.org/zed", 0), // forbidden character in reg-name (slash)
            // common hacking attempts, seen as values on the `Host:` request header
            Arguments.of("| ping 127.0.0.1 -n 10", "| ping 127.0.0.1 -n 10", 0), // forbidden characters in reg-name
            Arguments.of("%uf%80%ff%xx%uffff", "%uf%80%ff%xx%uffff", 0), // (invalid encoding)
            Arguments.of("[${jndi${:-:}ldap${:-:}]", "[${jndi${:-:}ldap${:-:}]", 0), // log4j hacking (forbidden chars in reg-name)
            Arguments.of("[${jndi:ldap://example.org:59377/nessus}]", "[${jndi:ldap://example.org:59377/nessus}]", 0), // log4j hacking (forbidden chars in reg-name)
            Arguments.of("${ip}", "${ip}", 0), // variation of log4j hack (forbidden chars in reg-name)
            Arguments.of("' *; host xyz.hacking.pro; '", "' *; host xyz.hacking.pro; '", 0), // forbidden chars in reg-name
            Arguments.of("'/**/OR/**/1/**/=/**/1", "'/**/OR/**/1/**/=/**/1", 0), // forbidden chars in reg-name
            Arguments.of("AND (SELECT 1 FROM(SELECT COUNT(*),CONCAT('x',(SELECT (ELT(1=1,1))),'x',FLOOR(RAND(0)*2))x FROM INFORMATION_SCHEMA.CHARACTER_SETS GROUP BY x)a)", "AND (SELECT 1 FROM(SELECT COUNT(*),CONCAT('x',(SELECT (ELT(1=1,1))),'x',FLOOR(RAND(0)*2))x FROM INFORMATION_SCHEMA.CHARACTER_SETS GROUP BY x)a)", 0)
        );
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorityProvider")
    public void testInvalidAuthority(String rawAuthority, String ignoredExpectedHost, int ignoredExpectedPort)
    {
        assertThrows(IllegalArgumentException.class, () -> new HostPort(rawAuthority));
    }

    @ParameterizedTest
    @MethodSource("invalidAuthorityProvider")
    public void testInvalidAuthorityViaUnsafe(String rawAuthority, String expectedHost, int expectedPort)
    {
        HostPort hostPort = HostPort.unsafe(rawAuthority);
        assertThat("(unsafe) Host for: " + rawAuthority, hostPort.getHost(), is(expectedHost));
        assertThat("(unsafe) Port for: " + rawAuthority, hostPort.getPort(), is(expectedPort));
    }
}
