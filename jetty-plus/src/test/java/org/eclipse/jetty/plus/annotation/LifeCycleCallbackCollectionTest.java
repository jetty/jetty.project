//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.annotation;

import java.lang.reflect.Method;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.fail;

public class LifeCycleCallbackCollectionTest
{

    /**
     * An unsupported lifecycle callback type
     *
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
     * 
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
