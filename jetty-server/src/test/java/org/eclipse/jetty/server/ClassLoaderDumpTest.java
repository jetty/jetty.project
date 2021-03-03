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

package org.eclipse.jetty.server;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;

import org.eclipse.jetty.util.component.Dumpable;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

public class ClassLoaderDumpTest
{
    @Test
    public void testSimple() throws Exception
    {
        Server server = new Server();
        ClassLoader loader = new ClassLoader()
        {
            public String toString()
            {
                return "SimpleLoader";
            }
        };

        server.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        server.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- SimpleLoader"));
        assertThat(dump, containsString("+> " + Server.class.getClassLoader()));
    }

    @Test
    public void testParent() throws Exception
    {
        Server server = new Server();
        ClassLoader loader = new ClassLoader(Server.class.getClassLoader())
        {
            public String toString()
            {
                return "ParentedLoader";
            }
        };

        server.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        server.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- ParentedLoader"));
        assertThat(dump, containsString("|  +> " + Server.class.getClassLoader()));
        assertThat(dump, containsString("+> " + Server.class.getClassLoader()));
    }

    @Test
    public void testNested() throws Exception
    {
        Server server = new Server();
        ClassLoader middleLoader = new ClassLoader(Server.class.getClassLoader())
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

        server.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        server.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("|  +> MiddleLoader"));
        assertThat(dump, containsString("|     +> " + Server.class.getClassLoader()));
        assertThat(dump, containsString("+> " + Server.class.getClassLoader()));
    }

    @Test
    public void testDumpable() throws Exception
    {
        Server server = new Server();
        ClassLoader middleLoader = new DumpableClassLoader(Server.class.getClassLoader());
        ClassLoader loader = new ClassLoader(middleLoader)
        {
            public String toString()
            {
                return "TopLoader";
            }
        };

        server.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        server.dump(out);
        String dump = out.toString();
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("|  +> DumpableClassLoader"));
        assertThat(dump, not(containsString("|    +> " + Server.class.getClassLoader())));
        assertThat(dump, containsString("+> " + Server.class.getClassLoader()));
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
        Server server = new Server();
        ClassLoader middleLoader = new URLClassLoader(new URL[]
            {new URL("file:/one"), new URL("file:/two"), new URL("file:/three")},
            Server.class.getClassLoader())
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

        server.addBean(new ClassLoaderDump(loader));

        StringBuilder out = new StringBuilder();
        server.dump(out);
        String dump = out.toString();
        // System.err.println(dump);
        assertThat(dump, containsString("+- TopLoader"));
        assertThat(dump, containsString("|  |  +> file:/ONE"));
        assertThat(dump, containsString("|  |  +> file:/TWO"));
        assertThat(dump, containsString("|  |  +> file:/THREE"));
        assertThat(dump, containsString("|  +> MiddleLoader"));
        assertThat(dump, containsString("|     |  +> file:/one"));
        assertThat(dump, containsString("|     |  +> file:/two"));
        assertThat(dump, containsString("|     |  +> file:/three"));
        assertThat(dump, containsString("|     +> " + Server.class.getClassLoader()));
        assertThat(dump, containsString("+> " + Server.class.getClassLoader()));
    }
}
