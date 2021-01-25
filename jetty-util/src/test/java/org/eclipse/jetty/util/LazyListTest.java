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

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Tests for LazyList utility class.
 */
public class LazyListTest
{
    public static final boolean STRICT = false;

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectNullInputNullItem()
    {
        Object list = LazyList.add(null, null);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectNullInputNonListItem()
    {
        String item = "a";
        Object list = LazyList.add(null, item);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectNullInputLazyListItem()
    {
        Object item = LazyList.add(null, "x");
        item = LazyList.add(item, "y");
        item = LazyList.add(item, "z");

        Object list = LazyList.add(null, item);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));

        Object val = LazyList.get(list, 0);
        assertTrue(val instanceof List);
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectNonListInput()
    {
        String input = "a";

        Object list = LazyList.add(input, "b");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(2, LazyList.size(list));
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectLazyListInput()
    {
        Object input = LazyList.add(null, "a");

        Object list = LazyList.add(input, "b");
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");

        list = LazyList.add(list, "c");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");

        Object list = LazyList.add(input, "b");
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");

        list = LazyList.add(list, "c");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link LazyList#add(Object, Object)}
     */
    @Test
    public void testAddObjectObjectAddNull()
    {
        Object list = null;
        list = LazyList.add(list, null);
        assertEquals(1, LazyList.size(list));
        assertNull(LazyList.get(list, 0));

        list = "a";
        list = LazyList.add(list, null);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertNull(LazyList.get(list, 1));

        list = LazyList.add(list, null);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertNull(LazyList.get(list, 1));
        assertNull(LazyList.get(list, 2));
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNullInputNullItem()
    {
        Object list = LazyList.add(null, 0, null);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNullInputNonListItem()
    {
        String item = "a";
        Object list = LazyList.add(null, 0, item);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNullInputNonListItem2()
    {
        assumeTrue(STRICT); // Only run in STRICT mode.

        String item = "a";
        // Test branch of logic "index>0"
        Object list = LazyList.add(null, 1, item); // Always throws exception?
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNullInputLazyListItem()
    {
        Object item = LazyList.add(null, "x");
        item = LazyList.add(item, "y");
        item = LazyList.add(item, "z");

        Object list = LazyList.add(null, 0, item);
        assertNotNull(list);
        assertEquals(1, LazyList.size(list));

        Object val = LazyList.get(list, 0);
        assertTrue(val instanceof List);
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNullInputGenericListItem()
    {
        List<String> item = new ArrayList<String>();
        item.add("a");

        Object list = LazyList.add(null, 0, item);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNonListInputNullItem()
    {
        String input = "a";

        Object list = LazyList.add(input, 0, null);
        assertNotNull(list);
        assertEquals(2, LazyList.size(list));
        assertNull(LazyList.get(list, 0));
        assertEquals(LazyList.get(list, 1), "a");
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectNonListInputNonListItem()
    {
        String input = "a";
        String item = "b";

        Object list = LazyList.add(input, 0, item);
        assertNotNull(list);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "b");
        assertEquals(LazyList.get(list, 1), "a");
    }

    /**
     * Test for {@link LazyList#add(Object, int, Object)}
     */
    @Test
    public void testAddObjectIntObjectLazyListInput()
    {
        Object list = LazyList.add(null, "c"); // [c]
        list = LazyList.add(list, 0, "a"); // [a, c]
        list = LazyList.add(list, 1, "b"); // [a, b, c]
        list = LazyList.add(list, 3, "d"); // [a, b, c, d]

        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
        assertEquals(LazyList.get(list, 3), "d");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionNullInput()
    {
        Collection<?> coll = Arrays.asList("a", "b", "c");

        Object list = LazyList.addCollection(null, coll);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionNonListInput()
    {
        Collection<?> coll = Arrays.asList("a", "b", "c");
        String input = "z";

        Object list = LazyList.addCollection(input, coll);
        assertTrue(list instanceof List);
        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "z");
        assertEquals(LazyList.get(list, 1), "a");
        assertEquals(LazyList.get(list, 2), "b");
        assertEquals(LazyList.get(list, 3), "c");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionLazyListInput()
    {
        Collection<?> coll = Arrays.asList("a", "b", "c");

        Object input = LazyList.add(null, "x");
        input = LazyList.add(input, "y");
        input = LazyList.add(input, "z");

        Object list = LazyList.addCollection(input, coll);
        assertTrue(list instanceof List);
        assertEquals(6, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
        assertEquals(LazyList.get(list, 4), "b");
        assertEquals(LazyList.get(list, 5), "c");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionGenericListInput()
    {
        Collection<?> coll = Arrays.asList("a", "b", "c");

        List<String> input = new ArrayList<String>();
        input.add("x");
        input.add("y");
        input.add("z");

        Object list = LazyList.addCollection(input, coll);
        assertTrue(list instanceof List);
        assertEquals(6, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
        assertEquals(LazyList.get(list, 4), "b");
        assertEquals(LazyList.get(list, 5), "c");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionSequential()
    {
        Collection<?> coll = Arrays.asList("a", "b");

        Object list = null;
        list = LazyList.addCollection(list, coll);
        list = LazyList.addCollection(list, coll);

        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "a");
        assertEquals(LazyList.get(list, 3), "b");
    }

    /**
     * Test for {@link LazyList#addCollection(Object, java.util.Collection)}
     */
    @Test
    public void testAddCollectionGenericListInput2()
    {
        List<String> l = new ArrayList<String>();
        l.add("a");
        l.add("b");

        Object list = null;
        list = LazyList.addCollection(list, l);
        list = LazyList.addCollection(list, l);

        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "a");
        assertEquals(LazyList.get(list, 3), "b");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNullInputNullArray()
    {
        String[] arr = null;
        Object list = LazyList.addArray(null, arr);
        assertNull(list);
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNullInputEmptyArray()
    {
        String[] arr = new String[0];
        Object list = LazyList.addArray(null, arr);
        if (STRICT)
        {
            assertNotNull(list);
            assertTrue(list instanceof List);
        }
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNullInputSingleArray()
    {
        String[] arr = new String[]{"a"};
        Object list = LazyList.addArray(null, arr);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNullInputArray()
    {
        String[] arr = new String[]{"a", "b", "c"};
        Object list = LazyList.addArray(null, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNonListInputNullArray()
    {
        String input = "z";
        String[] arr = null;
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNonListInputEmptyArray()
    {
        String input = "z";
        String[] arr = new String[0];
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNonListInputSingleArray()
    {
        String input = "z";
        String[] arr = new String[]{"a"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "z");
        assertEquals(LazyList.get(list, 1), "a");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayNonListInputArray()
    {
        String input = "z";
        String[] arr = new String[]{"a", "b", "c"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "z");
        assertEquals(LazyList.get(list, 1), "a");
        assertEquals(LazyList.get(list, 2), "b");
        assertEquals(LazyList.get(list, 3), "c");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayLazyListInputNullArray()
    {
        Object input = LazyList.add(null, "x");
        input = LazyList.add(input, "y");
        input = LazyList.add(input, "z");

        String[] arr = null;
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayLazyListInputEmptyArray()
    {
        Object input = LazyList.add(null, "x");
        input = LazyList.add(input, "y");
        input = LazyList.add(input, "z");

        String[] arr = new String[0];
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayLazyListInputSingleArray()
    {
        Object input = LazyList.add(null, "x");
        input = LazyList.add(input, "y");
        input = LazyList.add(input, "z");

        String[] arr = new String[]{"a"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayLazyListInputArray()
    {
        Object input = LazyList.add(null, "x");
        input = LazyList.add(input, "y");
        input = LazyList.add(input, "z");

        String[] arr = new String[]{"a", "b", "c"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(6, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
        assertEquals(LazyList.get(list, 4), "b");
        assertEquals(LazyList.get(list, 5), "c");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayGenericListInputNullArray()
    {
        List<String> input = new ArrayList<String>();
        input.add("x");
        input.add("y");
        input.add("z");

        String[] arr = null;
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayGenericListInputEmptyArray()
    {
        List<String> input = new ArrayList<String>();
        input.add("x");
        input.add("y");
        input.add("z");

        String[] arr = new String[0];
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayGenericListInputSingleArray()
    {
        List<String> input = new ArrayList<String>();
        input.add("x");
        input.add("y");
        input.add("z");

        String[] arr = new String[]{"a"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(4, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
    }

    /**
     * Tests for {@link LazyList#addArray(Object, Object[])}
     */
    @Test
    public void testAddArrayGenericListInputArray()
    {
        List<String> input = new ArrayList<String>();
        input.add("x");
        input.add("y");
        input.add("z");

        String[] arr = new String[]{"a", "b", "c"};
        Object list = LazyList.addArray(input, arr);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(6, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "x");
        assertEquals(LazyList.get(list, 1), "y");
        assertEquals(LazyList.get(list, 2), "z");
        assertEquals(LazyList.get(list, 3), "a");
        assertEquals(LazyList.get(list, 4), "b");
        assertEquals(LazyList.get(list, 5), "c");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeNullInput()
    {
        Object list = LazyList.ensureSize(null, 10);
        assertNotNull(list);
        assertTrue(list instanceof List);
        // Not possible to test for List capacity value.
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeNonListInput()
    {
        String input = "a";
        Object list = LazyList.ensureSize(input, 10);
        assertNotNull(list);
        assertTrue(list instanceof List);
        // Not possible to test for List capacity value.
        assertEquals(1, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");

        Object list = LazyList.ensureSize(input, 10);
        assertNotNull(list);
        assertTrue(list instanceof List);
        // Not possible to test for List capacity value.
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");

        Object list = LazyList.ensureSize(input, 10);
        assertNotNull(list);
        assertTrue(list instanceof List);
        // Not possible to test for List capacity value.
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeGenericListInputLinkedList()
    {
        assumeTrue(STRICT); // Only run in STRICT mode.

        // Using LinkedList concrete type as LazyList internal
        // implementation does not look for this specifically.
        List<String> input = new LinkedList<String>();
        input.add("a");
        input.add("b");

        Object list = LazyList.ensureSize(input, 10);
        assertNotNull(list);
        assertTrue(list instanceof List);
        // Not possible to test for List capacity value.
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeGrowth()
    {
        List<String> l = new ArrayList<String>();
        l.add("a");
        l.add("b");
        l.add("c");

        // NOTE: Testing for object equality might be viewed as
        //       fragile by most developers, however, for this
        //       specific implementation, we don't want the
        //       provided list to change if the size requirements
        //       have been met.

        // Trigger growth
        Object ret = LazyList.ensureSize(l, 10);
        assertTrue(ret != l, "Should have returned a new list object");

        // Growth not neeed.
        ret = LazyList.ensureSize(l, 1);
        assertTrue(ret == l, "Should have returned same list object");
    }

    /**
     * Tests for {@link LazyList#ensureSize(Object, int)}
     */
    @Test
    public void testEnsureSizeGrowthLinkedList()
    {
        assumeTrue(STRICT); // Only run in STRICT mode.

        // Using LinkedList concrete type as LazyList internal
        // implementation has not historically looked for this
        // specifically.
        List<String> l = new LinkedList<String>();
        l.add("a");
        l.add("b");
        l.add("c");

        // NOTE: Testing for object equality might be viewed as
        //       fragile by most developers, however, for this
        //       specific implementation, we don't want the
        //       provided list to change if the size requirements
        //       have been met.

        // Trigger growth
        Object ret = LazyList.ensureSize(l, 10);
        assertTrue(ret != l, "Should have returned a new list object");

        // Growth not neeed.
        ret = LazyList.ensureSize(l, 1);
        assertTrue(ret == l, "Should have returned same list object");
    }

    /**
     * Test for {@link LazyList#remove(Object, Object)}
     */
    @Test
    public void testRemoveObjectObjectNullInput()
    {
        Object input = null;

        assertNull(LazyList.remove(input, null));
        assertNull(LazyList.remove(input, "a"));
        assertNull(LazyList.remove(input, new ArrayList<Object>()));
        assertNull(LazyList.remove(input, Integer.valueOf(42)));
    }

    /**
     * Test for {@link LazyList#remove(Object, Object)}
     */
    @Test
    public void testRemoveObjectObjectNonListInput()
    {
        String input = "a";

        // Remove null item
        Object list = LazyList.remove(input, null);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));

        // Remove item that doesn't exist
        list = LazyList.remove(input, "b");
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));

        // Remove item that exists
        list = LazyList.remove(input, "a");
        // TODO: should this be null? or an empty list?
        assertNull(list); // nothing left in list
        assertEquals(0, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#remove(Object, Object)}
     */
    @Test
    public void testRemoveObjectObjectLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        // Remove null item
        Object list = LazyList.remove(input, null);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));

        // Attempt to remove something that doesn't exist
        list = LazyList.remove(input, "z");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));

        // Remove something that exists in input
        list = LazyList.remove(input, "b");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "c");
    }

    /**
     * Test for {@link LazyList#remove(Object, Object)}
     */
    @Test
    public void testRemoveObjectObjectGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        // Remove null item
        Object list = LazyList.remove(input, null);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(3, LazyList.size(list));

        // Attempt to remove something that doesn't exist
        list = LazyList.remove(input, "z");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(3, LazyList.size(list));

        // Remove something that exists in input
        list = LazyList.remove(input, "b");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "c");

        // Try to remove the rest.
        list = LazyList.remove(list, "a");
        list = LazyList.remove(list, "c");
        assertNull(list);
    }

    /**
     * Test for {@link LazyList#remove(Object, Object)}
     */
    @Test
    public void testRemoveObjectObjectLinkedListInput()
    {
        // Should be able to use any collection object.
        List<String> input = new LinkedList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        // Remove null item
        Object list = LazyList.remove(input, null);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(3, LazyList.size(list));

        // Attempt to remove something that doesn't exist
        list = LazyList.remove(input, "z");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(3, LazyList.size(list));

        // Remove something that exists in input
        list = LazyList.remove(input, "b");
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertTrue(input == list, "Should not have recreated list obj");
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "c");
    }

    /**
     * Tests for {@link LazyList#remove(Object, int)}
     */
    @Test
    public void testRemoveObjectIntNullInput()
    {
        Object input = null;

        assertNull(LazyList.remove(input, 0));
        assertNull(LazyList.remove(input, 2));
        assertNull(LazyList.remove(input, -2));
    }

    /**
     * Tests for {@link LazyList#remove(Object, int)}
     */
    @Test
    public void testRemoveObjectIntNonListInput()
    {
        String input = "a";

        // Invalid index
        Object list = LazyList.remove(input, 1);
        assertNotNull(list);
        if (STRICT)
        {
            assertTrue(list instanceof List);
        }
        assertEquals(1, LazyList.size(list));

        // Valid index
        list = LazyList.remove(input, 0);
        // TODO: should this be null? or an empty list?
        assertNull(list); // nothing left in list
        assertEquals(0, LazyList.size(list));
    }

    /**
     * Tests for {@link LazyList#remove(Object, int)}
     */
    @Test
    public void testRemoveObjectIntLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        Object list = null;

        if (STRICT)
        {
            // Invalid index
            // Shouldn't cause a IndexOutOfBoundsException as this is not the
            // same behavior you experience in testRemoveObjectInt_NonListInput and
            // testRemoveObjectInt_NullInput when using invalid indexes.
            list = LazyList.remove(input, 5);
            assertNotNull(list);
            assertTrue(list instanceof List);
            assertEquals(3, LazyList.size(list));
        }

        // Valid index
        list = LazyList.remove(input, 1); // remove the 'b'
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "c");
    }

    /**
     * Tests for {@link LazyList#remove(Object, int)}
     */
    @Test
    public void testRemoveObjectIntGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        Object list = null;

        if (STRICT)
        {
            // Invalid index
            // Shouldn't cause a IndexOutOfBoundsException as this is not the
            // same behavior you experience in testRemoveObjectInt_NonListInput and
            // testRemoveObjectInt_NullInput when using invalid indexes.
            list = LazyList.remove(input, 5);
            assertNotNull(list);
            assertTrue(list instanceof List);
            assertEquals(3, LazyList.size(list));
        }

        // Valid index
        list = LazyList.remove(input, 1); // remove the 'b'
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(2, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "c");

        // Remove the rest
        list = LazyList.remove(list, 0); // the 'a'
        list = LazyList.remove(list, 0); // the 'c'
        assertNull(list);
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectNullInput()
    {
        Object input = null;

        Object list = LazyList.getList(input);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(0, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectNonListInput()
    {
        String input = "a";

        Object list = LazyList.getList(input);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(1, LazyList.size(list));
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        Object list = LazyList.getList(input);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        Object list = LazyList.getList(input);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectLinkedListInput()
    {
        List<String> input = new LinkedList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        Object list = LazyList.getList(input);
        assertNotNull(list);
        assertTrue(list instanceof List);
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Test for {@link LazyList#getList(Object)}
     */
    @Test
    public void testGetListObjectNullForEmpty()
    {
        assertNull(LazyList.getList(null, true));
        assertNotNull(LazyList.getList(null, false));
    }

    /**
     * Tests for {@link LazyList#toStringArray(Object)}
     */
    @Test
    @SuppressWarnings("unchecked")
    public void testToStringArray()
    {
        assertEquals(0, LazyList.toStringArray(null).length);

        assertEquals(1, LazyList.toStringArray("a").length);
        assertEquals("a", LazyList.toStringArray("a")[0]);

        @SuppressWarnings("rawtypes")
        ArrayList l = new ArrayList();
        l.add("a");
        l.add(null);
        l.add(new Integer(2));
        String[] a = LazyList.toStringArray(l);

        assertEquals(3, a.length);
        assertEquals("a", a[0]);
        assertEquals(null, a[1]);
        assertEquals("2", a[2]);
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayNullInputObject()
    {
        Object input = null;

        Object arr = LazyList.toArray(input, Object.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayNullInputString()
    {
        String input = null;

        Object arr = LazyList.toArray(input, String.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
        assertTrue(arr instanceof String[]);
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayNonListInput()
    {
        String input = "a";

        Object arr = LazyList.toArray(input, String.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
        assertTrue(arr instanceof String[]);

        String[] strs = (String[])arr;
        assertEquals(1, strs.length);
        assertEquals("a", strs[0]);
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        Object arr = LazyList.toArray(input, String.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
        assertTrue(arr instanceof String[]);

        String[] strs = (String[])arr;
        assertEquals(3, strs.length);
        assertEquals("a", strs[0]);
        assertEquals("b", strs[1]);
        assertEquals("c", strs[2]);
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayLazyListInputPrimitives()
    {
        Object input = LazyList.add(null, 22);
        input = LazyList.add(input, 333);
        input = LazyList.add(input, 4444);
        input = LazyList.add(input, 55555);

        Object arr = LazyList.toArray(input, int.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
        assertTrue(arr instanceof int[]);

        int[] nums = (int[])arr;
        assertEquals(4, nums.length);
        assertEquals(22, nums[0]);
        assertEquals(333, nums[1]);
        assertEquals(4444, nums[2]);
        assertEquals(55555, nums[3]);
    }

    /**
     * Tests for {@link LazyList#toArray(Object, Class)}
     */
    @Test
    public void testToArrayGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        Object arr = LazyList.toArray(input, String.class);
        assertNotNull(arr);
        assertTrue(arr.getClass().isArray());
        assertTrue(arr instanceof String[]);

        String[] strs = (String[])arr;
        assertEquals(3, strs.length);
        assertEquals("a", strs[0]);
        assertEquals("b", strs[1]);
        assertEquals("c", strs[2]);
    }

    /**
     * Tests for {@link LazyList#size(Object)}
     */
    @Test
    public void testSizeNullInput()
    {
        assertEquals(0, LazyList.size(null));
    }

    /**
     * Tests for {@link LazyList#size(Object)}
     */
    @Test
    public void testSizeNonListInput()
    {
        String input = "a";
        assertEquals(1, LazyList.size(input));
    }

    /**
     * Tests for {@link LazyList#size(Object)}
     */
    @Test
    public void testSizeLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");

        assertEquals(2, LazyList.size(input));

        input = LazyList.add(input, "c");

        assertEquals(3, LazyList.size(input));
    }

    /**
     * Tests for {@link LazyList#size(Object)}
     */
    @Test
    public void testSizeGenericListInput()
    {
        List<String> input = new ArrayList<String>();

        assertEquals(0, LazyList.size(input));

        input.add("a");
        input.add("b");

        assertEquals(2, LazyList.size(input));

        input.add("c");

        assertEquals(3, LazyList.size(input));
    }

    /**
     * Tests for bad input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetOutOfBoundsNullInput()
    {
        assertThrows(IndexOutOfBoundsException.class, () ->
            {
                LazyList.get(null, 0); // Should Fail due to null input
            }
        );
    }

    /**
     * Tests for bad input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetOutOfBoundsNonListInput()
    {
        assertThrows(IndexOutOfBoundsException.class, () ->
        {
            String input = "a";
            LazyList.get(input, 1); // Should Fail
        });
    }

    /**
     * Tests for bad input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetOutOfBoundsLazyListInput()
    {
        assertThrows(IndexOutOfBoundsException.class, () ->
        {
            Object input = LazyList.add(null, "a");
            LazyList.get(input, 1); // Should Fail
        });
    }

    /**
     * Tests for bad input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetOutOfBoundsGenericListInput()
    {
        assertThrows(IndexOutOfBoundsException.class, () ->
        {
            List<String> input = new ArrayList<String>();
            input.add("a");
            LazyList.get(input, 1); // Should Fail
        });
    }

    /**
     * Tests for non-list input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetNonListInput()
    {
        String input = "a";
        assertEquals(LazyList.get(input, 0), "a");
    }

    /**
     * Tests for list input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        assertEquals(LazyList.get(input, 0), "a");
    }

    /**
     * Tests for list input on {@link LazyList#get(Object, int)}
     */
    @Test
    public void testGetGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        assertEquals(LazyList.get(input, 0), "a");

        List<URI> uris = new ArrayList<URI>();
        uris.add(URI.create("http://www.mortbay.org/"));
        uris.add(URI.create("http://jetty.codehaus.org/jetty/"));
        uris.add(URI.create("http://www.intalio.com/jetty/"));
        uris.add(URI.create("https://www.eclipse.org/jetty/"));

        // Make sure that Generics pass through the 'get' routine safely.
        // We should be able to call this without casting the result to URI
        URI eclipseUri = LazyList.get(uris, 3);
        assertEquals("https://www.eclipse.org/jetty/", eclipseUri.toASCIIString());
    }

    /**
     * Tests for {@link LazyList#contains(Object, Object)}
     */
    @Test
    public void testContainsNullInput()
    {
        assertFalse(LazyList.contains(null, "z"));
    }

    /**
     * Tests for {@link LazyList#contains(Object, Object)}
     */
    @Test
    public void testContainsNonListInput()
    {
        String input = "a";
        assertFalse(LazyList.contains(input, "z"));
        assertTrue(LazyList.contains(input, "a"));
    }

    /**
     * Tests for {@link LazyList#contains(Object, Object)}
     */
    @Test
    public void testContainsLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        assertFalse(LazyList.contains(input, "z"));
        assertTrue(LazyList.contains(input, "a"));
        assertTrue(LazyList.contains(input, "b"));
    }

    /**
     * Tests for {@link LazyList#contains(Object, Object)}
     */
    @Test
    public void testContainsGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        assertFalse(LazyList.contains(input, "z"));
        assertTrue(LazyList.contains(input, "a"));
        assertTrue(LazyList.contains(input, "b"));
    }

    /**
     * Tests for {@link LazyList#clone(Object)}
     */
    @Test
    public void testCloneNullInput()
    {
        Object input = null;

        Object list = LazyList.clone(input);
        assertNull(list);
    }

    /**
     * Tests for {@link LazyList#clone(Object)}
     */
    @Test
    public void testCloneNonListInput()
    {
        String input = "a";

        Object list = LazyList.clone(input);
        assertNotNull(list);
        assertTrue(input == list, "Should be the same object");
    }

    /**
     * Tests for {@link LazyList#clone(Object)}
     */
    @Test
    public void testCloneLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        Object list = LazyList.clone(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertFalse(input == list, "Should NOT be the same object");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link LazyList#clone(Object)}
     */
    @Test
    public void testCloneGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        // TODO: decorate the .clone(Object) method to return
        //       the same generic object element type
        Object list = LazyList.clone(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertFalse(input == list, "Should NOT be the same object");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link LazyList#toString(Object)}
     */
    @Test
    public void testToStringNullInput()
    {
        Object input = null;
        assertEquals("[]", LazyList.toString(input));
    }

    /**
     * Tests for {@link LazyList#toString(Object)}
     */
    @Test
    public void testToStringNonListInput()
    {
        String input = "a";
        assertEquals("[a]", LazyList.toString(input));
    }

    /**
     * Tests for {@link LazyList#toString(Object)}
     */
    @Test
    public void testToStringLazyListInput()
    {
        Object input = LazyList.add(null, "a");

        assertEquals("[a]", LazyList.toString(input));

        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        assertEquals("[a, b, c]", LazyList.toString(input));
    }

    /**
     * Tests for {@link LazyList#toString(Object)}
     */
    @Test
    public void testToStringGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");

        assertEquals("[a]", LazyList.toString(input));

        input.add("b");
        input.add("c");

        assertEquals("[a, b, c]", LazyList.toString(input));
    }

    /**
     * Tests for {@link LazyList#iterator(Object)}
     */
    @Test
    public void testIteratorNullInput()
    {
        Iterator<?> iter = LazyList.iterator(null);
        assertNotNull(iter);
        assertFalse(iter.hasNext());
    }

    /**
     * Tests for {@link LazyList#iterator(Object)}
     */
    @Test
    public void testIteratorNonListInput()
    {
        String input = "a";

        Iterator<?> iter = LazyList.iterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertEquals("a", iter.next());
        assertFalse(iter.hasNext());
    }

    /**
     * Tests for {@link LazyList#iterator(Object)}
     */
    @Test
    public void testIteratorLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        Iterator<?> iter = LazyList.iterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertFalse(iter.hasNext());
    }

    /**
     * Tests for {@link LazyList#iterator(Object)}
     */
    @Test
    public void testIteratorGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        Iterator<String> iter = LazyList.iterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertFalse(iter.hasNext());
    }

    /**
     * Tests for {@link LazyList#listIterator(Object)}
     */
    @Test
    public void testListIteratorNullInput()
    {
        ListIterator<?> iter = LazyList.listIterator(null);
        assertNotNull(iter);
        assertFalse(iter.hasNext());
        assertFalse(iter.hasPrevious());
    }

    /**
     * Tests for {@link LazyList#listIterator(Object)}
     */
    @Test
    public void testListIteratorNonListInput()
    {
        String input = "a";

        ListIterator<?> iter = LazyList.listIterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertFalse(iter.hasPrevious());
        assertEquals("a", iter.next());
        assertFalse(iter.hasNext());
        assertTrue(iter.hasPrevious());
    }

    /**
     * Tests for {@link LazyList#listIterator(Object)}
     */
    @Test
    public void testListIteratorLazyListInput()
    {
        Object input = LazyList.add(null, "a");
        input = LazyList.add(input, "b");
        input = LazyList.add(input, "c");

        ListIterator<?> iter = LazyList.listIterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertFalse(iter.hasPrevious());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertFalse(iter.hasNext());
        assertTrue(iter.hasPrevious());
        assertEquals("c", iter.previous());
        assertEquals("b", iter.previous());
        assertEquals("a", iter.previous());
        assertFalse(iter.hasPrevious());
    }

    /**
     * Tests for {@link LazyList#listIterator(Object)}
     */
    @Test
    public void testListIteratorGenericListInput()
    {
        List<String> input = new ArrayList<String>();
        input.add("a");
        input.add("b");
        input.add("c");

        ListIterator<?> iter = LazyList.listIterator(input);
        assertNotNull(iter);
        assertTrue(iter.hasNext());
        assertFalse(iter.hasPrevious());
        assertEquals("a", iter.next());
        assertEquals("b", iter.next());
        assertEquals("c", iter.next());
        assertFalse(iter.hasNext());
        assertTrue(iter.hasPrevious());
        assertEquals("c", iter.previous());
        assertEquals("b", iter.previous());
        assertEquals("a", iter.previous());
        assertFalse(iter.hasPrevious());
    }

    /**
     * Tests for {@link ArrayUtil#asMutableList(Object[])}
     */
    @Test
    public void testArray2ListNullInput()
    {
        Object[] input = null;

        Object list = ArrayUtil.asMutableList(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertEquals(0, LazyList.size(list));
    }

    /**
     * Tests for {@link ArrayUtil#asMutableList(Object[])}
     */
    @Test
    public void testArray2ListEmptyInput()
    {
        String[] input = new String[0];

        Object list = ArrayUtil.asMutableList(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertEquals(0, LazyList.size(list));
    }

    /**
     * Tests for {@link ArrayUtil#asMutableList(Object[])}
     */
    @Test
    public void testArray2ListSingleInput()
    {
        String[] input = new String[]{"a"};

        Object list = ArrayUtil.asMutableList(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertEquals(1, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
    }

    /**
     * Tests for {@link ArrayUtil#asMutableList(Object[])}
     */
    @Test
    public void testArray2ListMultiInput()
    {
        String[] input = new String[]{"a", "b", "c"};

        Object list = ArrayUtil.asMutableList(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link ArrayUtil#asMutableList(Object[])}
     */
    @Test
    public void testArray2ListGenericsInput()
    {
        String[] input = new String[]{"a", "b", "c"};

        // Test the Generics definitions for array2List
        List<String> list = ArrayUtil.asMutableList(input);
        assertNotNull(list);
        assertTrue(list instanceof List, "Should be a List object");
        assertEquals(3, LazyList.size(list));
        assertEquals(LazyList.get(list, 0), "a");
        assertEquals(LazyList.get(list, 1), "b");
        assertEquals(LazyList.get(list, 2), "c");
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArrayNullInputNullItem()
    {
        Object[] input = null;

        Object[] arr = ArrayUtil.addToArray(input, null, Object.class);
        assertNotNull(arr);
        if (STRICT)
        {
            // Adding null item to array should result in nothing added?
            assertEquals(0, arr.length);
        }
        else
        {
            assertEquals(1, arr.length);
        }
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArrayNullNullNull()
    {
        // NPE if item && type are both null.
        assumeTrue(STRICT);

        // Harsh test case.
        Object[] input = null;

        Object[] arr = ArrayUtil.addToArray(input, null, null);
        assertNotNull(arr);
        if (STRICT)
        {
            // Adding null item to array should result in nothing added?
            assertEquals(0, arr.length);
        }
        else
        {
            assertEquals(1, arr.length);
        }
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArrayNullInputSimpleItem()
    {
        Object[] input = null;

        Object[] arr = ArrayUtil.addToArray(input, "a", String.class);
        assertNotNull(arr);
        assertEquals(1, arr.length);
        assertEquals("a", arr[0]);

        // Same test, but with an undefined type
        arr = ArrayUtil.addToArray(input, "b", null);
        assertNotNull(arr);
        assertEquals(1, arr.length);
        assertEquals("b", arr[0]);
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArrayEmptyInputNullItem()
    {
        String[] input = new String[0];

        String[] arr = ArrayUtil.addToArray(input, null, Object.class);
        assertNotNull(arr);
        if (STRICT)
        {
            // Adding null item to array should result in nothing added?
            assertEquals(0, arr.length);
        }
        else
        {
            assertEquals(1, arr.length);
        }
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArrayEmptyInputSimpleItem()
    {
        String[] input = new String[0];

        String[] arr = ArrayUtil.addToArray(input, "a", String.class);
        assertNotNull(arr);
        assertEquals(1, arr.length);
        assertEquals("a", arr[0]);
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArraySingleInputNullItem()
    {
        String[] input = new String[]{"z"};

        String[] arr = ArrayUtil.addToArray(input, null, Object.class);
        assertNotNull(arr);
        if (STRICT)
        {
            // Should a null item be added to an array?
            assertEquals(1, arr.length);
        }
        else
        {
            assertEquals(2, arr.length);
            assertEquals("z", arr[0]);
            assertEquals(null, arr[1]);
        }
    }

    /**
     * Tests for {@link ArrayUtil#addToArray(Object[], Object, Class)}
     */
    @Test
    public void testAddToArraySingleInputSimpleItem()
    {
        String[] input = new String[]{"z"};

        String[] arr = ArrayUtil.addToArray(input, "a", String.class);
        assertNotNull(arr);
        assertEquals(2, arr.length);
        assertEquals("z", arr[0]);
        assertEquals("a", arr[1]);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArrayNullInputNullItem()
    {
        Object[] input = null;

        Object[] arr = ArrayUtil.removeFromArray(input, null);
        assertNull(arr);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArrayNullInputSimpleItem()
    {
        Object[] input = null;

        Object[] arr = ArrayUtil.removeFromArray(input, "a");
        assertNull(arr);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArrayEmptyInputNullItem()
    {
        String[] input = new String[0];

        String[] arr = ArrayUtil.removeFromArray(input, null);
        assertNotNull(arr, "Should not be null");
        assertEquals(0, arr.length);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArrayEmptyInputSimpleItem()
    {
        String[] input = new String[0];

        String[] arr = ArrayUtil.removeFromArray(input, "a");
        assertNotNull(arr, "Should not be null");
        assertEquals(0, arr.length);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArraySingleInput()
    {
        String[] input = new String[]{"a"};

        String[] arr = ArrayUtil.removeFromArray(input, null);
        assertNotNull(arr, "Should not be null");
        assertEquals(1, arr.length);
        assertEquals("a", arr[0]);

        // Remove actual item
        arr = ArrayUtil.removeFromArray(input, "a");
        assertNotNull(arr, "Should not be null");
        assertEquals(0, arr.length);
    }

    /**
     * Tests for {@link ArrayUtil#removeFromArray(Object[], Object)}
     */
    @Test
    public void testRemoveFromArrayMultiInput()
    {
        String[] input = new String[]{"a", "b", "c"};

        String[] arr = ArrayUtil.removeFromArray(input, null);
        assertNotNull(arr, "Should not be null");
        assertEquals(3, arr.length);
        assertEquals("a", arr[0]);
        assertEquals("b", arr[1]);
        assertEquals("c", arr[2]);

        // Remove an actual item
        arr = ArrayUtil.removeFromArray(input, "b");
        assertNotNull(arr, "Should not be null");
        assertEquals(2, arr.length);
        assertEquals("a", arr[0]);
        assertEquals("c", arr[1]);
    }
}
