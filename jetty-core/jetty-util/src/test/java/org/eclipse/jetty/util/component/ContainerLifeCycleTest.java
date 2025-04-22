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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.util.Collection;
import java.util.EventListener;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ContainerLifeCycleTest
{
    @Test
    public void testStartStop() throws Exception
    {
        ContainerLifeCycle a0 = new NamedContainerLifeCycle("a0");
        TestContainerLifeCycle a1 = new TestContainerLifeCycle("a1");
        a0.addBean(a1);

        a0.start();
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.start();
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.stop();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.start();
        assertEquals(2, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.stop();
        assertEquals(2, a1.started.get());
        assertEquals(2, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());
    }

    @Test
    public void testStartStopDestroy() throws Exception
    {
        ContainerLifeCycle a0 = new NamedContainerLifeCycle("a0");
        TestContainerLifeCycle a1 = new TestContainerLifeCycle("a1");

        a0.start();
        assertEquals(0, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.addBean(a1);
        assertEquals(0, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());
        assertFalse(a0.isManaged(a1));

        a0.start();
        assertEquals(0, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a1.start();
        a0.manage(a1);
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.removeBean(a1);
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.stop();
        a0.destroy();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a1.stop();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a1.destroy();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(1, a1.destroyed.get());
    }

    @Test
    public void testIllegalToStartAfterDestroy() throws Exception
    {
        ContainerLifeCycle container = new ContainerLifeCycle();
        container.start();
        container.stop();
        container.destroy();

        assertThrows(IllegalStateException.class, container::start);
    }

    @Test
    public void testDisJoint() throws Exception
    {
        ContainerLifeCycle a0 = new NamedContainerLifeCycle("a0");
        TestContainerLifeCycle a1 = new TestContainerLifeCycle("a1");

        // Start the a1 bean before adding, makes it auto disjoint
        a1.start();

        // Now add it
        a0.addBean(a1);
        assertFalse(a0.isManaged(a1));

        a0.start();
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.start();
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.stop();
        assertEquals(1, a1.started.get());
        assertEquals(0, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a1.stop();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.start();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.manage(a1);
        assertTrue(a0.isManaged(a1));

        a0.stop();
        assertEquals(1, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.start();
        assertEquals(2, a1.started.get());
        assertEquals(1, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.stop();
        assertEquals(2, a1.started.get());
        assertEquals(2, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a0.unmanage(a1);
        assertFalse(a0.isManaged(a1));

        a0.destroy();
        assertEquals(2, a1.started.get());
        assertEquals(2, a1.stopped.get());
        assertEquals(0, a1.destroyed.get());

        a1.destroy();
        assertEquals(2, a1.started.get());
        assertEquals(2, a1.stopped.get());
        assertEquals(1, a1.destroyed.get());
    }

    @Test
    public void testDumpable() throws Exception
    {
        org.eclipse.jetty.util.component.ContainerLifeCycle a0 = new NamedContainerLifeCycle("a0");
        String dump = trim(a0.dump());
        check(dump, "oejuc.ContainerLifeCycl");

        org.eclipse.jetty.util.component.ContainerLifeCycle aa0 = new NamedContainerLifeCycle("aa0");
        a0.addBean(aa0);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        check(dump, "+? oejuc.ContainerLife");

        org.eclipse.jetty.util.component.ContainerLifeCycle aa1 = new NamedContainerLifeCycle("aa1");
        a0.addBean(aa1);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+? oejuc.ContainerLife");
        dump = check(dump, "+? oejuc.ContainerLife");
        check(dump, "");

        org.eclipse.jetty.util.component.ContainerLifeCycle aa2 = new NamedContainerLifeCycle("aa2");
        a0.addBean(aa2, false);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+? oejuc.ContainerLife");
        dump = check(dump, "+? oejuc.ContainerLife");
        dump = check(dump, "+~ oejuc.ContainerLife");
        check(dump, "");

        aa1.start();
        a0.start();
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "+~ oejuc.ContainerLife");
        dump = check(dump, "+~ oejuc.ContainerLife");
        check(dump, "");

        a0.manage(aa1);
        a0.removeBean(aa2);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "+= oejuc.ContainerLife");
        check(dump, "");

        org.eclipse.jetty.util.component.ContainerLifeCycle aaa0 = new NamedContainerLifeCycle("aaa0");
        aa0.addBean(aaa0);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        check(dump, "");

        org.eclipse.jetty.util.component.ContainerLifeCycle aa10 = new NamedContainerLifeCycle("aa10");
        aa1.addBean(aa10, true);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "   += oejuc.Container");
        check(dump, "");

        final org.eclipse.jetty.util.component.ContainerLifeCycle a1 = new NamedContainerLifeCycle("a1");
        final org.eclipse.jetty.util.component.ContainerLifeCycle a2 = new NamedContainerLifeCycle("a2");
        final org.eclipse.jetty.util.component.ContainerLifeCycle a3 = new NamedContainerLifeCycle("a3");
        final org.eclipse.jetty.util.component.ContainerLifeCycle a4 = new NamedContainerLifeCycle("a4");

        org.eclipse.jetty.util.component.ContainerLifeCycle aa = new NamedContainerLifeCycle("aa")
        {
            @Override
            public void dump(Appendable out, String indent) throws IOException
            {
                Dumpable.dumpObjects(out, indent, this.toString(), TypeUtil.asList(new Object[]{
                    a1, a2
                }), TypeUtil.asList(new Object[]{a3, a4}));
            }
        };
        a0.addBean(aa, true);

        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  += oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        check(dump, "");

        a2.addBean(aa0, true);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  += oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   |     +=@ oejuc.Conta");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        check(dump, "");

        a2.unmanage(aa0);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  += oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   |  +: oejuc.ContainerLifeCycle");
        dump = check(dump, "   |     +~@ oejuc.Conta");
        dump = check(dump, "   +> ju.Arrays$ArrayList");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        dump = check(dump, "      +: oejuc.ContainerLifeCycle");
        check(dump, "");

        a0.unmanage(aa);
        dump = trim(a0.dump());
        dump = check(dump, "oejuc.ContainerLifeCycl");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  +~ oejuc.Container");
        dump = check(dump, "+= oejuc.ContainerLife");
        dump = check(dump, "|  += oejuc.Container");
        dump = check(dump, "+~ oejuc.ContainerLife");
        check(dump, "");
    }

    @Test
    public void listenerTest() throws Exception
    {
        final Queue<String> handled = new ConcurrentLinkedQueue<>();
        final Queue<String> operation = new ConcurrentLinkedQueue<>();
        final Queue<Container> parent = new ConcurrentLinkedQueue<>();
        final Queue<Object> child = new ConcurrentLinkedQueue<>();

        Container.Listener listener = new Container.Listener()
        {
            @Override
            public void beanRemoved(Container p, Object c)
            {
                handled.add(toString());
                operation.add("removed");
                parent.add(p);
                child.add(c);
            }

            @Override
            public void beanAdded(Container p, Object c)
            {
                handled.add(toString());
                operation.add("added");
                parent.add(p);
                child.add(c);
            }

            @Override
            public String toString()
            {
                return "listener";
            }
        };

        ContainerLifeCycle c0 = new NamedContainerLifeCycle("c0")
        {
            @Override
            public String toString()
            {
                return "c0";
            }
        };
        ContainerLifeCycle c00 = new NamedContainerLifeCycle("c00")
        {
            @Override
            public String toString()
            {
                return "c00";
            }
        };
        c0.addBean(c00);
        String b000 = "b000";
        c00.addBean(b000);

        c0.addBean(listener);

        assertEquals("listener", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(c00, child.poll());

        assertEquals("listener", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(listener, child.poll());

        Container.InheritedListener inherited = new Container.InheritedListener()
        {
            @Override
            public void beanRemoved(Container p, Object c)
            {
                handled.add(toString());
                operation.add("removed");
                parent.add(p);
                child.add(c);
            }

            @Override
            public void beanAdded(Container p, Object c)
            {
                handled.add(toString());
                operation.add("added");
                parent.add(p);
                child.add(c);
            }

            @Override
            public String toString()
            {
                return "inherited";
            }
        };

        c0.addBean(inherited);

        assertEquals("listener", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(inherited, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(c00, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(listener, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(inherited, child.poll());

        c0.start();

        assertEquals("inherited", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c00, parent.poll());
        assertEquals(b000, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("added", operation.poll());
        assertEquals(c00, parent.poll());
        assertEquals(inherited, child.poll());

        c0.removeBean(c00);

        assertEquals("inherited", handled.poll());
        assertEquals("removed", operation.poll());
        assertEquals(c00, parent.poll());
        assertEquals(inherited, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("removed", operation.poll());
        assertEquals(c00, parent.poll());
        assertEquals(b000, child.poll());

        assertEquals("listener", handled.poll());
        assertEquals("removed", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(c00, child.poll());

        assertEquals("inherited", handled.poll());
        assertEquals("removed", operation.poll());
        assertEquals(c0, parent.poll());
        assertEquals(c00, child.poll());
    }

    private static final class InheritedListenerLifeCycle extends AbstractLifeCycle implements Container.InheritedListener
    {
        @Override
        public void beanRemoved(Container p, Object c)
        {
        }

        @Override
        public void beanAdded(Container p, Object c)
        {
        }

        @Override
        public String toString()
        {
            return "inherited";
        }
    }

    @Test
    public void testInheritedListener() throws Exception
    {
        ContainerLifeCycle c0 = new NamedContainerLifeCycle("c0");
        ContainerLifeCycle c00 = new NamedContainerLifeCycle("c00");
        ContainerLifeCycle c01 = new NamedContainerLifeCycle("c01");
        Container.InheritedListener inherited = new InheritedListenerLifeCycle();

        c0.addBean(c00);
        c0.start();
        c0.addBean(inherited);
        c0.manage(inherited);
        c0.addBean(c01);
        c01.start();
        c0.manage(c01);

        assertTrue(c0.isManaged(inherited));
        assertFalse(c00.isManaged(inherited));
        assertFalse(c01.isManaged(inherited));
    }

    String trim(String s) throws IOException
    {
        StringBuilder b = new StringBuilder();
        BufferedReader reader = new BufferedReader(new StringReader(s));

        for (String line = reader.readLine(); line != null; line = reader.readLine())
        {
            if (line.length() > 50)
                line = line.substring(0, 50);
            b.append(line).append('\n');
        }

        return b.toString();
    }

    String check(String s, String x)
    {
        String r = s;
        int nl = s.indexOf('\n');
        if (nl > 0)
        {
            r = s.substring(nl + 1);
            s = s.substring(0, nl);
        }

        assertThat(s, Matchers.startsWith(x));

        return r;
    }

    private static class TestContainerLifeCycle extends NamedContainerLifeCycle
    {
        private final AtomicInteger destroyed = new AtomicInteger();
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger stopped = new AtomicInteger();

        private TestContainerLifeCycle(String name)
        {
            super(name);
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
        public void destroy()
        {
            destroyed.incrementAndGet();
            super.destroy();
        }
    }

    @Test
    public void testGetBeans()
    {
        TestContainerLifeCycle root = new TestContainerLifeCycle("root");
        TestContainerLifeCycle left = new TestContainerLifeCycle("left");
        root.addBean(left);
        TestContainerLifeCycle right = new TestContainerLifeCycle("right");
        root.addBean(right);
        TestContainerLifeCycle leaf = new TestContainerLifeCycle("leaf");
        right.addBean(leaf);

        root.addBean(0);
        root.addBean(1);
        left.addBean(2);
        right.addBean(3);
        leaf.addBean(4);
        leaf.addBean("leaf");

        assertThat(root.getBeans(Container.class), containsInAnyOrder(left, right));
        assertThat(root.getBeans(Integer.class), containsInAnyOrder(0, 1));
        assertThat(root.getBeans(String.class), containsInAnyOrder());

        assertThat(root.getContainedBeans(Container.class), containsInAnyOrder(left, right, leaf));
        assertThat(root.getContainedBeans(Integer.class), containsInAnyOrder(0, 1, 2, 3, 4));
        assertThat(root.getContainedBeans(String.class), containsInAnyOrder("leaf"));
    }

    @Test
    public void testBeanStoppingAddedToStartingBean() throws Exception
    {
        ContainerLifeCycle longLived = new ContainerLifeCycle()
        {
            @Override
            protected void doStop() throws Exception
            {
                super.doStop();

                ContainerLifeCycle shortLived = new ContainerLifeCycle();
                shortLived.addBean(this);
                shortLived.start();

                assertTrue(shortLived.isStarted());
                assertTrue(isStopping());
                assertFalse(shortLived.isManaged(this));
            }
        };
        longLived.start();
        longLived.stop();
    }

    @Test
    public void testFailedManagedBeanCanBeRestarted() throws Exception
    {
        AtomicBoolean fail = new AtomicBoolean();
        ContainerLifeCycle container = new NamedContainerLifeCycle("container");
        ContainerLifeCycle bean1 = new NamedContainerLifeCycle("bean1");
        ContainerLifeCycle bean2 = new NamedContainerLifeCycle("bean2")
        {
            @Override
            protected void doStart() throws Exception
            {
                super.doStart();
                // Fail only the first time.
                if (fail.compareAndSet(false, true))
                    throw new RuntimeException();
            }
        };
        ContainerLifeCycle bean3 = new NamedContainerLifeCycle("bean3");
        container.addBean(bean1);
        container.addBean(bean2);
        container.addBean(bean3);

        // Start the first time, it should fail.
        assertThrows(RuntimeException.class, container::start);
        assertTrue(container.isFailed());
        assertTrue(bean1.isStopped());
        assertTrue(bean2.isFailed());
        assertTrue(bean3.isStopped());

        // Re-start, it should succeed.
        container.start();
        assertTrue(container.isStarted());
        assertTrue(bean1.isStarted());
        assertTrue(bean2.isStarted());
        assertTrue(bean3.isStarted());
    }

    @Test
    public void testFailedAutoBeanIsNotRestarted() throws Exception
    {
        AtomicBoolean fail = new AtomicBoolean();
        ContainerLifeCycle bean = new NamedContainerLifeCycle("bean")
        {
            @Override
            protected void doStart() throws Exception
            {
                super.doStart();
                // Fail only the first time.
                if (fail.compareAndSet(false, true))
                    throw new RuntimeException();
            }
        };
        // The bean is started externally and fails.
        assertThrows(RuntimeException.class, bean::start);

        // The same bean now becomes part of a container.
        ContainerLifeCycle container = new NamedContainerLifeCycle("container");
        container.addBean(bean);
        assertTrue(container.isAuto(bean));

        // Start the container, the bean must not be managed.
        container.start();
        assertTrue(container.isStarted());
        assertTrue(bean.isFailed());
        assertTrue(container.isUnmanaged(bean));
    }

    @Test
    public void testInstallBeanThatImplementsEventListener()
    {
        class Bean implements EventListener
        {
        }

        ContainerLifeCycle container = new ContainerLifeCycle();
        container.installBean(new Bean());

        Collection<Bean> beans = container.getBeans(Bean.class);
        assertEquals(1, beans.size());
    }

    private static class NamedContainerLifeCycle extends org.eclipse.jetty.util.component.ContainerLifeCycle
    {
        private final String _name;

        private NamedContainerLifeCycle(String name)
        {
            _name = name;
        }

        @Override
        public String toString()
        {
            return super.toString() + ":" + _name;
        }
    }
}
