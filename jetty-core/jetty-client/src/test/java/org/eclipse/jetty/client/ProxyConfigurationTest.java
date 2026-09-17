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

package org.eclipse.jetty.client;

import org.eclipse.jetty.toolchain.test.Net;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ProxyConfigurationTest
{
    @Test
    public void testProxyMatchesWithoutIncludesWithoutExcludes()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        assertTrue(proxy.matches(new Origin("http", "any", 0)));
    }

    @Test
    public void testProxyMatchesWithOnlyExcludes()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("1.2.3.4:5");

        assertTrue(proxy.matches(new Origin("http", "any", 0)));
        assertTrue(proxy.matches(new Origin("http", "1.2.3.4", 0)));
        assertFalse(proxy.matches(new Origin("http", "1.2.3.4", 5)));
    }

    @Test
    public void testProxyMatchesWithOnlyIncludes()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getIncludedAddresses().add("1.2.3.4:5");

        assertFalse(proxy.matches(new Origin("http", "any", 0)));
        assertFalse(proxy.matches(new Origin("http", "1.2.3.4", 0)));
        assertTrue(proxy.matches(new Origin("http", "1.2.3.4", 5)));
    }

    @Test
    public void testProxyMatchesWithIncludesAndExcludes()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getIncludedAddresses().add("1.2.3.4");
        proxy.getExcludedAddresses().add("1.2.3.4:5");

        assertFalse(proxy.matches(new Origin("http", "any", 0)));
        assertTrue(proxy.matches(new Origin("http", "1.2.3.4", 0)));
        assertFalse(proxy.matches(new Origin("http", "1.2.3.4", 5)));
    }

    @Test
    public void testProxyMatchesWithIncludesAndExcludesIPv6()
    {
        Assumptions.assumeTrue(Net.isIpv6InterfaceAvailable());
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getIncludedAddresses().add("[1::2:3:4]");
        proxy.getExcludedAddresses().add("[1::2:3:4]:5");

        assertFalse(proxy.matches(new Origin("http", "any", 0)));
        assertTrue(proxy.matches(new Origin("http", "[1::2:3:4]", 0)));
        assertFalse(proxy.matches(new Origin("http", "[1::2:3:4]", 5)));
    }

    @Test
    public void testProxyMatchesWithWildcardPrefix()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("*.internal.corp");

        assertTrue(proxy.matches(new Origin("http", "example.com", 80)));
        assertFalse(proxy.matches(new Origin("http", "api.internal.corp", 80)));
        assertFalse(proxy.matches(new Origin("http", "db.internal.corp", 443)));
        // Also matches the domain itself
        assertFalse(proxy.matches(new Origin("http", "internal.corp", 80)));
    }

    @Test
    public void testProxyMatchesWithWildcardSuffix()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("localhost.*");

        assertTrue(proxy.matches(new Origin("http", "example.com", 80)));
        assertFalse(proxy.matches(new Origin("http", "localhost.local", 80)));
        assertFalse(proxy.matches(new Origin("http", "localhost.corp", 443)));
    }

    @Test
    public void testProxyMatchesWithCidr()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("192.168.0.0/16");

        assertTrue(proxy.matches(new Origin("http", "10.0.0.1", 80)));
        assertFalse(proxy.matches(new Origin("http", "192.168.1.1", 80)));
        assertFalse(proxy.matches(new Origin("http", "192.168.255.255", 443)));
    }

    @Test
    public void testProxyMatchesWithIpRange()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("10.0.0.1-10.0.0.10");

        assertTrue(proxy.matches(new Origin("http", "10.0.0.11", 80)));
        assertFalse(proxy.matches(new Origin("http", "10.0.0.1", 80)));
        assertFalse(proxy.matches(new Origin("http", "10.0.0.5", 80)));
        assertFalse(proxy.matches(new Origin("http", "10.0.0.10", 80)));
    }

    @Test
    public void testProxyMatchesWithWildcardAndPort()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("*.internal.corp:8080");

        assertTrue(proxy.matches(new Origin("http", "api.internal.corp", 80)));
        assertTrue(proxy.matches(new Origin("http", "api.internal.corp", 443)));
        assertFalse(proxy.matches(new Origin("http", "api.internal.corp", 8080)));
    }

    @Test
    public void testProxyMatchesWithMultiplePatterns()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getExcludedAddresses().add("*.internal.corp");
        proxy.getExcludedAddresses().add("192.168.0.0/16");
        proxy.getExcludedAddresses().add("localhost");

        assertTrue(proxy.matches(new Origin("http", "example.com", 80)));
        assertTrue(proxy.matches(new Origin("http", "10.0.0.1", 80)));

        assertFalse(proxy.matches(new Origin("http", "api.internal.corp", 80)));
        assertFalse(proxy.matches(new Origin("http", "192.168.1.1", 80)));
        assertFalse(proxy.matches(new Origin("http", "localhost", 8080)));
    }

    @Test
    public void testProxyMatchesWithIncludeWildcardExcludeSpecific()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getIncludedAddresses().add("*.example.com");
        proxy.getExcludedAddresses().add("admin.example.com");

        assertFalse(proxy.matches(new Origin("http", "other.com", 80)));
        assertTrue(proxy.matches(new Origin("http", "www.example.com", 80)));
        assertTrue(proxy.matches(new Origin("http", "api.example.com", 443)));
        assertFalse(proxy.matches(new Origin("http", "admin.example.com", 80)));
    }

    @Test
    public void testProxyMatchesWithCidrIncludeAndExclude()
    {
        HttpProxy proxy = new HttpProxy("host", 0);
        proxy.getIncludedAddresses().add("10.0.0.0/8");
        proxy.getExcludedAddresses().add("10.10.0.0/16");

        assertFalse(proxy.matches(new Origin("http", "192.168.1.1", 80)));
        assertTrue(proxy.matches(new Origin("http", "10.0.0.1", 80)));
        assertTrue(proxy.matches(new Origin("http", "10.255.255.255", 80)));
        assertFalse(proxy.matches(new Origin("http", "10.10.0.1", 80)));
        assertFalse(proxy.matches(new Origin("http", "10.10.255.255", 80)));
    }
}
