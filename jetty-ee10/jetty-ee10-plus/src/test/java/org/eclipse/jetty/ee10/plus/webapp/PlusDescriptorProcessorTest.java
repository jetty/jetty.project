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

package org.eclipse.jetty.ee10.plus.webapp;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;

import org.eclipse.jetty.ee10.plus.annotation.Injection;
import org.eclipse.jetty.ee10.plus.annotation.InjectionCollection;
import org.eclipse.jetty.ee10.plus.jndi.EnvEntry;
import org.eclipse.jetty.ee10.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.ee10.plus.jndi.Resource;
import org.eclipse.jetty.ee10.plus.jndi.webapp.EnvConfiguration;
import org.eclipse.jetty.ee10.plus.jndi.webapp.PlusConfiguration;
import org.eclipse.jetty.ee10.plus.jndi.webapp.PlusDescriptorProcessor;
import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.Descriptor;
import org.eclipse.jetty.ee10.webapp.FragmentDescriptor;
import org.eclipse.jetty.ee10.webapp.Origin;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebDescriptor;
import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.IntrospectionUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * PlusDescriptorProcessorTest
 */
public class PlusDescriptorProcessorTest
{
    protected static final Class<?>[] STRING_ARG = new Class[]{String.class};
    protected WebDescriptor webDescriptor;
    protected FragmentDescriptor fragDescriptor1;
    protected FragmentDescriptor fragDescriptor2;
    protected FragmentDescriptor fragDescriptor3;
    protected FragmentDescriptor fragDescriptor4;
    protected WebAppContext context;

    public static class TestInjections
    {
        private String foo;
        private String bah;
        private String empty;
        private String vacuum;
        private String webXmlOnly;
        
        public String getWebXmlOnly()
        {
            return webXmlOnly;
        }

        public void setWebXmlOnly(String webXmlOnly)
        {
            this.webXmlOnly = webXmlOnly;
        }

        public String getVacuum()
        {
            return vacuum;
        }

        public void setVacuum(String val)
        {
            vacuum = val;
        }

        public String getEmpty()
        {
            return empty;
        }

        public void setEmpty(String val)
        {
            empty = val;
        }

        public void setFoo(String val)
        {
            foo = val;
        }
        
        public String getFoo()
        {
            return foo;
        }

        public String getBah()
        {
            return bah;
        }

        public void setBah(String val)
        {
            bah = val;
        }
    }
    
    @BeforeEach
    public void setUp() throws Exception
    {
        context = new WebAppContext();
        context.setConfigurations(new Configuration[]{new PlusConfiguration(), new EnvConfiguration()});
        context.preConfigure();
        context.setClassLoader(new WebAppClassLoader(Thread.currentThread().getContextClassLoader(), context));
        context.getServerClassMatcher().exclude("org.eclipse.jetty.plus.webapp."); //need visbility of the TestInjections class
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        Context icontext = new InitialContext();
        Context compCtx = (Context)icontext.lookup("java:comp");
        Context envCtx = compCtx.createSubcontext("env");

        @SuppressWarnings("unused")
        Resource ds = new Resource(context, "jdbc/mydatasource", new Object());
        
        //An EnvEntry that should override any value supplied in a web.xml file
        EnvEntry fooStringEnvEntry = new EnvEntry("foo", "FOO", true);
        doEnvConfiguration(envCtx, fooStringEnvEntry);
        
        //An EnvEntry that should NOT override any value supplied in a web.xml file
        EnvEntry bahStringEnvEntry = new EnvEntry("bah", "BAH", false);
        doEnvConfiguration(envCtx, bahStringEnvEntry);
        
        //An EnvEntry that will override an empty value in web.xml
        EnvEntry emptyStringEnvEntry = new EnvEntry("empty", "EMPTY", true);
        doEnvConfiguration(envCtx, emptyStringEnvEntry);
        
        //An EnvEntry that will NOT override an empty value in web.xml
        EnvEntry vacuumStringEnvEntry = new EnvEntry("vacuum", "VACUUM", false);
        doEnvConfiguration(envCtx, vacuumStringEnvEntry);

        URL webXml = Thread.currentThread().getContextClassLoader().getResource("web.xml");
        webDescriptor = new WebDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(webXml));
        webDescriptor.parse(WebDescriptor.getParser(false));

