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

package org.eclipse.jetty.util.component;

import java.util.ArrayList;
import java.util.List;

import org.hamcrest.Matcher;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Testing for LifeCycleListener events on nested components
 * during runtime.
 */
@Disabled
public class LifeCycleListenerNestedTest
{
    // Set this true to use test-specific workaround.
    private final boolean workaround = false;

    public static class Foo extends ContainerLifeCycle
    {
        @Override
        public String toString()
        {
            return Foo.class.getSimpleName();
        }
    }

    public static class Bar extends ContainerLifeCycle
    {
        private final String id;

        public Bar(String id)
        {
            this.id = id;
        }

        public String getId()
        {
            return id;
        }

        @Override
        public int hashCode()
        {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((id == null) ? 0 : id.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            Bar other = (Bar)obj;
            if (id == null)
            {
                if (other.id != null)
                    return false;
            }
            else if (!id.equals(other.id))
                return false;
            return true;
        }

        @Override
        public String toString()
        {
            return Bar.class.getSimpleName() + "(" + id + ")";
        }
    }

    public static enum LifeCycleEvent
    {
        STARTING,
        STARTED,
        FAILURE,
        STOPPING,
        STOPPED
    }

    public static class CapturingListener implements LifeCycle.Listener, Container.InheritedListener
    {
        private List<String> events = new ArrayList<>();

        private void addEvent(Object obj, LifeCycleEvent event)
        {
            events.add(String.format("%s - %s", obj.toString(), event.name()));
        }

        @Override
        public void lifeCycleStarting(LifeCycle event)
        {
            addEvent(event, LifeCycleEvent.STARTING);
        }

        @Override
        public void lifeCycleStarted(LifeCycle event)
        {
            addEvent(event, LifeCycleEvent.STARTED);
        }

        @Override
        public void lifeCycleFailure(LifeCycle event, Throwable cause)
        {
            addEvent(event, LifeCycleEvent.FAILURE);
        }

        @Override
        public void lifeCycleStopping(LifeCycle event)
        {
            addEvent(event, LifeCycleEvent.STOPPING);
        }

        @Override
        public void lifeCycleStopped(LifeCycle event)
        {
            addEvent(event, LifeCycleEvent.STOPPED);
        }

        public List<String> getEvents()
        {
            return events;
        }

        public void assertEvents(Matcher<Iterable<? super String>> matcher)
        {
            assertThat(events, matcher);
        }

        @Override
        public void beanAdded(Container parent, Object child)
        {
            if (child instanceof LifeCycle)
            {
                ((LifeCycle)child).addEventListener(this);
            }
        }

        @Override
        public void beanRemoved(Container parent, Object child)
        {
            if (child instanceof LifeCycle)
            {
                ((LifeCycle)child).removeEventListener(this);
            }
        }
    }

    @Test
    public void testAddBeanAddListenerStart() throws Exception
    {
        Foo foo = new Foo();
        Bar bara = new Bar("a");
        Bar barb = new Bar("b");
        foo.addBean(bara);
        foo.addBean(barb);

        CapturingListener listener = new CapturingListener();
        foo.addEventListener(listener);
        if (workaround)
            foo.addEventListener(listener);

        try
        {
            foo.start();

            assertThat("Foo.started", foo.isStarted(), is(true));
            assertThat("Bar(a).started", bara.isStarted(), is(true));
            assertThat("Bar(b).started", barb.isStarted(), is(true));

            listener.assertEvents(hasItem("Foo - STARTING"));
            listener.assertEvents(hasItem("Foo - STARTED"));
            listener.assertEvents(hasItem("Bar(a) - STARTING"));
            listener.assertEvents(hasItem("Bar(a) - STARTED"));
            listener.assertEvents(hasItem("Bar(b) - STARTING"));
            listener.assertEvents(hasItem("Bar(b) - STARTED"));
        }
        finally
        {
            foo.stop();
        }
    }

    @Test
    public void testAddListenerAddBeanStart() throws Exception
    {
        Foo foo = new Foo();

        CapturingListener listener = new CapturingListener();
        foo.addEventListener(listener);
        if (workaround)
            foo.addEventListener(listener);

        Bar bara = new Bar("a");
        Bar barb = new Bar("b");
        foo.addBean(bara);
        foo.addBean(barb);

        try
        {
            foo.start();

            assertThat("Foo.started", foo.isStarted(), is(true));
            assertThat("Bar(a).started", bara.isStarted(), is(true));
            assertThat("Bar(b).started", barb.isStarted(), is(true));

            listener.assertEvents(hasItem("Foo - STARTING"));
            listener.assertEvents(hasItem("Foo - STARTED"));
            listener.assertEvents(hasItem("Bar(a) - STARTING"));
            listener.assertEvents(hasItem("Bar(a) - STARTED"));
            listener.assertEvents(hasItem("Bar(b) - STARTING"));
            listener.assertEvents(hasItem("Bar(b) - STARTED"));
        }
        finally
        {
            foo.stop();
        }
    }

    @Test
    public void testAddListenerStartAddBean() throws Exception
    {
        Foo foo = new Foo();
        Bar bara = new Bar("a");
        Bar barb = new Bar("b");

        CapturingListener listener = new CapturingListener();
        foo.addEventListener(listener);
        if (workaround)
            foo.addEventListener(listener);

        try
        {
            foo.start();

            listener.assertEvents(hasItem("Foo - STARTING"));
            listener.assertEvents(hasItem("Foo - STARTED"));

            foo.addBean(bara);
            foo.addBean(barb);

            bara.start();
            barb.start();

            assertThat("Bar(a).started", bara.isStarted(), is(true));
            assertThat("Bar(b).started", barb.isStarted(), is(true));

            listener.assertEvents(hasItem("Bar(a) - STARTING"));
            listener.assertEvents(hasItem("Bar(a) - STARTED"));
            listener.assertEvents(hasItem("Bar(b) - STARTING"));
            listener.assertEvents(hasItem("Bar(b) - STARTED"));
        }
        finally
        {
            barb.stop();
            bara.stop();
            foo.stop();
        }
    }
}
