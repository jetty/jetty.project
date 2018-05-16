//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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


import org.junit.Test;

import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * Tests for LazySet utility class.
 */
public class LazySetTest
{
    public static final boolean STRICT = false;

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_NullInput_NullItem()
    {
        Object list = LazySet.add(null, null);
        assertNotNull(list);
        assertTrue(list instanceof Set);
        assertEquals(1,LazySet.size(list));
    }

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_NullInput_NonSetItem()
    {
        String item = "a";
        Object list = LazySet.add(null, item);
        assertNotNull(list);
        if(STRICT) {
            assertTrue(list instanceof Set);
        }
        assertEquals(1,LazySet.size(list));
    }

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_NullInput_LazySetItem()
    {
        Object item = LazySet.add(null, "x");
        item = LazySet.add(item,"y");
        item = LazySet.add(item,"z");

        Object list = LazySet.add(null, item);
        assertNotNull(list);
        assertTrue(list instanceof Set);
        assertEquals(1,LazySet.size(list));

        Object val = LazySet.getSet(list).iterator().next();
        assertTrue(val instanceof Set);
    }

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_NonSetInput()
    {
        String input = "a";

        Object list = LazySet.add(input, "b");
        assertNotNull(list);
        assertTrue(list instanceof Set);
        assertEquals(2,LazySet.size(list));
    }

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_LazySetInput()
    {
        Object input = LazySet.add(null, "a");

        Object set = LazySet.add(input, "b");
        assertEquals(2,LazySet.size(set));
        assertEquals("a",LazySet.getSet(set).iterator().next());

        set=LazySet.add(set, "c");
        assertEquals(3,LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("a",iterator.next());
        assertEquals("b",iterator.next());
        assertEquals("c",iterator.next());
    }

    /**
     * Tests for {@link LazySet#add(Object, Object)}
     */
    @Test
    public void testAddObjectObject_GenericSetInput()
    {
        Set<String> input = new HashSet<>();
        input.add("a");

        Object set = LazySet.add(input, "b");
        assertEquals(2,LazySet.size(set));
        assertEquals("a",LazySet.getSet(set).iterator().next());

        set=LazySet.add(set, "c");
        assertEquals(3,LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("a",iterator.next());
        assertEquals("b",iterator.next());
        assertEquals("c",iterator.next());
    }

    /**
     * Test for {@link LazySet#addCollection(Object, Collection)}
     */
    @Test
    public void testAddCollection_NullInput()
    {
        Collection<?> coll = Arrays.asList("a","b","c");

        Object set = LazySet.addCollection(null,coll);
        assertTrue(set instanceof Set);
        assertEquals(3, LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("a",iterator.next());
        assertEquals("b",iterator.next());
        assertEquals("c",iterator.next());
    }

    /**
     * Test for {@link LazySet#addCollection(Object, Collection)}
     */
    @Test
    public void testAddCollection_AllDuplicated()
    {
        Collection<?> coll = Arrays.asList("a","a","a");

        Object set = LazySet.addCollection(null,coll);
        assertTrue(set instanceof Set);
        assertEquals(1, LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("a",iterator.next());
    }    

    /**
     * Test for {@link LazySet#addCollection(Object, Collection)}
     */
    @Test
    public void testAddCollection_NonSetInput()
    {
        Collection<?> coll = Arrays.asList("a","b","c");
        String input = "z";

        Object set = LazySet.addCollection(input,coll);
        assertTrue(set instanceof Set);
        assertEquals(4, LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("z",iterator.next());
        assertEquals("a",iterator.next());
        assertEquals("b",iterator.next());
        assertEquals("c",iterator.next());
    }

    /**
     * Test for {@link LazySet#addCollection(Object, Collection)}
     */
    @Test
    public void testAddCollection_LazySetInput()
    {
        Collection<?> coll = Arrays.asList("a","b","c");

        Object input = LazySet.add(null, "x");
        input = LazySet.add(input, "y");
        input = LazySet.add(input, "z");

        Object set = LazySet.addCollection(input,coll);
        assertTrue(set instanceof Set);
        assertEquals(6, LazySet.size(set));
        Iterator iterator = LazySet.getSet( set ).iterator();
        assertEquals("x",iterator.next());
        assertEquals("y",iterator.next());
        assertEquals("z",iterator.next());
        assertEquals("a",iterator.next());
        assertEquals("b",iterator.next());
        assertEquals("c",iterator.next());
    }

}
