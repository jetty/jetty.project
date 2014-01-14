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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Before;
import org.junit.Test;


/**
 *
 *
 */
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
        m0=new StringMap<String>();
        m1=new StringMap<String>(false);
        m1.put("abc", "0");

        m5=new StringMap<String>(false);
        m5.put("a", "0");
        m5.put("ab", "1");
        m5.put("abc", "2");
        m5.put("abb", "3");
        m5.put("bbb", "4");

        m5i=new StringMap<String>(true);
        m5i.put("ab", "1");
        m5i.put("abc", "2");
        m5i.put("abb", "3");
    }

    @Test
    public void testSize()
    {
        assertEquals(0, m0.size());
        assertEquals(1, m1.size());
        assertEquals(5, m5.size());
        assertEquals(3, m5i.size());

        m1.remove("abc");
        m5.remove("abc");
        m5.put("bbb","x");
        m5i.put("ABC", "x");
        assertEquals(0, m0.size());
        assertEquals(0, m1.size());
        assertEquals(4, m5.size());
        assertEquals(3, m5i.size());
    }

    @Test
    public void testIsEmpty()
    {
        assertTrue(m0.isEmpty());
        assertFalse(m1.isEmpty());
        assertFalse(m5.isEmpty());
        assertFalse(m5i.isEmpty());
    }

    @Test
    public void testClear()
    {
        m0.clear();
        m1.clear();
        m5.clear();
        m5i.clear();
        assertTrue(m0.isEmpty());
        assertTrue(m1.isEmpty());
        assertTrue(m5.isEmpty());
        assertTrue(m5i.isEmpty());
        assertEquals(null,m1.get("abc"));
        assertEquals(null,m5.get("abc"));
        assertEquals(null,m5i.get("abc"));
    }


    /*
     * Test for Object put(Object, Object)
     */
    @Test
    public void testPutGet()
    {
        assertEquals("2",m5.get("abc"));
        assertEquals(null,m5.get("aBc"));
        assertEquals("2",m5i.get("abc"));
        assertEquals("2",m5i.get("aBc"));

        m5.put("aBc", "x");
        m5i.put("AbC", "x");

        StringBuilder buffer=new StringBuilder();
        buffer.append("aBc");
        assertEquals("2",m5.get("abc"));
        assertEquals("x",m5.get(buffer));
        assertEquals("x",m5i.get((Object)"abc"));
        assertEquals("x",m5i.get("aBc"));


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

        assertEquals(0, m0.size());
        assertEquals(0, m1.size());
        assertEquals(4, m5.size());
        assertEquals(2, m5i.size());

        assertEquals("2",m5.get("abc"));
        assertEquals(null,m5.get("bbb"));
        assertEquals(null,m5i.get("AbC"));
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
        assertEquals(0, es0.size());
        assertEquals(1, es1.size());
        assertEquals(5, es5.size());
    }

    /*
     * Test for boolean containsKey(Object)
     */
    @Test
    public void testContainsKey()
    {
        assertTrue(m5.containsKey("abc"));
        assertTrue(!m5.containsKey("aBc"));
        assertTrue(m5.containsKey("bbb"));
        assertTrue(!m5.containsKey("xyz"));

        assertTrue(m5i.containsKey("abc"));
        assertTrue(m5i.containsKey("aBc"));
        assertTrue(m5i.containsKey("ABC"));
    }

    @Test
    public void testToString()
    {
        assertEquals("{}",m0.toString());
        assertEquals("{abc=0}",m1.toString());
        assertTrue(m5.toString().indexOf("abc=2")>0);
    }

    @Test
    public void testIgnoreCase()
    {
        StringMap<String> map = new StringMap<String>(true);
        map.put("POST","1");
        map.put("HEAD","2");
        map.put("PUT","3");
        map.put("OPTIONS","4");
        map.put("DELETE","5");
        map.put("TRACE","6");
        map.put("CONNECT","7");
        map.put("Upgrade","8");

        assertEquals("1",map.get("POST"));
        assertEquals("1",map.get("pOST"));
        assertEquals("1",map.get("Post"));

        assertEquals("8",map.get("UPGRADE"));
        assertEquals("8",map.get("Upgrade"));
        assertEquals("8",map.get("upgrade"));

    }

}
