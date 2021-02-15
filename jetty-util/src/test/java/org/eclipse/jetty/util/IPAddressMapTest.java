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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class IPAddressMapTest
{
    @Test
    public void testOneAddress()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("10.5.2.1", "1");

        assertNotNull(map.match("10.5.2.1"));

        assertNull(map.match("101.5.2.1"));
        assertNull(map.match("10.15.2.1"));
        assertNull(map.match("10.5.22.1"));
        assertNull(map.match("10.5.2.0"));
    }

    @Test
    public void testOneRange()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("1-15.16-31.32-63.64-127", "1");

        assertNotNull(map.match("7.23.39.71"));
        assertNotNull(map.match("1.16.32.64"));
        assertNotNull(map.match("15.31.63.127"));

        assertNull(map.match("16.32.64.128"));
        assertNull(map.match("1.16.32.63"));
        assertNull(map.match("1.16.31.64"));
        assertNull(map.match("1.15.32.64"));
        assertNull(map.match("0.16.32.64"));
    }

    @Test
    public void testOneMissing()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("10.5.2.", "1");

        assertNotNull(map.match("10.5.2.0"));
        assertNotNull(map.match("10.5.2.128"));
        assertNotNull(map.match("10.5.2.255"));
    }

    @Test
    public void testTwoMissing()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("10.5.", "1");

        assertNotNull(map.match("10.5.2.0"));
        assertNotNull(map.match("10.5.2.128"));
        assertNotNull(map.match("10.5.2.255"));
        assertNotNull(map.match("10.5.0.1"));
        assertNotNull(map.match("10.5.128.1"));
        assertNotNull(map.match("10.5.255.1"));
    }

    @Test
    public void testThreeMissing()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("10.", "1");

        assertNotNull(map.match("10.5.2.0"));
        assertNotNull(map.match("10.5.2.128"));
        assertNotNull(map.match("10.5.2.255"));
        assertNotNull(map.match("10.5.0.1"));
        assertNotNull(map.match("10.5.128.1"));
        assertNotNull(map.match("10.5.255.1"));
        assertNotNull(map.match("10.0.1.1"));
        assertNotNull(map.match("10.128.1.1"));
        assertNotNull(map.match("10.255.1.1"));
    }

    @Test
    public void testOneMixed()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("0-15,21.10,16-31.0-15,32-63.-95,128-", "1");

        assertNotNull(map.match("7.23.39.46"));
        assertNotNull(map.match("10.20.10.150"));
        assertNotNull(map.match("21.10.32.255"));
        assertNotNull(map.match("21.10.15.0"));

        assertNull(map.match("16.15.20.100"));
        assertNull(map.match("15.10.63.100"));
        assertNull(map.match("15.10.64.128"));
        assertNull(map.match("15.11.32.95"));
        assertNull(map.match("16.31.63.128"));
    }

    @Test
    public void testManyMixed()
    {
        IPAddressMap<String> map = new IPAddressMap<>();

        map.put("10.5.2.1", "1");
        map.put("1-15.16-31.32-63.64-127", "2");
        map.put("1-15,21.10,16-31.0-15,32-63.-55,195-", "3");
        map.put("44.99.99.", "4");
        map.put("55.99.", "5");
        map.put("66.", "6");

        assertEquals("1", map.match("10.5.2.1"));

        assertEquals("2", map.match("7.23.39.71"));
        assertEquals("2", map.match("1.16.32.64"));
        assertEquals("2", map.match("15.31.63.127"));

        assertEquals("3", map.match("7.23.39.46"));
        assertEquals("3", map.match("10.20.10.200"));
        assertEquals("3", map.match("21.10.32.255"));
        assertEquals("3", map.match("21.10.15.0"));

        assertEquals("4", map.match("44.99.99.0"));
        assertEquals("5", map.match("55.99.128.1"));
        assertEquals("6", map.match("66.255.1.1"));

        assertNull(map.match("101.5.2.1"));
        assertNull(map.match("10.15.2.1"));
        assertNull(map.match("10.5.22.1"));
        assertNull(map.match("10.5.2.0"));

        assertNull(map.match("16.32.64.96"));
        assertNull(map.match("1.16.32.194"));
        assertNull(map.match("1.16.31.64"));
        assertNull(map.match("1.15.32.64"));
        assertNull(map.match("0.16.32.64"));

        assertNull(map.match("16.15.20.100"));
        assertNull(map.match("15.10.63.100"));
        assertNull(map.match("15.10.64.128"));
        assertNull(map.match("15.11.32.95"));
        assertNull(map.match("16.31.63.128"));
    }
}