        URL frag1Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-1.xml");
        fragDescriptor1 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag1Xml));
        fragDescriptor1.parse(WebDescriptor.getParser(false));
        URL frag2Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-2.xml");
        fragDescriptor2 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag2Xml));
        fragDescriptor2.parse(WebDescriptor.getParser(false));
        URL frag3Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-3.xml");
        fragDescriptor3 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag3Xml));
        fragDescriptor3.parse(WebDescriptor.getParser(false));
        URL frag4Xml = Thread.currentThread().getContextClassLoader().getResource("web-fragment-4.xml");
        fragDescriptor4 = new FragmentDescriptor(org.eclipse.jetty.util.resource.Resource.newResource(frag4Xml));
        fragDescriptor4.parse(WebDescriptor.getParser(false));
        Thread.currentThread().setContextClassLoader(oldLoader);
    }
    
    /**
     * Do the kind of processing that EnvConfiguration would do.
     * 
     * @param envCtx the java:comp/env context
     * @param envEntry the EnvEntry
     * @throws Exception
     */
    private void doEnvConfiguration(Context envCtx, EnvEntry envEntry) throws Exception
    {
        envEntry.bindToENC(envEntry.getJndiName());
        Name namingEntryName = NamingEntryUtil.makeNamingEntryName(null, envEntry);
        NamingUtil.bind(envCtx, namingEntryName.toString(), envEntry);
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        Context ic = new InitialContext();
        Context compCtx = (Context)ic.lookup("java:comp");
        compCtx.destroySubcontext("env");
        Thread.currentThread().setContextClassLoader(oldLoader);
    }

    @Test
    public void testMissingResourceDeclaration()
        throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());

        InvocationTargetException x = assertThrows(InvocationTargetException.class, () ->
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            pdp.process(context, fragDescriptor4);
            fail("Expected missing resource declaration");
        });
        Thread.currentThread().setContextClassLoader(oldLoader);

        assertThat(x.getCause(), is(notNullValue()));
        assertThat(x.getCause().getMessage(), containsString("jdbc/mymissingdatasource"));
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
    public void testEnvEntries() throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        try
        {
            PlusDescriptorProcessor pdp = new PlusDescriptorProcessor();
            //process web.xml
            pdp.process(context, webDescriptor);
            InjectionCollection injections = (InjectionCollection)context.getAttribute(InjectionCollection.INJECTION_COLLECTION);
            assertNotNull(injections);
            
            //check that there is an injection for "foo" with the value from the overriding EnvEntry of "FOO"
            Injection foo = injections.getInjection("foo", TestInjections.class, 
                IntrospectionUtil.findMethod(TestInjections.class, "setFoo", STRING_ARG, false, true), 
                String.class);
            assertNotNull(foo);
            assertEquals("FOO", foo.lookupInjectedValue());
            
            //check that there is an injection for "bah" with the value from web.xml of "beer"
            Injection bah = injections.getInjection("bah", TestInjections.class,
                IntrospectionUtil.findMethod(TestInjections.class, "setBah", STRING_ARG, false, true),
                String.class);
            assertNotNull(bah);
            assertEquals("beer", bah.lookupInjectedValue());
            
            //check that there is an injection for "empty" with the value from the overriding EnvEntry of "EMPTY"
            Injection empty = injections.getInjection("empty", TestInjections.class,
                IntrospectionUtil.findMethod(TestInjections.class, "setEmpty", STRING_ARG, false, true),
                String.class);
            assertNotNull(empty);
            assertEquals("EMPTY", empty.lookupInjectedValue());
            
            //check that there is NOT an injection for "vacuum"
            Injection vacuum = injections.getInjection("vacuum", TestInjections.class,
                IntrospectionUtil.findMethod(TestInjections.class, "setVacuum", STRING_ARG, false, true),
                String.class);
            assertNull(vacuum); 
            
            //check that there is an injection for "webxmlonly" with the value from web.xml of "WEBXMLONLY"
            Injection webXmlOnly = injections.getInjection("webxmlonly", TestInjections.class,
                IntrospectionUtil.findMethod(TestInjections.class, "setWebXmlOnly", STRING_ARG, false, true),
                String.class);
            assertNotNull(webXmlOnly);
            assertEquals("WEBXMLONLY", webXmlOnly.lookupInjectedValue());
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    @Test
    public void testMismatchedFragmentResourceDeclarations()
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
    public void testMatchingFragmentResourceDeclarations()
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
