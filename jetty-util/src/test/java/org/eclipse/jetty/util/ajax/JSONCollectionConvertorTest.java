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

import org.junit.Assert;
import org.junit.Test;

public class JSONCollectionConvertorTest
{
    @Test
    public void testArrayList() throws Exception
    {
        List<String> list = new ArrayList<String>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    @Test
    public void testLinkedList() throws Exception
    {
        List<String> list = new LinkedList<String>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    @Test
    public void testCopyOnWriteArrayList() throws Exception
    {
        List<String> list = new CopyOnWriteArrayList<String>();
        Collections.addAll(list, "one", "two");
        testList(list);
    }

    private void testList(List<String> list1) throws Exception
    {
        JSON json = new JSON();
        json.addConvertor(List.class, new JSONCollectionConvertor());

        Map<String, Object> object1 = new HashMap<String, Object>();
        String field = "field";
        object1.put(field, list1);

        String string = json.toJSON(object1);
        Assert.assertTrue(string.contains(list1.getClass().getName()));

        @SuppressWarnings("unchecked")
        Map<String, Object> object2 = (Map<String, Object>)json.parse(new JSON.StringSource(string));
        @SuppressWarnings("unchecked")
        List<String> list2 = (List<String>)object2.get(field);

        Assert.assertSame(list1.getClass(), list2.getClass());
        Assert.assertEquals(list1, list2);
    }

    @Test
    public void testHashSet() throws Exception
    {
        Set<String> set = new HashSet<String>();
        Collections.addAll(set, "one", "two", "three");
        testSet(set);
    }

    @Test
    public void testTreeSet() throws Exception
    {
        Set<String> set = new TreeSet<String>();
        Collections.addAll(set, "one", "two", "three");
        testSet(set);
    }

    private void testSet(Set<String> set1)
    {
        JSON json = new JSON();
        json.addConvertor(Set.class, new JSONCollectionConvertor());

        String string = json.toJSON(set1);
        Assert.assertTrue(string.contains(set1.getClass().getName()));

        @SuppressWarnings("unchecked")
        Set<String> set2 = (Set<String>)json.parse(new JSON.StringSource(string));

        Assert.assertSame(set1.getClass(), set2.getClass());
        Assert.assertEquals(set1, set2);
    }
}
