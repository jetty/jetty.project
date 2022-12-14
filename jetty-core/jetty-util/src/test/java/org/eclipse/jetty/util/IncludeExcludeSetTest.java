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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IncludeExcludeSetTest
{
    @Test
    public void testEmptySet()
    {
        IncludeExcludeSet<String, String> empty = new IncludeExcludeSet<>();

        assertTrue(empty.test("abc"));
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
