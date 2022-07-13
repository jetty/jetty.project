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

package org.eclipse.jetty.ee9.plus.annotation;

import java.lang.reflect.Method;

import jakarta.servlet.http.HttpServlet;
import org.eclipse.jetty.ee9.servlet.ServletHolder;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

public class LifeCycleCallbackCollectionTest
{
    public static class TestServlet extends HttpServlet
    {
        public static int postConstructCount = 0;
        public static int preDestroyCount = 0;

        public void postconstruct()
        {
            ++postConstructCount;
        }

        public void predestroy()
        {
            ++preDestroyCount;
        }
    }

    /**
     * An unsupported lifecycle callback type
     */
    public class TestLifeCycleCallback extends LifeCycleCallback
    {
        public TestLifeCycleCallback(Class<?> clazz, String methodName)
        {
            super(clazz, methodName);
        }

        public TestLifeCycleCallback(String className, String methodName)
        {
            super(className, methodName);
        }

        @Override
        public void validate(Class<?> clazz, Method m)
        {
            throw new IllegalStateException("TEST!");
        }
    }

    /**
     * A class that we can use to simulate having PostConstruct and
     * PreDestroy annotations on.
     */
    public class SomeTestClass
    {
        public void afterConstruct()
        {
            //Empty method, we just want to refer to its name
        }
    }

