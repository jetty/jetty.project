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

package org.eclipse.jetty.plus.webapp;


import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;

import javax.naming.Context;
import javax.naming.InitialContext;

import org.eclipse.jetty.webapp.Descriptor;
import org.eclipse.jetty.webapp.FragmentDescriptor;
import org.eclipse.jetty.webapp.Origin;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebDescriptor;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PlusDescriptorProcessorTest
 */
public class PlusDescriptorProcessorTest
{
    protected WebDescriptor webDescriptor;
    protected FragmentDescriptor fragDescriptor1;
    protected FragmentDescriptor fragDescriptor2;
    protected FragmentDescriptor fragDescriptor3;
    protected FragmentDescriptor fragDescriptor4;
    protected WebAppContext context;
    
    @Before
    public void setUp() throws Exception
    {
        context = new WebAppContext();
        context.setClassLoader(new WebAppClassLoader(Thread.currentThread().getContextClassLoader(), context));
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        Context icontext = new InitialContext();
        Context compCtx =  (Context)icontext.lookup ("java:comp");
        compCtx.createSubcontext("env");
        Thread.currentThread().setContextClassLoader(oldLoader);

        org.eclipse.jetty.plus.jndi.Resource ds = new org.eclipse.jetty.plus.jndi.Resource (context, "jdbc/mydatasource", new Object());

        URL webXml = Thread.currentThread().getContextClassLoader().getResource("web.xml");
        webDescriptor = new WebDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(webXml));
        webDescriptor.parse();

        URL frag1Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-1.xml");
        fragDescriptor1 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag1Xml));
        fragDescriptor1.parse();
        URL frag2Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-2.xml");
        fragDescriptor2 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag2Xml));
        fragDescriptor2.parse();
        URL frag3Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-3.xml");
        fragDescriptor3 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag3Xml));
        fragDescriptor3.parse();
        URL frag4Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-4.xml");
        fragDescriptor4 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag4Xml));
        fragDescriptor4.parse();
    }

    @After
    public void tearDown() throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        Context ic = new InitialContext();
        Context compCtx =  (Context)ic.lookup ("java:comp");
        compCtx.destroySubcontext("env");
        Thread.currentThread().setContextClassLoader(oldLoader);
    }

    @Test
    public void testMissingResourceDeclaration()
    throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());

        try
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            pdp.process(context, fragDescriptor4);
            fail("Expected missing resource declaration");
        }
        catch (InvocationTargetException ex)
        {
            Throwable cause = ex.getCause();
            assertNotNull(cause);
            assertNotNull(cause.getMessage());
            assertTrue(cause.getMessage().contains("jdbc/mymissingdatasource"));
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    @Test
    public void testWebXmlResourceDeclarations()
    throws Exception
    {
        //if declared in web.xml, fragment declarations ignored
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        try
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            pdp.process(context, webDescriptor);
            Descriptor d = context.getMetaData().getOriginDescriptor("resource-ref.jdbc/mydatasource");
            assertNotNull(d);
            assertTrue(d == webDescriptor);

            pdp.process(context, fragDescriptor1);
            pdp.process(context, fragDescriptor2);
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }


    @Test
    public void testMismatchedFragmentResourceDeclarations ()
    throws Exception
    {
        //if declared in more than 1 fragment, declarations must be the same
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        try
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            pdp.process(context, fragDescriptor1);
            Descriptor d = context.getMetaData().getOriginDescriptor("resource-ref.jdbc/mydatasource");
            assertNotNull(d);
            assertTrue(d == fragDescriptor1);
            assertEquals(Origin.WebFragment, context.getMetaData().getOrigin("resource-ref.jdbc/mydatasource"));

            pdp.process(context, fragDescriptor2);
            fail("Expected conflicting resource-ref declaration");
        }
        catch (Exception e)
        {
            //expected
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    @Test
    public void testMatchingFragmentResourceDeclarations ()
    throws Exception
    {
        //if declared in more than 1 fragment, declarations must be the same
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        try
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            pdp.process(context, fragDescriptor1);
            Descriptor d = context.getMetaData().getOriginDescriptor("resource-ref.jdbc/mydatasource");
            assertNotNull(d);
            assertTrue(d == fragDescriptor1);
            assertEquals(Origin.WebFragment, context.getMetaData().getOrigin("resource-ref.jdbc/mydatasource"));
            pdp.process(context, fragDescriptor3);
        }

        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }
}
