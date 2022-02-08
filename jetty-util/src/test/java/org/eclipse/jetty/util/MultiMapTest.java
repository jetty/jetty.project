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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class MultiMapTest
{
    /**
     * Tests {@link MultiMap#put(String, Object)}
     */
    @Test
    public void testPut()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");
    }

    /**
     * Tests {@link MultiMap#put(String, Object)}
     */
    @Test
    public void testPutNullString()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";
        String val = null;

        mm.put(key, val);
        assertMapSize(mm, 1);
        assertNullValues(mm, key);
    }

    /**
     * Tests {@link MultiMap#put(String, Object)}
     */
    @Test
    public void testPutNullList()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";
        List<String> vals = null;

        mm.put(key, vals);
        assertMapSize(mm, 1);
        assertNullValues(mm, key);
    }

    /**
     * Tests {@link MultiMap#put(String, Object)}
     */
    @Test
    public void testPutReplace()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";
        Object ret;

        ret = mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");
        assertNull(ret, "Should not have replaced anything");
        Object orig = mm.get(key);

        // Now replace it
        ret = mm.put(key, "jar");
        assertMapSize(mm, 1);
        assertValues(mm, key, "jar");
        assertEquals(orig, ret, "Should have replaced original");
    }

    /**
     * Tests {@link MultiMap#putValues(String, List)}
     */
    @Test
    public void testPutValuesList()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        List<String> input = new ArrayList<String>();
        input.add("gzip");
        input.add("jar");
        input.add("pack200");

        mm.putValues(key, input);
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");
    }

    /**
     * Tests {@link MultiMap#putValues(String, Object[])}
     */
    @Test
    public void testPutValuesStringArray()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        String[] input = {"gzip", "jar", "pack200"};
        mm.putValues(key, input);
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");
    }

    /**
     * Tests {@link MultiMap#putValues(String, List)}
     */
    @Test
    public void testPutValuesVarArgs()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        mm.putValues(key, "gzip", "jar", "pack200");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");
    }

    /**
     * Tests {@link MultiMap#add(String, Object)}
     */
    @Test
    public void testAdd()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");

        // Add to the key
        mm.add(key, "jar");
        mm.add(key, "pack200");

        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");
    }

    /**
     * Tests {@link MultiMap#addValues(String, List)}
     */
    @Test
    public void testAddValuesList()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");

        // Add to the key
        List<String> extras = new ArrayList<String>();
        extras.add("jar");
        extras.add("pack200");
        extras.add("zip");
        mm.addValues(key, extras);

        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200", "zip");
    }

    /**
     * Tests {@link MultiMap#addValues(String, List)}
     */
    @Test
    public void testAddValuesListEmpty()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");

        // Add to the key
        List<String> extras = new ArrayList<String>();
        mm.addValues(key, extras);

        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");
    }

    /**
     * Tests {@link MultiMap#addValues(String, Object[])}
     */
    @Test
    public void testAddValuesStringArray()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");

        // Add to the key
        String[] extras = {"jar", "pack200", "zip"};
        mm.addValues(key, extras);

        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200", "zip");
    }

    /**
     * Tests {@link MultiMap#addValues(String, Object[])}
     */
    @Test
    public void testAddValuesStringArrayEmpty()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.put(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");

        // Add to the key
        String[] extras = new String[0];
        mm.addValues(key, extras);

        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip");
    }

    /**
     * Tests {@link MultiMap#removeValue(String, Object)}
     */
    @Test
    public void testRemoveValue()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.putValues(key, "gzip", "jar", "pack200");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");

        // Remove a value
        mm.removeValue(key, "jar");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "pack200");
    }

    /**
     * Tests {@link MultiMap#removeValue(String, Object)}
     */
    @Test
    public void testRemoveValueInvalidItem()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.putValues(key, "gzip", "jar", "pack200");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");

        // Remove a value that isn't there
        mm.removeValue(key, "msi");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");
    }

    /**
     * Tests {@link MultiMap#removeValue(String, Object)}
     */
    @Test
    public void testRemoveValueAllItems()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.putValues(key, "gzip", "jar", "pack200");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "jar", "pack200");

        // Remove a value
        mm.removeValue(key, "jar");
        assertMapSize(mm, 1);
        assertValues(mm, key, "gzip", "pack200");

        // Remove another value
        mm.removeValue(key, "gzip");
        assertMapSize(mm, 1);
        assertValues(mm, key, "pack200");

        // Remove last value
        mm.removeValue(key, "pack200");
        assertMapSize(mm, 0);  // should be empty now
    }

    /**
     * Tests {@link MultiMap#removeValue(String, Object)}
     */
    @Test
    public void testRemoveValueFromEmpty()
    {
        MultiMap<String> mm = new MultiMap<>();

        String key = "formats";

        // Setup the key
        mm.putValues(key, new String[0]);
        assertMapSize(mm, 1);
        assertEmptyValues(mm, key);

        // Remove a value that isn't in the underlying values
        mm.removeValue(key, "jar");
        assertMapSize(mm, 1);
        assertEmptyValues(mm, key);
    }

    /**
     * Tests {@link MultiMap#putAll(java.util.Map)}
     */
    @Test
    public void testPutAllMap()
    {
        MultiMap<String> mm = new MultiMap<>();

        assertMapSize(mm, 0); // Shouldn't have anything yet.

        Map<String, String> input = new HashMap<String, String>();
        input.put("food", "apple");
        input.put("color", "red");
        input.put("amount", "bushel");

        mm.putAllValues(input);

        assertMapSize(mm, 3);
        assertValues(mm, "food", "apple");
        assertValues(mm, "color", "red");
        assertValues(mm, "amount", "bushel");
    }

    /**
     * Tests {@link MultiMap#putAll(java.util.Map)}
     */
    @Test
    public void testPutAllMultiMapSimple()
    {
        MultiMap<String> mm = new MultiMap<>();

        assertMapSize(mm, 0); // Shouldn't have anything yet.

        MultiMap<String> input = new MultiMap<>();
        input.put("food", "apple");
        input.put("color", "red");
        input.put("amount", "bushel");

        mm.putAll(input);

        assertMapSize(mm, 3);
        assertValues(mm, "food", "apple");
        assertValues(mm, "color", "red");
        assertValues(mm, "amount", "bushel");
    }

    /**
     * Tests {@link MultiMap#putAll(java.util.Map)}
     */
    @Test
    public void testPutAllMultiMapComplex()
    {
        MultiMap<String> mm = new MultiMap<>();

        assertMapSize(mm, 0); // Shouldn't have anything yet.

        MultiMap<String> input = new MultiMap<>();
        input.putValues("food", "apple", "cherry", "raspberry");
        input.put("color", "red");
        input.putValues("amount", "bushel", "pint");

        mm.putAll(input);

        assertMapSize(mm, 3);
        assertValues(mm, "food", "apple", "cherry", "raspberry");
        assertValues(mm, "color", "red");
        assertValues(mm, "amount", "bushel", "pint");
    }

    /**
     * Tests {@link MultiMap#toStringArrayMap()}
     */
    @Test
    public void testToStringArrayMap()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        assertMapSize(mm, 3);

        Map<String, String[]> sam = mm.toStringArrayMap();
        assertEquals(3, sam.size(), "String Array Map.size");

        assertArray("toStringArrayMap(food)", sam.get("food"), "apple", "cherry", "raspberry");
        assertArray("toStringArrayMap(color)", sam.get("color"), "red");
        assertArray("toStringArrayMap(amount)", sam.get("amount"), "bushel", "pint");
    }

    /**
     * Tests {@link MultiMap#toString()}
     */
    @Test
    public void testToString()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.put("color", "red");

        assertEquals("{color=red}", mm.toString());

        mm.putValues("food", "apple", "cherry", "raspberry");

        String expected1 = "{color=red, food=[apple, cherry, raspberry]}";
        String expected2 = "{food=[apple, cherry, raspberry], color=red}";
        String actual = mm.toString();
        assertTrue(actual.equals(expected1) || actual.equals(expected2));
    }

    /**
     * Tests {@link MultiMap#clear()}
     */
    @Test
    public void testClear()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        assertMapSize(mm, 3);

        mm.clear();

        assertMapSize(mm, 0);
    }

    /**
     * Tests {@link MultiMap#containsKey(Object)}
     */
    @Test
    public void testContainsKey()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        assertTrue(mm.containsKey("color"), "Contains Key [color]");
        assertFalse(mm.containsKey("nutrition"), "Contains Key [nutrition]");
    }

    /**
     * Tests {@link MultiMap#containsSimpleValue(Object)}
     */
    @Test
    public void testContainsSimpleValue()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        assertTrue(mm.containsSimpleValue("red"), "Contains Value [red]");
        assertFalse(mm.containsValue("nutrition"), "Contains Value [nutrition]");
    }

    /**
     * Tests {@link MultiMap#containsValue(Object)}
     */
    @Test
    public void testContainsValue()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        List<String> acr = new ArrayList<>();
        acr.add("apple");
        acr.add("cherry");
        acr.add("raspberry");
        assertTrue(mm.containsValue(acr), "Contains Value [apple,cherry,raspberry]");
        assertFalse(mm.containsValue("nutrition"), "Contains Value [nutrition]");
    }

    /**
     * Tests {@link MultiMap#containsValue(Object)}
     */
    @Test
    public void testContainsValueLazyList()
    {
        MultiMap<String> mm = new MultiMap<>();
        mm.putValues("food", "apple", "cherry", "raspberry");
        mm.put("color", "red");
        mm.putValues("amount", "bushel", "pint");

        Object list = LazyList.add(null, "bushel");
        list = LazyList.add(list, "pint");

        assertTrue(mm.containsValue(list), "Contains Value [" + list + "]");
    }

    private void assertArray(String prefix, Object[] actualValues, Object... expectedValues)
    {
        assertEquals(expectedValues.length, actualValues.length, prefix + ".size");
        int len = actualValues.length;
        for (int i = 0; i < len; i++)
        {
            assertEquals(expectedValues[i], actualValues[i], prefix + "[" + i + "]");
        }
    }

    private void assertValues(MultiMap<String> mm, String key, Object... expectedValues)
    {
        List<String> values = mm.getValues(key);

        String prefix = "MultiMap.getValues(" + key + ")";

        assertThat(prefix + ".size", values.size(), is(expectedValues.length));
        int len = expectedValues.length;
        for (int i = 0; i < len; i++)
        {
            if (expectedValues[i] == null)
            {
                assertThat(prefix + "[" + i + "]", values.get(i), is(nullValue()));
            }
            else
            {
                assertThat(prefix + "[" + i + "]", values.get(i), is(expectedValues[i]));
            }
        }
    }

    private void assertNullValues(MultiMap<String> mm, String key)
    {
        List<String> values = mm.getValues(key);

        String prefix = "MultiMap.getValues(" + key + ")";

        assertThat(prefix + ".size", values, nullValue());
    }

    private void assertEmptyValues(MultiMap<String> mm, String key)
    {
        List<String> values = mm.getValues(key);

        String prefix = "MultiMap.getValues(" + key + ")";

        assertEquals(0, LazyList.size(values), prefix + ".size");
    }

    private void assertMapSize(MultiMap<String> mm, int expectedSize)
    {
        assertEquals(expectedSize, mm.size(), "MultiMap.size");
    }
}
