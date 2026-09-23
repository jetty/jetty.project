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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class HostPortSetTest
{
    @Test
    public void testAddAndTest()
    {
        HostPortSet set = new HostPortSet();

        set.add("example.com");
        set.add("*.internal.corp");
        set.add("192.168.0.0/16");

        assertTrue(set.test(new HostPort("example.com", 80)));
        assertTrue(set.test(new HostPort("api.internal.corp", 8080)));
        assertTrue(set.test(new HostPort("192.168.1.100", 443)));

        assertFalse(set.test(new HostPort("other.com", 80)));
        assertFalse(set.test(new HostPort("10.0.0.1", 80)));
    }

    @Test
    public void testRemove()
    {
        HostPortSet set = new HostPortSet();

        set.add("example.com");
        set.add("localhost");

        assertTrue(set.test(new HostPort("example.com", 80)));
        assertTrue(set.test(new HostPort("localhost", 8080)));

        set.remove("example.com");

        assertFalse(set.test(new HostPort("example.com", 80)));
        assertTrue(set.test(new HostPort("localhost", 8080)));
    }

    @Test
    public void testSize()
    {
        HostPortSet set = new HostPortSet();

        assertEquals(0, set.size());

        set.add("example.com");
        assertEquals(1, set.size());

        set.add("localhost");
        assertEquals(2, set.size());

        set.add("example.com");
        assertEquals(2, set.size());

        set.remove("example.com");
        assertEquals(1, set.size());
    }

    @Test
    public void testClear()
    {
        HostPortSet set = new HostPortSet();

        set.add("example.com");
        set.add("localhost");

        assertEquals(2, set.size());

        set.clear();

        assertEquals(0, set.size());
        assertFalse(set.test(new HostPort("example.com", 80)));
    }

    @Test
    public void testContains()
    {
        HostPortSet set = new HostPortSet();

        set.add("example.com");

        assertTrue(set.contains("example.com"));
        assertFalse(set.contains("localhost"));
    }

    @Test
    public void testIterator()
    {
        HostPortSet set = new HostPortSet();

        set.add("example.com");
        set.add("localhost");

        int count = 0;
        for (String pattern : set)
        {
            assertTrue(pattern.equals("example.com") || pattern.equals("localhost"));
            count++;
        }
        assertEquals(2, count);
    }

    @Test
    public void testNullHostPort()
    {
        HostPortSet set = new HostPortSet();
        set.add("example.com");

        assertFalse(set.test(null));
    }

    @Test
    public void testWithIncludeExcludeSet()
    {
        IncludeExcludeSet<String, HostPort> includeExclude = new IncludeExcludeSet<>(HostPortSet.class);

        includeExclude.include("*.example.com");
        includeExclude.exclude("admin.example.com");

        // Matches include pattern
        assertTrue(includeExclude.test(new HostPort("www.example.com", 80)));
        assertTrue(includeExclude.test(new HostPort("api.example.com", 443)));

        // Excluded by exclude pattern
        assertFalse(includeExclude.test(new HostPort("admin.example.com", 80)));

        // Doesn't match include pattern
        assertFalse(includeExclude.test(new HostPort("other.com", 80)));
    }

    @Test
    public void testWithIncludeExcludeSetCidr()
    {
        IncludeExcludeSet<String, HostPort> includeExclude = new IncludeExcludeSet<>(HostPortSet.class);

        includeExclude.include("10.0.0.0/8");
        includeExclude.exclude("10.10.0.0/16");

        // Matches include CIDR
        assertTrue(includeExclude.test(new HostPort("10.0.0.1", 80)));
        assertTrue(includeExclude.test(new HostPort("10.255.255.255", 80)));

        // Excluded by narrower CIDR
        assertFalse(includeExclude.test(new HostPort("10.10.0.1", 80)));
        assertFalse(includeExclude.test(new HostPort("10.10.255.255", 80)));

        // Outside include range
        assertFalse(includeExclude.test(new HostPort("192.168.1.1", 80)));
    }

    @Test
    public void testEmptyIncludesAllowsAll()
    {
        IncludeExcludeSet<String, HostPort> includeExclude = new IncludeExcludeSet<>(HostPortSet.class);

        // No includes - should allow all
        assertTrue(includeExclude.test(new HostPort("example.com", 80)));
        assertTrue(includeExclude.test(new HostPort("any.host", 443)));

        // Add exclude
        includeExclude.exclude("blocked.com");

        // Still allows others
        assertTrue(includeExclude.test(new HostPort("example.com", 80)));

        // But blocks excluded
        assertFalse(includeExclude.test(new HostPort("blocked.com", 80)));
    }
}
