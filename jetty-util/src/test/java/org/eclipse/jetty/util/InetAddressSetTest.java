//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import static org.junit.Assert.*;

import java.net.InetAddress;
import java.util.Iterator;

import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Test;

public class InetAddressSetTest
{
    @Test
    public void testInetAddress() throws Exception
    {
        assertTrue(InetAddress.getByName("127.0.0.1").isLoopbackAddress());
        assertTrue(InetAddress.getByName("::1").isLoopbackAddress());
        assertTrue(InetAddress.getByName("::0.0.0.1").isLoopbackAddress());
        assertTrue(InetAddress.getByName("[::1]").isLoopbackAddress());
        assertTrue(InetAddress.getByName("[::0.0.0.1]").isLoopbackAddress());
        assertTrue(InetAddress.getByName("[::ffff:127.0.0.1]").isLoopbackAddress());
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
        assertTrue(set.test(InetAddress.getByAddress(new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertTrue(set.test(InetAddress.getByAddress("hostname",new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
        assertTrue(set.test(InetAddress.getByName("::abcd")));
        assertTrue(set.test(InetAddress.getByName("[::abcd]")));
        assertTrue(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        
        assertFalse(set.test(InetAddress.getByName("www.google.com")));
        assertFalse(set.test(InetAddress.getByName("1.2.3.5")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1,(byte)2,(byte)3,(byte)5})));
        assertFalse(set.test(InetAddress.getByAddress("webtide.com",new byte[]{(byte)1,(byte)2,(byte)3,(byte)5})));
        assertFalse(set.test(InetAddress.getByName("::1.2.3.4")));
        assertFalse(set.test(InetAddress.getByName("::1234")));
        assertFalse(set.test(InetAddress.getByName("::abce")));
        assertFalse(set.test(InetAddress.getByName("1::abcd")));
    }
    
    @Test
    public void testBadSingleton() throws Exception
    {
        String[] tests = new String[]
        {
            "unknown",
            "1.2.3.4.5.6.7.8.9.10.11.12.13.14.15.16",
            "a.b.c.d",
            "[::1",
            "[xxx]",
            "[:::1]",
        };

        InetAddressSet set = new InetAddressSet();
        
        for (String t:tests)
        {
            try
            {
                set.add(t);
                fail(t);
            }
            catch(IllegalArgumentException e)
            {
                Assert.assertThat(e.getMessage(),Matchers.containsString(t));
            }
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
    }

    @Test
    public void testBadCIDR() throws Exception
    {
        String[] tests = new String[]
        {
            "unknown/8",
            "1.2.3.4/-1",
            "1.2.3.4/xxx",
            "1.2.3.4/33",
            "255.255.8.0/16",
            "255.255.8.1/17",
            "[::1]/129",
        };

        InetAddressSet set = new InetAddressSet();
        
        for (String t:tests)
        {
            try
            {
                set.add(t);
                fail(t);
            }
            catch(IllegalArgumentException e)
            {
                Assert.assertThat(e.getMessage(),Matchers.containsString(t));
            }
        }
    }

    @Test
    public void testMinMax() throws Exception
    {
        InetAddressSet set = new InetAddressSet();
        
        set.add("10.0.0.4-10.0.0.6");
        set.add("10.1.0.254-10.1.1.1");
        set.add("[abcd:ef::fffe]-[abcd:ef::1:1]");

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
        
        assertFalse(set.test(InetAddress.getByName("ABCD:EF::FFFD")));
        assertTrue(set.test(InetAddress.getByName("ABCD:EF::FFFE")));
        assertTrue(set.test(InetAddress.getByName("ABCD:EF::FFFF")));
        assertTrue(set.test(InetAddress.getByName("ABCD:EF::1:0")));
        assertTrue(set.test(InetAddress.getByName("ABCD:EF::1:1")));
        assertFalse(set.test(InetAddress.getByName("ABCD:EF::1:2")));
    }
    
    @Test
    public void testBadMinMax() throws Exception
    {
        String[] tests = new String[]
        {
            "10.0.0.0-9.0.0.0",
            "9.0.0.0-[::10.0.0.0]",
        };

        InetAddressSet set = new InetAddressSet();
        
        for (String t:tests)
        {
            try
            {
                set.add(t);
                fail(t);
            }
            catch(IllegalArgumentException e)
            {
                Assert.assertThat(e.getMessage(),Matchers.containsString(t));
            }
        }
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

    @Test
    public void testBadLegacy() throws Exception
    {
        String[] tests = new String[]
        {
            "9.0-10.0",
            "10.0.0--1.1",
            "10.0.0-256.1",
        };

        InetAddressSet set = new InetAddressSet();
        
        for (String t:tests)
        {
            try
            {
                set.add(t);
                fail(t);
            }
            catch(IllegalArgumentException e)
            {
                Assert.assertThat(e.getMessage(),Matchers.containsString(t));
            }
        }
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
        assertTrue(set.test(InetAddress.getByAddress(new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertTrue(set.test(InetAddress.getByAddress("hostname",new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
        assertTrue(set.test(InetAddress.getByName("::abcd")));
        assertTrue(set.test(InetAddress.getByName("[::abcd]")));
        assertTrue(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));
        
        set.remove("1.2.3.4");
        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertFalse(set.test(InetAddress.getByName("1.2.3.4")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertFalse(set.test(InetAddress.getByAddress("hostname",new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertTrue(set.test(InetAddress.getByName("::0:0:abcd")));
        assertTrue(set.test(InetAddress.getByName("::abcd")));
        assertTrue(set.test(InetAddress.getByName("[::abcd]")));
        assertFalse(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));
        
        for (Iterator<String> i=set.iterator();i.hasNext();)
        {
            if ("::abcd".equals(i.next()))
                i.remove();
        }
        assertTrue(set.test(InetAddress.getByName("webtide.com")));
        assertTrue(set.test(InetAddress.getByName(InetAddress.getByName("webtide.com").getHostAddress())));
        assertFalse(set.test(InetAddress.getByName("1.2.3.4")));
        assertFalse(set.test(InetAddress.getByAddress(new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertFalse(set.test(InetAddress.getByAddress("hostname",new byte[]{(byte)1,(byte)2,(byte)3,(byte)4})));
        assertFalse(set.test(InetAddress.getByName("::0:0:abcd")));
        assertFalse(set.test(InetAddress.getByName("::abcd")));
        assertFalse(set.test(InetAddress.getByName("[::abcd]")));
        assertFalse(set.test(InetAddress.getByName("::ffff:1.2.3.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.4")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.5")));
        assertTrue(set.test(InetAddress.getByName("10.0.0.6")));
        
    }
}
