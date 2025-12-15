//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util.component;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContainerLifeCycleMapTest
{
    @Test
    public void testPutGetRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        // Test put
        assertNull(map.put("key1", bean1));
        assertNull(map.put("key2", bean2));

        // Test get
        assertEquals(bean1, map.get("key1"));
        assertEquals(bean2, map.get("key2"));
        assertNull(map.get("nonexistent"));

        // Test size
        assertEquals(2, map.size());
        assertFalse(map.isEmpty());

        // Test containsKey/containsValue
        assertTrue(map.containsKey("key1"));
        assertTrue(map.containsValue(bean1));
        assertFalse(map.containsKey("nonexistent"));

        // Test remove
        assertEquals(bean1, map.remove("key1"));
        assertNull(map.get("key1"));
        assertNull(map.remove("nonexistent"));
        assertEquals(1, map.size());
    }

    @Test
    public void testPutReplacesOldValue() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        assertNull(map.put("key", bean1));
        assertEquals(bean1, map.put("key", bean2));
        assertEquals(bean2, map.get("key"));
        assertEquals(1, map.size());
    }

    @Test
    public void testPutAll() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        Map<String, TestLifeCycle> other = new HashMap<>();
        other.put("key1", bean1);
        other.put("key2", bean2);

        map.putAll(other);

        assertEquals(2, map.size());
        assertEquals(bean1, map.get("key1"));
        assertEquals(bean2, map.get("key2"));
        assertTrue(map.contains(bean1));
        assertTrue(map.contains(bean2));
    }

    @Test
    public void testClear() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        map.clear();

        assertTrue(map.isEmpty());
        assertEquals(0, map.size());
        assertNull(map.get("key1"));
        assertNull(map.get("key2"));
    }

    @Test
    public void testBeanAddedOnPut() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean = new TestLifeCycle("bean");

        map.put("key", bean);

        // Bean should be added to the container
        assertTrue(map.contains(bean));
    }

    @Test
    public void testBeanRemovedOnRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean = new TestLifeCycle("bean");

        map.put("key", bean);
        assertTrue(map.contains(bean));

        map.remove("key");
        assertFalse(map.contains(bean));
    }

    @Test
    public void testBeanReplacedOnPut() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key", bean1);
        assertTrue(map.contains(bean1));
        assertFalse(map.contains(bean2));

        map.put("key", bean2);
        assertFalse(map.contains(bean1));
        assertTrue(map.contains(bean2));
    }

    @Test
    public void testBeansRemovedOnClear() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);
        assertTrue(map.contains(bean1));
        assertTrue(map.contains(bean2));

        map.clear();
        assertFalse(map.contains(bean1));
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testLifeCycleManagement() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        // Beans should not be started yet
        assertEquals(0, bean1.started.get());
        assertEquals(0, bean2.started.get());

        // Start the container
        map.start();

        // Beans should now be started
        assertEquals(1, bean1.started.get());
        assertEquals(1, bean2.started.get());
        assertTrue(bean1.isStarted());
        assertTrue(bean2.isStarted());

        // Stop the container
        map.stop();

        // Beans should now be stopped
        assertEquals(1, bean1.stopped.get());
        assertEquals(1, bean2.stopped.get());
        assertTrue(bean1.isStopped());
        assertTrue(bean2.isStopped());
    }

    @Test
    public void testKeySet() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        Set<String> keys = map.keySet();
        assertEquals(2, keys.size());
        assertTrue(keys.contains("key1"));
        assertTrue(keys.contains("key2"));
    }

    @Test
    public void testKeySetRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        // Remove via keySet
        map.keySet().remove("key1");

        assertNull(map.get("key1"));
        assertFalse(map.contains(bean1));
        assertEquals(1, map.size());
    }

    @Test
    public void testKeySetIteratorRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        // Remove via keySet iterator
        Iterator<String> iter = map.keySet().iterator();
        while (iter.hasNext())
        {
            String key = iter.next();
            if (key.equals("key1"))
            {
                iter.remove();
            }
        }

        assertNull(map.get("key1"));
        assertFalse(map.contains(bean1));
        assertEquals(1, map.size());
    }

    @Test
    public void testValuesIteratorRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        // Remove via values iterator
        Iterator<TestLifeCycle> iter = map.values().iterator();
        while (iter.hasNext())
        {
            TestLifeCycle value = iter.next();
            if (value == bean1)
            {
                iter.remove();
            }
        }

        assertNull(map.get("key1"));
        assertFalse(map.contains(bean1));
        assertEquals(1, map.size());
    }

    @Test
    public void testEntrySet() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        Set<Map.Entry<String, TestLifeCycle>> entries = map.entrySet();
        assertEquals(2, entries.size());
    }

    @Test
    public void testEntrySetIteratorRemove() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        // Remove via entrySet iterator
        Iterator<Map.Entry<String, TestLifeCycle>> iter = map.entrySet().iterator();
        while (iter.hasNext())
        {
            Map.Entry<String, TestLifeCycle> entry = iter.next();
            if (entry.getKey().equals("key1"))
            {
                iter.remove();
            }
        }

        assertNull(map.get("key1"));
        assertFalse(map.contains(bean1));
        assertEquals(1, map.size());
    }

    @Test
    public void testEntrySetValue() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);

        // Set value via entry
        for (Map.Entry<String, TestLifeCycle> entry : map.entrySet())
        {
            if (entry.getKey().equals("key1"))
            {
                entry.setValue(bean2);
            }
        }

        assertEquals(bean2, map.get("key1"));
        assertFalse(map.contains(bean1));
        assertTrue(map.contains(bean2));
    }

    @Test
    public void testKeySetClear() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        map.keySet().clear();

        assertTrue(map.isEmpty());
        assertFalse(map.contains(bean1));
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testValuesClear() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        map.values().clear();

        assertTrue(map.isEmpty());
        assertFalse(map.contains(bean1));
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testEntrySetClear() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        map.entrySet().clear();

        assertTrue(map.isEmpty());
        assertFalse(map.contains(bean1));
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testToString() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        map.put("key1", new TestLifeCycle("bean1"));
        map.put("key2", new TestLifeCycle("bean2"));

        String str = map.toString();
        assertTrue(str.contains("size=2"));
    }

    private static class TestLifeCycle extends AbstractLifeCycle
    {
        private final String _name;
        final AtomicInteger started = new AtomicInteger();
        final AtomicInteger stopped = new AtomicInteger();

        TestLifeCycle(String name)
        {
            _name = name;
        }

        @Override
        protected void doStart() throws Exception
        {
            started.incrementAndGet();
            super.doStart();
        }

        @Override
        protected void doStop() throws Exception
        {
            stopped.incrementAndGet();
            super.doStop();
        }

        @Override
        public String toString()
        {
            return _name;
        }
    }
}
