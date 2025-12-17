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

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HostPortPatternTest
{
    @Test
    public void testExactHostnameMatch()
    {
        HostPortPattern pattern = HostPortPattern.from("example.com");

        assertTrue(pattern.test(new HostPort("example.com", 80)));
        assertTrue(pattern.test(new HostPort("example.com", 443)));
        assertTrue(pattern.test(new HostPort("EXAMPLE.COM", 80)));
        assertTrue(pattern.test(new HostPort("Example.Com", 8080)));

        assertFalse(pattern.test(new HostPort("www.example.com", 80)));
        assertFalse(pattern.test(new HostPort("example.org", 80)));
        assertFalse(pattern.test(new HostPort("notexample.com", 80)));
    }

    @Test
    public void testExactHostnameWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("example.com:8080");

        assertTrue(pattern.test(new HostPort("example.com", 8080)));
        assertTrue(pattern.test(new HostPort("EXAMPLE.COM", 8080)));

        assertFalse(pattern.test(new HostPort("example.com", 80)));
        assertFalse(pattern.test(new HostPort("example.com", 443)));
        assertFalse(pattern.test(new HostPort("www.example.com", 8080)));
    }

    @Test
    public void testWildcardPrefixPattern()
    {
        HostPortPattern pattern = HostPortPattern.from("*.example.com");

        assertTrue(pattern.test(new HostPort("www.example.com", 80)));
        assertTrue(pattern.test(new HostPort("api.example.com", 443)));
        assertTrue(pattern.test(new HostPort("foo.bar.example.com", 8080)));
        assertTrue(pattern.test(new HostPort("WWW.EXAMPLE.COM", 80)));
        // Also match the domain itself
        assertTrue(pattern.test(new HostPort("example.com", 80)));

        assertFalse(pattern.test(new HostPort("example.org", 80)));
        assertFalse(pattern.test(new HostPort("notexample.com", 80)));
        assertFalse(pattern.test(new HostPort("exampleXcom", 80)));
    }

    @Test
    public void testWildcardPrefixWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("*.example.com:8080");

        assertTrue(pattern.test(new HostPort("www.example.com", 8080)));
        assertTrue(pattern.test(new HostPort("api.example.com", 8080)));
        assertTrue(pattern.test(new HostPort("example.com", 8080)));

        assertFalse(pattern.test(new HostPort("www.example.com", 80)));
        assertFalse(pattern.test(new HostPort("www.example.com", 443)));
    }

    @Test
    public void testWildcardSuffixPattern()
    {
        HostPortPattern pattern = HostPortPattern.from("internal.*");

        assertTrue(pattern.test(new HostPort("internal.corp", 80)));
        assertTrue(pattern.test(new HostPort("internal.local", 443)));
        assertTrue(pattern.test(new HostPort("internal.svc.cluster", 8080)));
        assertTrue(pattern.test(new HostPort("INTERNAL.CORP", 80)));
        // Also match the prefix itself
        assertTrue(pattern.test(new HostPort("internal", 80)));

        assertFalse(pattern.test(new HostPort("external.corp", 80)));
        assertFalse(pattern.test(new HostPort("notinternal.corp", 80)));
    }

    @Test
    public void testWildcardSuffixWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("internal.*:9000");

        assertTrue(pattern.test(new HostPort("internal.corp", 9000)));
        assertTrue(pattern.test(new HostPort("internal.local", 9000)));
        assertTrue(pattern.test(new HostPort("internal", 9000)));

        assertFalse(pattern.test(new HostPort("internal.corp", 80)));
        assertFalse(pattern.test(new HostPort("internal.local", 443)));
    }

    @Test
    public void testMatchAllPattern()
    {
        HostPortPattern pattern = HostPortPattern.from("*");

        assertTrue(pattern.test(new HostPort("example.com", 80)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.1.1", 443)));
    }

    @Test
    public void testMatchAllWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("*:8080");

        assertTrue(pattern.test(new HostPort("example.com", 8080)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.1.1", 8080)));

        assertFalse(pattern.test(new HostPort("example.com", 80)));
        assertFalse(pattern.test(new HostPort("localhost", 443)));
    }

    @Test
    public void testIpAddressExact()
    {
        HostPortPattern pattern = HostPortPattern.from("192.168.1.100");

        assertTrue(pattern.test(new HostPort("192.168.1.100", 80)));
        assertTrue(pattern.test(new HostPort("192.168.1.100", 8080)));

        assertFalse(pattern.test(new HostPort("192.168.1.101", 80)));
        assertFalse(pattern.test(new HostPort("192.168.2.100", 80)));
    }

    @Test
    public void testIpAddressWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("192.168.1.100:8080");

        assertTrue(pattern.test(new HostPort("192.168.1.100", 8080)));

        assertFalse(pattern.test(new HostPort("192.168.1.100", 80)));
        assertFalse(pattern.test(new HostPort("192.168.1.101", 8080)));
    }

    @Test
    public void testCidrPattern()
    {
        HostPortPattern pattern = HostPortPattern.from("192.168.0.0/16");

        assertTrue(pattern.test(new HostPort("192.168.0.1", 80)));
        assertTrue(pattern.test(new HostPort("192.168.255.255", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.100.50", 443)));

        assertFalse(pattern.test(new HostPort("192.169.0.1", 80)));
        assertFalse(pattern.test(new HostPort("10.0.0.1", 80)));
    }

    @Test
    public void testCidrWithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("10.0.0.0/8:8080");

        assertTrue(pattern.test(new HostPort("10.0.0.1", 8080)));
        assertTrue(pattern.test(new HostPort("10.255.255.255", 8080)));

        assertFalse(pattern.test(new HostPort("10.0.0.1", 80)));
        assertFalse(pattern.test(new HostPort("11.0.0.1", 8080)));
    }

    @Test
    public void testIpRange()
    {
        HostPortPattern pattern = HostPortPattern.from("10.0.0.1-10.0.0.10");

        assertTrue(pattern.test(new HostPort("10.0.0.1", 80)));
        assertTrue(pattern.test(new HostPort("10.0.0.5", 80)));
        assertTrue(pattern.test(new HostPort("10.0.0.10", 80)));

        assertFalse(pattern.test(new HostPort("10.0.0.0", 80)));
        assertFalse(pattern.test(new HostPort("10.0.0.11", 80)));
    }

    @Test
    public void testNullHostPort()
    {
        HostPortPattern pattern = HostPortPattern.from("example.com");
        assertFalse(pattern.test(null));
    }

    @Test
    public void testBadPatternEmpty()
    {
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from(""));
        assertThat(cause.getMessage(), containsString("empty"));
    }

    @Test
    public void testBadPatternInvalidPort()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from("example.com:abc"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from("example.com:-1"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from("example.com:99999"));
    }

    @Test
    public void testBadPatternMalformedIpv6()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from("[::1"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from("[::1]abc"));
    }

    @Test
    public void testNullPattern()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPattern.from(null));
    }

    @Test
    public void testEqualsAndHashCode()
    {
        HostPortPattern pattern1 = HostPortPattern.from("*.example.com");
        HostPortPattern pattern2 = HostPortPattern.from("*.example.com");
        HostPortPattern pattern3 = HostPortPattern.from("*.other.com");

        assertTrue(pattern1.equals(pattern2));
        assertTrue(pattern1.hashCode() == pattern2.hashCode());
        assertFalse(pattern1.equals(pattern3));
    }

    @Test
    public void testToString()
    {
        HostPortPattern pattern = HostPortPattern.from("*.example.com:8080");
        assertTrue(pattern.toString().equals("*.example.com:8080"));
    }

    @Test
    public void testLocalhostPatterns()
    {
        HostPortPattern pattern = HostPortPattern.from("localhost");

        assertTrue(pattern.test(new HostPort("localhost", 80)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("LOCALHOST", 80)));

        assertFalse(pattern.test(new HostPort("127.0.0.1", 80)));
    }

    @Test
    public void testIpv6Pattern()
    {
        HostPortPattern pattern = HostPortPattern.from("[::1]");

        assertTrue(pattern.test(new HostPort("[::1]", 80)));
        assertTrue(pattern.test(new HostPort("[::1]", 8080)));
    }

    @Test
    public void testIpv6WithPort()
    {
        HostPortPattern pattern = HostPortPattern.from("[::1]:8080");

        assertTrue(pattern.test(new HostPort("[::1]", 8080)));

        assertFalse(pattern.test(new HostPort("[::1]", 80)));
    }
}
