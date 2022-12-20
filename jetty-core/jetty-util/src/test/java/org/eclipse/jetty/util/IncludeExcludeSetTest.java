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

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IncludeExcludeSetTest
{
    @Test
    public void testEmpty()
    {
        IncludeExcludeSet<String, String> set = new IncludeExcludeSet<>();
        assertTrue(set.test("foo"));

        assertTrue(set.isEmpty());

        assertFalse(set.hasIncludes());
        assertFalse(set.hasExcludes());

        // Set is empty, so it's neither included nor excluded
        assertNull(set.isIncludedAndNotExcluded("foo"));
    }

    @Test
    public void testOnlyIncludes()
    {
        IncludeExcludeSet<String, String> set = new IncludeExcludeSet<>();
        set.getIncluded().add("foo");
        set.include("bar");
        set.include("a", "b", "c");

        assertTrue(set.test("foo"));
        assertTrue(set.test("bar"));
        assertFalse(set.test("zed"));

        assertTrue(set.hasIncludes());
        assertFalse(set.hasExcludes());

        // "foo" is included
        assertEquals(set.isIncludedAndNotExcluded("foo"), Boolean.TRUE);
    }

    @Test
    public void testOnlyExcludes()
    {
        IncludeExcludeSet<String, String> set = new IncludeExcludeSet<>();
        set.getExcluded().add("foo");
        set.exclude("bar");
        set.exclude("a", "b", "c");

        assertFalse(set.test("foo"));
        assertFalse(set.test("bar"));
        assertTrue(set.test("zed"));

        assertFalse(set.hasIncludes());
        assertTrue(set.hasExcludes());

        // "foo" is excluded
        assertEquals(set.isIncludedAndNotExcluded("foo"), Boolean.FALSE);
    }

    @Test
    public void testIncludeAndExclude()
    {
        IncludeExcludeSet<String, String> set = new IncludeExcludeSet<>();
        set.include("foo");
        set.exclude("bar");

        assertTrue(set.test("foo")); // specifically included
        assertFalse(set.test("bar")); // specifically excluded
        assertFalse(set.test("zed")); // not in includes nor excludes

        assertTrue(set.hasIncludes());
        assertTrue(set.hasExcludes());

        // "foo" is included
        assertEquals(set.isIncludedAndNotExcluded("foo"), Boolean.TRUE);

        // "bar" is included
        assertEquals(set.isIncludedAndNotExcluded("bar"), Boolean.FALSE);

        // "zed" is neither included nor excluded
        assertNull(set.isIncludedAndNotExcluded("zed"));
    }

    @Test
    public void testWithInetAddressSet() throws Exception
    {
        IncludeExcludeSet<String, InetAddress> set = new IncludeExcludeSet<>(InetAddressSet.class);
        assertTrue(set.test(InetAddress.getByName("192.168.0.1")));

        set.include("10.10.0.0/16");
        assertFalse(set.test(InetAddress.getByName("192.168.0.1")));
        assertTrue(set.test(InetAddress.getByName("10.10.128.1")));

        set.exclude("[::ffff:10.10.128.1]");
        assertFalse(set.test(InetAddress.getByName("10.10.128.1")));

        set.include("[ffff:ff00::]/24");
        assertTrue(set.test(InetAddress.getByName("ffff:ff00::1")));
        assertTrue(set.test(InetAddress.getByName("ffff:ff00::42")));

        set.exclude("[ffff:ff00::42]");
        assertTrue(set.test(InetAddress.getByName("ffff:ff00::41")));
        assertFalse(set.test(InetAddress.getByName("ffff:ff00::42")));
        assertTrue(set.test(InetAddress.getByName("ffff:ff00::43")));

        set.include("192.168.0.0-192.168.255.128");
        assertTrue(set.test(InetAddress.getByName("192.168.0.1")));
        assertTrue(set.test(InetAddress.getByName("192.168.254.255")));
        assertFalse(set.test(InetAddress.getByName("192.168.255.255")));
    }
}
