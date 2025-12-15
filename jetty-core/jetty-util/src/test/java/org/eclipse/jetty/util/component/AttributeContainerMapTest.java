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

public class AttributeContainerMapTest
{
    @Test
    public void testSetGetRemoveAttribute() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        // Test setAttribute
        assertNull(map.setAttribute("key1", bean1));
        assertNull(map.setAttribute("key2", bean2));

        // Test getAttribute
        assertEquals(bean1, map.getAttribute("key1"));
        assertEquals(bean2, map.getAttribute("key2"));
        assertNull(map.getAttribute("nonexistent"));

        // Test getAttributeNameSet
        Set<String> names = map.getAttributeNameSet();
        assertThat(names, containsInAnyOrder("key1", "key2"));

        // Test removeAttribute
        assertEquals(bean1, map.removeAttribute("key1"));
        assertNull(map.getAttribute("key1"));
        assertNull(map.removeAttribute("nonexistent"));

        // Verify key1 is removed from names
        names = map.getAttributeNameSet();
        assertThat(names, containsInAnyOrder("key2"));
    }

    @Test
    public void testSetAttributeReplacesOldValue() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        assertNull(map.setAttribute("key", bean1));
        assertEquals(bean1, map.setAttribute("key", bean2));
        assertEquals(bean2, map.getAttribute("key"));
    }

    @Test
    public void testClearAttributes() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.setAttribute("key1", bean1);
        map.setAttribute("key2", bean2);

        map.clearAttributes();

        assertNull(map.getAttribute("key1"));
        assertNull(map.getAttribute("key2"));
        assertThat(map.getAttributeNameSet(), empty());
    }

    @Test
    public void testBeanAddedOnSetAttribute() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean = new TestLifeCycle("bean");

        map.setAttribute("key", bean);

        // Bean should be added to the container
        assertTrue(map.contains(bean));
    }

    @Test
    public void testBeanRemovedOnRemoveAttribute() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean = new TestLifeCycle("bean");

        map.setAttribute("key", bean);
        assertTrue(map.contains(bean));

        map.removeAttribute("key");
        assertFalse(map.contains(bean));
    }

    @Test
    public void testBeanReplacedOnSetAttribute() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.setAttribute("key", bean1);
        assertTrue(map.contains(bean1));
        assertFalse(map.contains(bean2));

        map.setAttribute("key", bean2);
        assertFalse(map.contains(bean1));
        assertTrue(map.contains(bean2));
    }

    @Test
    public void testBeansRemovedOnClearAttributes() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.setAttribute("key1", bean1);
        map.setAttribute("key2", bean2);
        assertTrue(map.contains(bean1));
        assertTrue(map.contains(bean2));

        map.clearAttributes();
        assertFalse(map.contains(bean1));
        assertFalse(map.contains(bean2));
    }

    @Test
    public void testLifeCycleManagement() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.setAttribute("key1", bean1);
        map.setAttribute("key2", bean2);

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
    public void testLifeCycleAddWhileRunning() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        TestLifeCycle bean1 = new TestLifeCycle("bean1");
        TestLifeCycle bean2 = new TestLifeCycle("bean2");

        map.setAttribute("key1", bean1);
        map.start();

        assertEquals(1, bean1.started.get());
        assertEquals(0, bean2.started.get());

        // Add bean2 while container is running
        map.setAttribute("key2", bean2);

        // bean2 is added as unmanaged since container is already started
        // The bean needs to be started separately or managed explicitly
        assertTrue(map.contains(bean2));
    }

    @Test
    public void testNonLifeCycleAttribute() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        String stringValue = "hello";

        map.setAttribute("key", stringValue);
        assertEquals(stringValue, map.getAttribute("key"));

        map.start();
        map.stop();

        // String value should still be accessible
        assertEquals(stringValue, map.getAttribute("key"));
    }

    @Test
    public void testToString() throws Exception
    {
        AttributeContainerMap map = new AttributeContainerMap();
        map.setAttribute("key1", "value1");
        map.setAttribute("key2", "value2");

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
