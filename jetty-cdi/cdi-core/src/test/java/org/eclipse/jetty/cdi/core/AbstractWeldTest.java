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

package org.eclipse.jetty.cdi.core;

import java.util.Set;

import javax.enterprise.context.spi.CreationalContext;
import javax.enterprise.inject.spi.Bean;

import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.AfterClass;
import org.junit.BeforeClass;

public abstract class AbstractWeldTest
{
    public static class TestBean<T>
    {
        public Bean<T> bean;
        public CreationalContext<T> cCtx;
        public T instance;

        public void destroy()
        {
            bean.destroy(instance,cCtx);
        }
    }

    @BeforeClass
    public static void initWeld()
    {
        weld = new Weld();
        container = weld.initialize();
    }

    @AfterClass
    public static void shutdownWeld()
    {
        weld.shutdown();
    }

    private static WeldContainer container;
    private static Weld weld;

    @SuppressWarnings("unchecked")
    public <T> TestBean<T> newInstance(Class<T> clazz) throws Exception
    {
        TestBean<T> testBean = new TestBean<>();
        Set<Bean<?>> beans = container.getBeanManager().getBeans(clazz,AnyLiteral.INSTANCE);
        if (beans.size() > 0)
        {
            testBean.bean = (Bean<T>)beans.iterator().next();
            testBean.cCtx = container.getBeanManager().createCreationalContext(testBean.bean);
            testBean.instance = (T)container.getBeanManager().getReference(testBean.bean,clazz,testBean.cCtx);
            return testBean;
        }
        else
        {
            throw new Exception(String.format("Can't find class %s",clazz));
        }
    }
}
