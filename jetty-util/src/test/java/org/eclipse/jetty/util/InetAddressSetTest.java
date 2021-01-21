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
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.Net;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class InetAddressSetTest
{
    public static Stream<String> loopbacks()
    {
        List<String> loopbacks = new ArrayList<>();

        loopbacks.add("127.0.0.1");
        loopbacks.add("127.0.0.2");

        if (Net.isIpv6InterfaceAvailable())
        {
            loopbacks.add("::1");
            loopbacks.add("::0.0.0.1");
            loopbacks.add("[::1]");
            loopbacks.add("[::0.0.0.1]");
            loopbacks.add("[::ffff:127.0.0.1]");
        }

        return loopbacks.stream();
    }

    @ParameterizedTest
    @MethodSource("loopbacks")
    public void testInetAddressLoopback(String addr) throws Exception
    {
        InetAddress inetAddress = InetAddress.getByName(addr);
        assertNotNull(inetAddress);
        assertTrue(inetAddress.isLoopbackAddress());
    }

    @Test
    public void testSingleton() throws Exception
    {
        InetAddressSet set = new InetAddressSet();

        set.add("webtide.com");
        set.add("1.2.3.4");
        set.add("::abcd");

        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertTrue(set.test(InetAddress.getByName("1.2.3.4")));
        assertTrue(set.test(InetAddress.getByAddress(new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        assertTrue(set.test(InetAddress.getByAddress("hostname", new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
        assertTrue(set.test(InetAddress.getByName("::abcd")));
        assertTrue(set.test(InetAddress.getByName("[::abcd]")));
        assertTrue(set.test(InetAddress.getByName("::ffff:1.2.3.4")));

        assertFalse(set.test(InetAddress.getByName("www.google.com")));
        assertFalse(set.test(InetAddress.getByName("1.2.3.5")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1, (byte)2, (byte)3, (byte)5})));
        assertFalse(set.test(InetAddress.getByAddress("webtide.com", new byte[]{(byte)1, (byte)2, (byte)3, (byte)5})));
        assertFalse(set.test(InetAddress.getByName("::1.2.3.4")));
        assertFalse(set.test(InetAddress.getByName("::1234")));
        assertFalse(set.test(InetAddress.getByName("::abce")));
        assertFalse(set.test(InetAddress.getByName("1::abcd")));
    }

    public static Stream<String> badsingletons()
    {
        List<String> bad = new ArrayList<>();

        bad.add("intentionally invalid hostname");
        bad.add("nonexistentdomain.tld");
        bad.add("1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16");
        bad.add("a.b.c.d");

        bad.add("[::1"); // incomplete
        bad.add("[xxx]"); // not valid octets
        bad.add("[:::1]"); // too many colons

        return bad.stream();
    }

    @ParameterizedTest
    @MethodSource("badsingletons")
    public void testBadSingleton(final String badAddr)
    {
        try
        {
            InetAddress inetAddress = InetAddress.getByName(badAddr);
            Assumptions.assumeTrue(inetAddress == null);
        }
        catch (UnknownHostException expected)
        {
            //noinspection MismatchedQueryAndUpdateOfCollection
            InetAddressSet inetAddressSet = new InetAddressSet();
            IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> inetAddressSet.add(badAddr));
            assertThat(cause.getMessage(), containsString(badAddr));
        }
    }

    @Test
    public void testCIDR() throws Exception
    {
        InetAddressSet set = new InetAddressSet();

        set.add("10.10.0.0/16");
        set.add("192.0.80.0/22");
        set.add("168.0.0.80/30");
        set.add("abcd:ef00::/24");

        assertTrue(set.test(InetAddress.getByName("10.10.0.0")));
        assertTrue(set.test(InetAddress.getByName("10.10.0.1")));
        assertTrue(set.test(InetAddress.getByName("10.10.255.255")));
        assertTrue(set.test(InetAddress.getByName("::ffff:10.10.0.1")));
        assertTrue(set.test(InetAddress.getByName("192.0.80.0")));
        assertTrue(set.test(InetAddress.getByName("192.0.83.1")));
        assertTrue(set.test(InetAddress.getByName("168.0.0.80")));
        assertTrue(set.test(InetAddress.getByName("168.0.0.83")));
        assertTrue(set.test(InetAddress.getByName("abcd:ef00::1")));
        assertTrue(set.test(InetAddress.getByName("abcd:efff::ffff")));

        assertFalse(set.test(InetAddress.getByName("10.11.0.0")));
        assertFalse(set.test(InetAddress.getByName("1.2.3.5")));
        assertFalse(set.test(InetAddress.getByName("192.0.84.1")));
        assertFalse(set.test(InetAddress.getByName("168.0.0.84")));
        assertFalse(set.test(InetAddress.getByName("::10.10.0.1")));
        assertFalse(set.test(InetAddress.getByName("abcd:eeff::1")));
        assertFalse(set.test(InetAddress.getByName("abcd:f000::")));

        set.add("255.255.255.255/32");
        assertTrue(set.test(InetAddress.getByName("255.255.255.255")));
        assertFalse(set.test(InetAddress.getByName("10.11.0.0")));

        set.add("0.0.0.0/0");
        assertTrue(set.test(InetAddress.getByName("10.11.0.0")));

        // test #1664
        set.add("2.144.0.0/14");
        set.add("2.176.0.0/12");
        set.add("5.22.0.0/17");
        set.add("5.22.192.0/19");
        assertTrue(set.test(InetAddress.getByName("2.144.0.1")));
        assertTrue(set.test(InetAddress.getByName("2.176.0.1")));
        assertTrue(set.test(InetAddress.getByName("5.22.0.1")));
        assertTrue(set.test(InetAddress.getByName("5.22.192.1")));
    }

    public static Stream<String> badCidrs()
    {
        List<String> bad = new ArrayList<>();
        bad.add("intentionally invalid hostname/8");
        bad.add("nonexistentdomain.tld/8");
        bad.add("1.2.3.4/-1");
        bad.add("1.2.3.4/xxx");
        bad.add("1.2.3.4/33");
        bad.add("255.255.8.0/16");
        bad.add("255.255.8.1/17");

        if (Net.isIpv6InterfaceAvailable())
        {
            bad.add("[::1]/129");
        }

        return bad.stream();
    }

    @ParameterizedTest
    @MethodSource("badCidrs")
    public void testBadCIDR(String cidr)
    {
        //noinspection MismatchedQueryAndUpdateOfCollection
        InetAddressSet inetAddressSet = new InetAddressSet();

        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> inetAddressSet.add(cidr));
        assertThat(cause.getMessage(), containsString(cidr));
    }

    @Test
    public void testMinMax() throws Exception
    {
        InetAddressSet set = new InetAddressSet();

        set.add("10.0.0.4-10.0.0.6");
        set.add("10.1.0.254-10.1.1.1");

        if (Net.isIpv6InterfaceAvailable())
        {
            set.add("[abcd:ef::fffe]-[abcd:ef::1:1]");
        }

        assertFalse(set.test(InetAddress.getByName("10.0.0.3")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));
        assertFalse(set.test(InetAddress.getByName("10.0.0.7")));

        assertFalse(set.test(InetAddress.getByName("10.1.0.253")));
        assertTrue(set.test(InetAddress.getByName("10.1.0.254")));
        assertTrue(set.test(InetAddress.getByName("10.1.0.255")));
        assertTrue(set.test(InetAddress.getByName("10.1.1.0")));
        assertTrue(set.test(InetAddress.getByName("10.1.1.1")));
        assertFalse(set.test(InetAddress.getByName("10.1.1.2")));

        if (Net.isIpv6InterfaceAvailable())
        {
            assertFalse(set.test(InetAddress.getByName("ABCD:EF::FFFD")));
            assertTrue(set.test(InetAddress.getByName("ABCD:EF::FFFE")));
            assertTrue(set.test(InetAddress.getByName("ABCD:EF::FFFF")));
            assertTrue(set.test(InetAddress.getByName("ABCD:EF::1:0")));
            assertTrue(set.test(InetAddress.getByName("ABCD:EF::1:1")));
            assertFalse(set.test(InetAddress.getByName("ABCD:EF::1:2")));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "10.0.0.0-9.0.0.0",
        "9.0.0.0-[::10.0.0.0]"
    })
    public void testBadMinMax(String bad)
    {
        //noinspection MismatchedQueryAndUpdateOfCollection
        InetAddressSet inetAddressSet = new InetAddressSet();
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> inetAddressSet.add(bad));
        assertThat(cause.getMessage(), containsString(bad));
    }

    @Test
    public void testLegacy() throws Exception
    {
        InetAddressSet set = new InetAddressSet();

        set.add("10.-.245-.-2");
        set.add("11.11.11.127-129");

        assertFalse(set.test(InetAddress.getByName("9.0.245.0")));

        assertTrue(set.test(InetAddress.getByName("10.0.245.0")));
        assertTrue(set.test(InetAddress.getByName("10.0.245.1")));
        assertTrue(set.test(InetAddress.getByName("10.0.245.2")));
        assertFalse(set.test(InetAddress.getByName("10.0.245.3")));

        assertTrue(set.test(InetAddress.getByName("10.255.255.0")));
        assertTrue(set.test(InetAddress.getByName("10.255.255.1")));
        assertTrue(set.test(InetAddress.getByName("10.255.255.2")));
        assertFalse(set.test(InetAddress.getByName("10.255.255.3")));

        assertFalse(set.test(InetAddress.getByName("10.0.244.0")));
        assertFalse(set.test(InetAddress.getByName("10.0.244.1")));
        assertFalse(set.test(InetAddress.getByName("10.0.244.2")));
        assertFalse(set.test(InetAddress.getByName("10.0.244.3")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "9.0-10.0",
        "10.0.0--1.1",
        "10.0.0-256.1"
    })
    public void testBadLegacy(String bad)
    {
        //noinspection MismatchedQueryAndUpdateOfCollection
        InetAddressSet inetAddressSet = new InetAddressSet();
        IllegalArgumentException cause = assertThrows(IllegalArgumentException.class, () -> inetAddressSet.add(bad));
        assertThat(cause.getMessage(), containsString(bad));
    }

    @Test
    public void testRemove() throws Exception
    {
        InetAddressSet set = new InetAddressSet();

        set.add("webtide.com");
        set.add("1.2.3.4");
        set.add("::abcd");
        set.add("10.0.0.4-10.0.0.6");

        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertTrue(set.test(InetAddress.getByName("1.2.3.4")));
        assertTrue(set.test(InetAddress.getByAddress(new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        assertTrue(set.test(InetAddress.getByAddress("hostname", new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        if (Net.isIpv6InterfaceAvailable())
        {
            assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
            assertTrue(set.test(InetAddress.getByName("::abcd")));
            assertTrue(set.test(InetAddress.getByName("[::abcd]")));
            assertTrue(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        }
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));

        set.remove("1.2.3.4");
        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertFalse(set.test(InetAddress.getByName("1.2.3.4")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        assertFalse(set.test(InetAddress.getByAddress("hostname", new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        if (Net.isIpv6InterfaceAvailable())
        {
            assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
            assertTrue(set.test(InetAddress.getByName("::abcd")));
            assertTrue(set.test(InetAddress.getByName("[::abcd]")));
            assertFalse(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        }
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));

        set.removeIf("::abcd"::equals);

        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertFalse(set.test(InetAddress.getByName("1.2.3.4")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        assertFalse(set.test(InetAddress.getByAddress("hostname", new byte[]{(byte)1, (byte)2, (byte)3, (byte)4})));
        if (Net.isIpv6InterfaceAvailable())
        {
            assertFalse(set.test(InetAddress.getByName("::0:0:abcd")));
            assertFalse(set.test(InetAddress.getByName("::abcd")));
            assertFalse(set.test(InetAddress.getByName("[::abcd]")));
            assertFalse(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        }
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));
    }
}
