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

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class IncludeExcludeSetTest
{
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
