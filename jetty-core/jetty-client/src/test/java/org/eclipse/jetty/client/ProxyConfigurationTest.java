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
}
