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

package org.eclipse.jetty.util.ajax;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class JSONCollectionConvertorTest
{
    @Test
    public void testArrayList()
    {
        List<String> list = new ArrayList<>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    @Test
    public void testLinkedList()
    {
        List<String> list = new LinkedList<>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    @Test
    public void testCopyOnWriteArrayList()
    {
        List<String> list = new CopyOnWriteArrayList<>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    private void testList(List<String> list1)
    {
        JSON json = new JSON();
        json.addConvertor(List.class, new JSONCollectionConvertor());

        Map<String, Object> object1 = new HashMap<>();
        String field = "field";
        object1.put(field, list1);

        String string = json.toJSON(object1);
        assertThat(string, containsString(list1.getClass().getName()));

        @SuppressWarnings("unchecked")
        Map<String, Object> object2 = (Map<String, Object>)json.fromJSON(string);
        @SuppressWarnings("unchecked")
        List<String> list2 = (List<String>)object2.get(field);

        assertSame(list1.getClass(), list2.getClass());
        assertEquals(list1, list2);
    }

    @Test
    public void testHashSet()
    {
        Set<String> set = new HashSet<>();
        Collections.addAll(set, "one", "two", "three");
        testSet(set);
    }

    @Test
    public void testTreeSet()
    {
        Set<String> set = new TreeSet<>();
        Collections.addAll(set, "one", "two", "three");
        testSet(set);
    }

    private void testSet(Set<String> set1)
    {
        JSON json = new JSON();
        json.addConvertor(Set.class, new JSONCollectionConvertor());

        String string = json.toJSON(set1);
        assertThat(string, containsString(set1.getClass().getName()));

        @SuppressWarnings("unchecked")
        Set<String> set2 = (Set<String>)json.fromJSON(string);

        assertSame(set1.getClass(), set2.getClass());
        assertEquals(set1, set2);
    }
}