    @Test
    public void testAddForPostConstruct() throws Exception
    {
        //test empty PostConstruct
        String nullName = null;
        Class<?> clazz = null;
        PostConstructCallback pc1 = null;
        try
        {
            pc1 = new PostConstructCallback(nullName, null);
            fail("Null class arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PostConstructCallback(clazz, null);
            fail("Null class arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PostConstructCallback(SomeTestClass.class, null);
            fail("Null method arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PostConstructCallback("foo", null);
            fail("Null method arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        LifeCycleCallbackCollection collection = new LifeCycleCallbackCollection();
        //test ignoring duplicate adds for callbacks for same classname and method
        PostConstructCallback pc2 = new PostConstructCallback("foo", "bar");
        collection.add(pc2);
        assertThat(collection.getPostConstructCallbackMap().get("foo"), Matchers.contains(pc2));

        PostConstructCallback pc3 = new PostConstructCallback("foo", "bar");
        collection.add(pc3);
        assertThat(collection.getPostConstructCallbackMap().get("foo"), Matchers.contains(pc2));
        assertThat(collection.getPostConstructCallbackMap().values(), hasSize(1));

        //test ignoring duplicate adds by class and method name
        collection = new LifeCycleCallbackCollection();

        PostConstructCallback pc4 = new PostConstructCallback(SomeTestClass.class, "afterConstruct");
        collection.add(pc4);
        assertThat(collection.getPostConstructCallbackMap().get(SomeTestClass.class.getName()), Matchers.contains(pc4));
        assertThat(collection.getPostConstructCallbackMap().values(), hasSize(1));

        PostConstructCallback pc5 = new PostConstructCallback(SomeTestClass.class, "afterConstruct");
        collection.add(pc5);
        assertThat(collection.getPostConstructCallbackMap().get(SomeTestClass.class.getName()), Matchers.contains(pc4));
        assertThat(collection.getPostConstructCallbackMap().values(), hasSize(1));
    }

    @Test
    public void testUnsupportedType() throws Exception
    {
        //test that we currently only support PostConstruct and PreDestroy
        LifeCycleCallbackCollection collection = new LifeCycleCallbackCollection();
        try
        {
            TestLifeCycleCallback tcb = new TestLifeCycleCallback("abc", "def");
            collection.add(tcb);
            fail("Support only PostConstruct and PreDestroy");
        }
        catch (IllegalArgumentException e)
        {
            //expected
        }
    }

    @Test
    public void testServletPostConstructPreDestroy() throws Exception
    {
        Server server = new Server();
        WebAppContext context = new WebAppContext();
        context.setResourceBase(MavenTestingUtils.getTargetTestingDir("predestroy-test").toURI().toURL().toString());
        context.setContextPath("/");
        server.setHandler(context);

        //add a non-async servlet
        ServletHolder notAsync = new ServletHolder();
        notAsync.setHeldClass(TestServlet.class);
        notAsync.setName("notAsync");
        notAsync.setAsyncSupported(false);
        notAsync.setInitOrder(1);
        context.getServletHandler().addServletWithMapping(notAsync, "/notasync/*");

        //add an async servlet
        ServletHolder async = new ServletHolder();
        async.setHeldClass(TestServlet.class);
        async.setName("async");
        async.setAsyncSupported(true);
        async.setInitOrder(1);
        context.getServletHandler().addServletWithMapping(async, "/async/*");

        //add a run-as servlet
        ServletHolder runas = new ServletHolder();
        runas.setHeldClass(TestServlet.class);
        runas.setName("runas");
        runas.setRunAsRole("admin");
        runas.setInitOrder(1);
        context.getServletHandler().addServletWithMapping(runas, "/runas/*");

        //add both run-as and non async servlet
        ServletHolder both = new ServletHolder();
        both.setHeldClass(TestServlet.class);
        both.setName("both");
        both.setRunAsRole("admin");
        both.setAsyncSupported(false);
        both.setInitOrder(1);
        context.getServletHandler().addServletWithMapping(both, "/both/*");

        //Make fake lifecycle callbacks for all servlets
        LifeCycleCallbackCollection collection = new LifeCycleCallbackCollection();
        context.setAttribute(LifeCycleCallbackCollection.LIFECYCLE_CALLBACK_COLLECTION, collection);
        PostConstructCallback pcNotAsync = new PostConstructCallback(TestServlet.class, "postconstruct");
        collection.add(pcNotAsync);
        PreDestroyCallback pdNotAsync = new PreDestroyCallback(TestServlet.class, "predestroy");
        collection.add(pdNotAsync);

        PostConstructCallback pcAsync = new PostConstructCallback(TestServlet.class, "postconstruct");
        collection.add(pcAsync);
        PreDestroyCallback pdAsync = new PreDestroyCallback(TestServlet.class, "predestroy");
        collection.add(pdAsync);

        PostConstructCallback pcRunAs = new PostConstructCallback(TestServlet.class, "postconstruct");
        collection.add(pcRunAs);
        PreDestroyCallback pdRunAs = new PreDestroyCallback(TestServlet.class, "predestroy");
        collection.add(pdRunAs);

        PostConstructCallback pcBoth = new PostConstructCallback(TestServlet.class, "postconstruct");
        collection.add(pcBoth);
        PreDestroyCallback pdBoth = new PreDestroyCallback(TestServlet.class, "predestroy");
        collection.add(pdBoth);

        server.start();

        assertEquals(4, TestServlet.postConstructCount);

        server.stop();

        assertEquals(4, TestServlet.preDestroyCount);
    }

    @Test
    public void testAddForPreDestroy() throws Exception
    {
        //test empty PreDestroy
        String nullName = null;
        Class<?> clazz = null;
        PreDestroyCallback pc1 = null;
        try
        {
            pc1 = new PreDestroyCallback(nullName, null);
            fail("Null class arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PreDestroyCallback(clazz, null);
            fail("Null class arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PreDestroyCallback(SomeTestClass.class, null);
            fail("Null method arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        try
        {
            pc1 = new PreDestroyCallback("foo", null);
            fail("Null method arg should not be allowed");
        }
        catch (NullPointerException e)
        {
            //expected
        }

        LifeCycleCallbackCollection collection = new LifeCycleCallbackCollection();
        //test ignoring duplicate adds for callbacks for same classname and method
        PreDestroyCallback pc2 = new PreDestroyCallback("foo", "bar");
        collection.add(pc2);
        assertThat(collection.getPreDestroyCallbackMap().get("foo"), Matchers.contains(pc2));

        PreDestroyCallback pc3 = new PreDestroyCallback("foo", "bar");
        collection.add(pc3);
        assertThat(collection.getPreDestroyCallbackMap().get("foo"), Matchers.contains(pc2));
        assertThat(collection.getPreDestroyCallbackMap().values(), hasSize(1));

        //test ignoring duplicate adds by class and method name
        collection = new LifeCycleCallbackCollection();

        PreDestroyCallback pc4 = new PreDestroyCallback(SomeTestClass.class, "afterConstruct");
        collection.add(pc4);
        assertThat(collection.getPreDestroyCallbackMap().get(SomeTestClass.class.getName()), Matchers.contains(pc4));
        assertThat(collection.getPreDestroyCallbackMap().values(), hasSize(1));

        PreDestroyCallback pc5 = new PreDestroyCallback(SomeTestClass.class, "afterConstruct");
        collection.add(pc5);
        assertThat(collection.getPreDestroyCallbackMap().get(SomeTestClass.class.getName()), Matchers.contains(pc4));
        assertThat(collection.getPreDestroyCallbackMap().values(), hasSize(1));
    }
}
