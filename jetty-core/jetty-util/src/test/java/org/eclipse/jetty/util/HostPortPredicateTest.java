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

public class HostPortPredicateTest
{
    @Test
    public void testExactHostnameMatch()
    {
        HostPortPredicate pattern = HostPortPredicate.from("example.com");

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
        HostPortPredicate pattern = HostPortPredicate.from("example.com:8080");

        assertTrue(pattern.test(new HostPort("example.com", 8080)));
        assertTrue(pattern.test(new HostPort("EXAMPLE.COM", 8080)));

        assertFalse(pattern.test(new HostPort("example.com", 80)));
        assertFalse(pattern.test(new HostPort("example.com", 443)));
        assertFalse(pattern.test(new HostPort("www.example.com", 8080)));
    }

    @Test
    public void testWildcardPrefixPattern()
    {
        HostPortPredicate pattern = HostPortPredicate.from("*.example.com");

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
        HostPortPredicate pattern = HostPortPredicate.from("*.example.com:8080");

        assertTrue(pattern.test(new HostPort("www.example.com", 8080)));
        assertTrue(pattern.test(new HostPort("api.example.com", 8080)));
        assertTrue(pattern.test(new HostPort("example.com", 8080)));

        assertFalse(pattern.test(new HostPort("www.example.com", 80)));
        assertFalse(pattern.test(new HostPort("www.example.com", 443)));
    }

    @Test
    public void testWildcardSuffixPattern()
    {
        HostPortPredicate pattern = HostPortPredicate.from("internal.*");

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
        HostPortPredicate pattern = HostPortPredicate.from("internal.*:9000");

        assertTrue(pattern.test(new HostPort("internal.corp", 9000)));
        assertTrue(pattern.test(new HostPort("internal.local", 9000)));
        assertTrue(pattern.test(new HostPort("internal", 9000)));

        assertFalse(pattern.test(new HostPort("internal.corp", 80)));
        assertFalse(pattern.test(new HostPort("internal.local", 443)));
    }

    @Test
    public void testMatchAllPattern()
    {
        HostPortPredicate pattern = HostPortPredicate.from("*");

        assertTrue(pattern.test(new HostPort("example.com", 80)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.1.1", 443)));
    }

    @Test
    public void testMatchAllWithPort()
    {
        HostPortPredicate pattern = HostPortPredicate.from("*:8080");

        assertTrue(pattern.test(new HostPort("example.com", 8080)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.1.1", 8080)));

        assertFalse(pattern.test(new HostPort("example.com", 80)));
        assertFalse(pattern.test(new HostPort("localhost", 443)));
    }

    @Test
    public void testIpAddressExact()
    {
        HostPortPredicate pattern = HostPortPredicate.from("192.168.1.100");

        assertTrue(pattern.test(new HostPort("192.168.1.100", 80)));
        assertTrue(pattern.test(new HostPort("192.168.1.100", 8080)));

        assertFalse(pattern.test(new HostPort("192.168.1.101", 80)));
        assertFalse(pattern.test(new HostPort("192.168.2.100", 80)));
    }

    @Test
    public void testIpAddressWithPort()
    {
        HostPortPredicate pattern = HostPortPredicate.from("192.168.1.100:8080");

        assertTrue(pattern.test(new HostPort("192.168.1.100", 8080)));

        assertFalse(pattern.test(new HostPort("192.168.1.100", 80)));
        assertFalse(pattern.test(new HostPort("192.168.1.101", 8080)));
    }

    @Test
    public void testCidrPattern()
    {
        HostPortPredicate pattern = HostPortPredicate.from("192.168.0.0/16");

        assertTrue(pattern.test(new HostPort("192.168.0.1", 80)));
        assertTrue(pattern.test(new HostPort("192.168.255.255", 8080)));
        assertTrue(pattern.test(new HostPort("192.168.100.50", 443)));

        assertFalse(pattern.test(new HostPort("192.169.0.1", 80)));
        assertFalse(pattern.test(new HostPort("10.0.0.1", 80)));
    }

    @Test
    public void testCidrWithPort()
    {
        HostPortPredicate pattern = HostPortPredicate.from("10.0.0.0/8:8080");

        assertTrue(pattern.test(new HostPort("10.0.0.1", 8080)));
        assertTrue(pattern.test(new HostPort("10.255.255.255", 8080)));

        assertFalse(pattern.test(new HostPort("10.0.0.1", 80)));
        assertFalse(pattern.test(new HostPort("11.0.0.1", 8080)));
    }

    @Test
    public void testIpRange()
    {
        HostPortPredicate pattern = HostPortPredicate.from("10.0.0.1-10.0.0.10");

        assertTrue(pattern.test(new HostPort("10.0.0.1", 80)));
        assertTrue(pattern.test(new HostPort("10.0.0.5", 80)));
        assertTrue(pattern.test(new HostPort("10.0.0.10", 80)));

        assertFalse(pattern.test(new HostPort("10.0.0.0", 80)));
        assertFalse(pattern.test(new HostPort("10.0.0.11", 80)));
    }

    @Test
    public void testNullHostPort()
    {
        HostPortPredicate pattern = HostPortPredicate.from("example.com");
        assertFalse(pattern.test(null));
    }

    @Test
    public void testBadPatternEmpty()
    {
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from(""));
        assertThat(cause.getMessage(), containsString("empty"));
    }

    @Test
    public void testBadPatternInvalidPort()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("example.com:abc"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("example.com:-1"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("example.com:99999"));
    }

    @Test
    public void testBadPatternMalformedIpv6()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("[::1"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("[::1]abc"));
    }

    @Test
    public void testNullPattern()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from(null));
    }

