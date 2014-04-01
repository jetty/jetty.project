//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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

import java.util.Set;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class StringMapTest
{
    StringMap<String> m0;
    StringMap<String> m1;
    StringMap<String> m5;
    StringMap<String> m5i;

    /*
     * @see TestCase#setUp()
     */

    @Before
    public void setUp() throws Exception
    {
        m0=new StringMap<>();
        m1=new StringMap<>(false);
        m1.put("abc", "0");

        m5=new StringMap<>(false);
        m5.put("a", "0");
        m5.put("ab", "1");
        m5.put("abc", "2");
        m5.put("abb", "3");
        m5.put("bbb", "4");

        m5i=new StringMap<>(true);
        m5i.put("ab", "1");
        m5i.put("abc", "2");
        m5i.put("abb", "3");
    }

    @Test
    public void testSize()
    {
        Assert.assertEquals(0, m0.size());
        Assert.assertEquals(1, m1.size());
        Assert.assertEquals(5, m5.size());
        Assert.assertEquals(3, m5i.size());

        m1.remove("abc");
        m5.remove("abc");
        m5.put("bbb","x");
        m5i.put("ABC", "x");
        Assert.assertEquals(0, m0.size());
        Assert.assertEquals(0, m1.size());
        Assert.assertEquals(4, m5.size());
        Assert.assertEquals(3, m5i.size());
    }

    @Test
    public void testIsEmpty()
    {
        Assert.assertTrue(m0.isEmpty());
        Assert.assertFalse(m1.isEmpty());
        Assert.assertFalse(m5.isEmpty());
        Assert.assertFalse(m5i.isEmpty());
    }

    @Test
    public void testClear()
    {
        m0.clear();
        m1.clear();
        m5.clear();
        m5i.clear();
        Assert.assertTrue(m0.isEmpty());
        Assert.assertTrue(m1.isEmpty());
        Assert.assertTrue(m5.isEmpty());
        Assert.assertTrue(m5i.isEmpty());
        Assert.assertEquals(null, m1.get("abc"));
        Assert.assertEquals(null, m5.get("abc"));
        Assert.assertEquals(null, m5i.get("abc"));
    }


    /*
     * Test for Object put(Object, Object)
     */
    @Test
    public void testPutGet()
    {
        Assert.assertEquals("2", m5.get("abc"));
        Assert.assertEquals(null, m5.get("aBc"));
        Assert.assertEquals("2", m5i.get("abc"));
        Assert.assertEquals("2", m5i.get("aBc"));

        m5.put("aBc", "x");
        m5i.put("AbC", "x");

        StringBuilder buffer=new StringBuilder();
        buffer.append("aBc");
        Assert.assertEquals("2", m5.get("abc"));
        Assert.assertEquals("x", m5.get(buffer));
        Assert.assertEquals("x", m5i.get((Object)"abc"));
        Assert.assertEquals("x", m5i.get("aBc"));


    }

    /*
     * Test for Object remove(Object)
     */
    @Test
    public void testRemove()
    {
        m0.remove("abc");
        m1.remove("abc");
        m5.remove("aBc");
        m5.remove("bbb");
        m5i.remove("aBc");

        Assert.assertEquals(0, m0.size());
        Assert.assertEquals(0, m1.size());
        Assert.assertEquals(4, m5.size());
        Assert.assertEquals(2, m5i.size());

        Assert.assertEquals("2", m5.get("abc"));
        Assert.assertEquals(null, m5.get("bbb"));
        Assert.assertEquals(null, m5i.get("AbC"));
    }

    /*
     * Test for Set entrySet()
     */
    @Test
    public void testEntrySet()
    {
        Set es0=m0.entrySet();
        Set es1=m1.entrySet();
        Set es5=m5.entrySet();
        Assert.assertEquals(0, es0.size());
        Assert.assertEquals(1, es1.size());
        Assert.assertEquals(5, es5.size());
    }

    /*
     * Test for boolean containsKey(Object)
     */
    @Test
    public void testContainsKey()
    {
        Assert.assertTrue(m5.containsKey("abc"));
        Assert.assertTrue(!m5.containsKey("aBc"));
        Assert.assertTrue(m5.containsKey("bbb"));
        Assert.assertTrue(!m5.containsKey("xyz"));

        Assert.assertTrue(m5i.containsKey("abc"));
        Assert.assertTrue(m5i.containsKey("aBc"));
        Assert.assertTrue(m5i.containsKey("ABC"));
    }

    @Test
    public void testToString()
    {
        Assert.assertEquals("{}", m0.toString());
        Assert.assertEquals("{abc=0}", m1.toString());
        Assert.assertTrue(m5.toString().indexOf("abc=2") > 0);
    }

    @Test
    public void testIgnoreCase()
    {
        StringMap<String> map = new StringMap<>(true);
        map.put("POST","1");
        map.put("HEAD","2");
        map.put("PUT","3");
        map.put("OPTIONS","4");
        map.put("DELETE","5");
        map.put("TRACE","6");
        map.put("CONNECT","7");
        map.put("Upgrade","8");

        Assert.assertEquals("1", map.get("POST"));
        Assert.assertEquals("1", map.get("pOST"));
        Assert.assertEquals("1", map.get("Post"));

        Assert.assertEquals("8", map.get("UPGRADE"));
        Assert.assertEquals("8", map.get("Upgrade"));
        Assert.assertEquals("8", map.get("upgrade"));

    }

}
