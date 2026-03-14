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

import java.io.StringWriter;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
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

        assertNull(map.put("key1", bean1));
        assertNull(map.put("key2", bean2));

        assertEquals(bean1, map.get("key1"));
        assertEquals(bean2, map.get("key2"));
        assertNull(map.get("nonexistent"));

        assertEquals(2, map.size());
        assertFalse(map.isEmpty());

        assertTrue(map.containsKey("key1"));
        assertTrue(map.containsValue(bean1));
        assertFalse(map.containsKey("nonexistent"));

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

        assertEquals(0, bean1.started.get());
        assertEquals(0, bean2.started.get());

        map.start();

        assertEquals(1, bean1.started.get());
        assertEquals(1, bean2.started.get());
        assertTrue(bean1.isStarted());
        assertTrue(bean2.isStarted());

        map.stop();

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

        map.keySet().remove("key1");

        assertNull(map.get("key1"));
        assertFalse(map.contains(bean1));
        assertEquals(1, map.size());
    }

    public static Stream<Arguments> iteratorRemoveSource()
    {
        return Stream.of(
            Arguments.of("keySet", (BiConsumer<ContainerLifeCycleMap<String, TestLifeCycle>, TestLifeCycle>)(map, bean) ->
            {
                Iterator<String> iter = map.keySet().iterator();
                while (iter.hasNext())
                {
                    if (iter.next().equals("key1"))
                    {
                        iter.remove();
                        break;
                    }
                }
            }),
            Arguments.of("values", (BiConsumer<ContainerLifeCycleMap<String, TestLifeCycle>, TestLifeCycle>)(map, bean) ->
            {
                Iterator<TestLifeCycle> iter = map.values().iterator();
                while (iter.hasNext())
                {
                    if (iter.next() == bean)
                    {
                        iter.remove();
                        break;
                    }
                }
            }),
            Arguments.of("entrySet", (BiConsumer<ContainerLifeCycleMap<String, TestLifeCycle>, TestLifeCycle>)(map, bean) ->
            {
                Iterator<Map.Entry<String, TestLifeCycle>> iter = map.entrySet().iterator();
                while (iter.hasNext())
                {
                    if (iter.next().getKey().equals("key1"))
                    {
                        iter.remove();
                        break;
                    }
                }
            })
        );
    }

    @ParameterizedTest
    @MethodSource("iteratorRemoveSource")
    public void testIteratorRemoveViaView(String viewName, BiConsumer<ContainerLifeCycleMap<String, TestLifeCycle>, TestLifeCycle> removeOp) throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        removeOp.accept(map, bean1);

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
    public void testEntrySetValue() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);

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

    public static Stream<Arguments> clearViewSource()
    {
        return Stream.of(
            Arguments.of("keySet", (Consumer<ContainerLifeCycleMap<String, TestLifeCycle>>)map -> map.keySet().clear()),
            Arguments.of("values", (Consumer<ContainerLifeCycleMap<String, TestLifeCycle>>)map -> map.values().clear()),
            Arguments.of("entrySet", (Consumer<ContainerLifeCycleMap<String, TestLifeCycle>>)map -> map.entrySet().clear())
        );
    }

    @ParameterizedTest
    @MethodSource("clearViewSource")
    public void testClearViaView(String viewName, Consumer<ContainerLifeCycleMap<String, TestLifeCycle>> clearOp) throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("key1", bean1);
        map.put("key2", bean2);

        clearOp.accept(map);

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

    @Test
    public void testCustomMapCaseInsensitive() throws Exception
    {
        // Use a TreeMap with case-insensitive key ordering
        ContainerLifeCycleMap<String, TestLifeCycle> map =
            new ContainerLifeCycleMap<>(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("gzip", bean1);

        assertEquals(bean1, map.get("GZIP"));
        assertEquals(bean1, map.get("Gzip"));
        assertEquals(bean1, map.get("gzip"));
        assertTrue(map.containsKey("GZIP"));
        assertTrue(map.containsKey("gzip"));

        map.put("GZIP", bean2);
        assertEquals(1, map.size());
        assertEquals(bean2, map.get("gzip"));
        assertFalse(map.contains(bean1));
        assertTrue(map.contains(bean2));

        assertEquals(bean2, map.remove("Gzip"));
        assertTrue(map.isEmpty());
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testCustomMapLifeCycleManagement() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map =
            new ContainerLifeCycleMap<>(new TreeMap<>(String.CASE_INSENSITIVE_ORDER));

        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.put("encoding1", bean1);
        map.put("encoding2", bean2);

        assertTrue(map.contains(bean1));
        assertTrue(map.contains(bean2));

        map.start();

        assertEquals(1, bean1.started.get());
        assertEquals(1, bean2.started.get());
        assertTrue(bean1.isStarted());
        assertTrue(bean2.isStarted());

        map.stop();

        assertEquals(1, bean1.stopped.get());
        assertEquals(1, bean2.stopped.get());
        assertTrue(bean1.isStopped());
        assertTrue(bean2.isStopped());
    }

    @Test
    public void testDump() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        map.put("key1", new TestLifeCycle("bean1"));

        StringWriter sw = new StringWriter();
        map.dump(sw, "");
        String dump = sw.toString();

        assertThat(dump, containsString("key1"));
        assertThat(dump, containsString("bean1"));
    }

    @Test
    public void testPutWhileRunning() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        map.start();

        TestLifeCycle bean = new TestLifeCycle("bean");
        map.put("key", bean);

        assertTrue(map.containsKey("key"));
        assertTrue(map.contains(bean));
    }

    @Test
    public void testNullValue()
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        map.put("key", null);
        assertTrue(map.containsKey("key"));
        assertNull(map.get("key"));
        assertNull(map.remove("key"));
        assertFalse(map.containsKey("key"));
    }

    @Test
    public void testEntrySetContains() throws Exception
    {
        ContainerLifeCycleMap<String, TestLifeCycle> map = new ContainerLifeCycleMap<>();
        TestLifeCycle bean = new TestLifeCycle("bean");
        map.put("key", bean);

        Map.Entry<String, TestLifeCycle> entry = Map.entry("key", bean);
        assertTrue(map.entrySet().contains(entry));
    }

    private static class TestLifeCycle extends AbstractLifeCycle
    {
        private final String _name;
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger stopped = new AtomicInteger();

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
