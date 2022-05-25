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

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class ClassLoaderDumpTest
{
    @Test
    public void testSimple() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        ClassLoader loader = new ClassLoader(null)
        {
            public String toString()
            {
                return "SimpleLoader";
            }
        };
        bean.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- SimpleLoader"));
        assertThat(dump, not(containsString("parent")));
    }

    @Test
    public void testCore() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        bean.addBean(new ClassLoaderDump(ClassLoaderDump.class.getClassLoader()));

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- " + ClassLoaderDump.class.getClassLoader()));
        assertThat(dump, containsString("packages size="));
        assertThat(dump, containsString("|  +> package org.eclipse.jetty.util"));
        if (ClassLoaderDump.class.getClassLoader().getParent() != null)
            assertThat(dump, containsString("+> parent: " + ClassLoaderDump.class.getClassLoader().getParent()));
    }

    @Test
    public void testParent() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        ClassLoader loader = new ClassLoader(ContainerLifeCycle.class.getClassLoader())
        {
            public String toString()
            {
                return "ParentedLoader";
            }
        };

        bean.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- ParentedLoader"));
        assertThat(dump, containsString("   +> parent(core): " + ContainerLifeCycle.class.getClassLoader()));
    }

    @Test
    public void testNested() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        ClassLoader middleLoader = new ClassLoader(ContainerLifeCycle.class.getClassLoader())
        {
            public String toString()
            {
                return "MiddleLoader";
            }
        };
        ClassLoader loader = new ClassLoader(middleLoader)
        {
            public String toString()
            {
                return "TopLoader";
            }
        };

        bean.addBean(new ClassLoaderDump(loader));
        bean.addBean("Other");

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("|  +> parent: MiddleLoader"));
        assertThat(dump, containsString("|     +> parent(core): " + ContainerLifeCycle.class.getClassLoader()));
        assertThat(dump, containsString("+- Other"));
    }

    @Test
    public void testDumpable() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        ClassLoader middleLoader = new DumpableClassLoader(ContainerLifeCycle.class.getClassLoader());
        ClassLoader loader = new ClassLoader(middleLoader)
        {
            public String toString()
            {
                return "TopLoader";
            }
        };

        bean.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("   +> parent: DumpableClassLoader"));
        assertThat(dump, not(containsString("     +> parent(core): " + ContainerLifeCycle.class.getClassLoader())));
    }

    public static class DumpableClassLoader extends ClassLoader implements Dumpable
    {
        public DumpableClassLoader(ClassLoader parent)
        {
            super(parent);
        }

        @Override
        public String dump()
        {
            return "DumpableClassLoader";
        }

        @Override
        public void dump(Appendable out, String indent) throws IOException
        {
            out.append(dump()).append('\n');
        }

        public String toString()
        {
            return "DumpableClassLoader";
        }
    }

    @Test
    public void testUrlClassLoaders() throws Exception
    {
        ContainerLifeCycle bean = new ContainerLifeCycle();
        ClassLoader middleLoader = new URLClassLoader(new URL[]
            {new URL("file:/one"), new URL("file:/two"), new URL("file:/three")},
            ContainerLifeCycle.class.getClassLoader())
        {
            public String toString()
            {
                return "MiddleLoader";
            }
        };
        ClassLoader loader = new URLClassLoader(new URL[]
            {new URL("file:/ONE"), new URL("file:/TWO"), new URL("file:/THREE")},
            middleLoader)
        {
            public String toString()
            {
                return "TopLoader";
            }
        };

        bean.addBean(new ClassLoaderDump(loader));
        bean.addBean(ContainerLifeCycle.class.getClassLoader());

        StringBuilder out = new StringBuilder();
        bean.dump(out);
        String dump = out.toString();
        // System.err.println(dump);
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("|  |  +> file:/ONE"));
        assertThat(dump, containsString("|  |  +> file:/TWO"));
        assertThat(dump, containsString("|  |  +> file:/THREE"));
        assertThat(dump, containsString("|  +> parent: MiddleLoader"));
        assertThat(dump, containsString("|     |  +> file:/one"));
        assertThat(dump, containsString("|     |  +> file:/two"));
        assertThat(dump, containsString("|     |  +> file:/three"));
        assertThat(dump, containsString("|     +> parent(core): " + ContainerLifeCycle.class.getClassLoader()));
        assertThat(dump, containsString("+- " + ContainerLifeCycle.class.getClassLoader()));
    }
}