    @Test
    public void testEqualsAndHashCode()
    {
        HostPortPredicate pattern1 = HostPortPredicate.from("*.example.com");
        HostPortPredicate pattern2 = HostPortPredicate.from("*.example.com");
        HostPortPredicate pattern3 = HostPortPredicate.from("*.other.com");

        assertTrue(pattern1.equals(pattern2));
        assertTrue(pattern1.hashCode() == pattern2.hashCode());
        assertFalse(pattern1.equals(pattern3));
    }

    @Test
    public void testToString()
    {
        HostPortPredicate pattern = HostPortPredicate.from("*.example.com:8080");
        assertTrue(pattern.toString().equals("*.example.com:8080"));
    }

    @Test
    public void testLocalhostPatterns()
    {
        HostPortPredicate pattern = HostPortPredicate.from("localhost");

        assertTrue(pattern.test(new HostPort("localhost", 80)));
        assertTrue(pattern.test(new HostPort("localhost", 8080)));
        assertTrue(pattern.test(new HostPort("LOCALHOST", 80)));

        assertFalse(pattern.test(new HostPort("127.0.0.1", 80)));
    }

    @Test
    public void testIpv6Pattern()
    {
        HostPortPredicate pattern = HostPortPredicate.from("[::1]");

        assertTrue(pattern.test(new HostPort("[::1]", 80)));
        assertTrue(pattern.test(new HostPort("[::1]", 8080)));
    }

    @Test
    public void testIpv6WithPort()
    {
        HostPortPredicate pattern = HostPortPredicate.from("[::1]:8080");

        assertTrue(pattern.test(new HostPort("[::1]", 8080)));

        assertFalse(pattern.test(new HostPort("[::1]", 80)));
    }

    @Test
    public void testRejectPatternWithUserinfo()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("user@example.com"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("user:pass@example.com"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("user@*.example.com"));
    }

    @Test
    public void testRejectPatternWithPath()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("example.com/admin"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("*.example.com/path"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("1.2.3.4/not-a-cidr"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("1.2.3.4/33"));
    }

    @Test
    public void testRejectMiddleWildcard()
    {
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("foo.*.bar.com"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("*foo.example.com"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("foo*.example.com"));
        assertThrows(IllegalArgumentException.class, () -> HostPortPredicate.from("example.*com"));
    }

    @Test
    public void testNoPortMatchesAnyPort()
    {
        // Pattern without port should match any port
        HostPortPredicate pattern = HostPortPredicate.from("example.com");

        assertTrue(pattern.test(new HostPort("example.com", 80)));
        assertTrue(pattern.test(new HostPort("example.com", 443)));
        assertTrue(pattern.test(new HostPort("example.com", 8080)));
        // Port 0 means "unspecified" - pattern without port matches it too
        assertTrue(pattern.test(new HostPort("example.com", 0)));
    }

    @Test
    public void testSpecificPortMatchesOnlyThatPort()
    {
        // Pattern with port should match only that specific port
        HostPortPredicate pattern = HostPortPredicate.from("example.com:8080");

        assertTrue(pattern.test(new HostPort("example.com", 8080)));

        assertFalse(pattern.test(new HostPort("example.com", 80)));
        assertFalse(pattern.test(new HostPort("example.com", 443)));
        // Port 0 means "unspecified" - does not match specific port pattern
        assertFalse(pattern.test(new HostPort("example.com", 0)));
    }
}
