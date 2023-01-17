//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.servlet;

import java.util.Set;
import java.util.regex.Matcher;
import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import org.eclipse.jetty.servlet.Source.Origin;
import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ServletContainerInitializerHolderTest
{
    public static final String[] EMPTY_ARRAY = {};
    
    static class SimpleSCI implements ServletContainerInitializer
    {
        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
        {
            ctx.setAttribute("SimpleSCI-onStartup", Boolean.TRUE);
        }
    }

    @Test
    public void testClassNoArgs() throws Exception
    {
        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(SimpleSCI.class);
        assertEquals(Source.EMBEDDED, holder.getSource());
        assertEquals("ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[],applicable=[],annotated=[]}", holder.toString());
    }
    
    @Test
    public void testClassWithStartupClasses() throws Exception
    {
        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(SimpleSCI.class, Integer.class);
        assertEquals(Source.EMBEDDED, holder.getSource());
        assertEquals(SimpleSCI.class, holder.getHeldClass());
        assertEquals("ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[java.lang.Integer],applicable=[],annotated=[]}", holder.toString());
    }

    @Test
    public void testInstanceWithStartupClasses() throws Exception
    {        
        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(new SimpleSCI(), Integer.class);
        assertEquals(Source.EMBEDDED, holder.getSource());
        assertEquals(SimpleSCI.class, holder.getHeldClass());
        assertEquals("ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[java.lang.Integer],applicable=[],annotated=[]}", holder.toString()); 
    }
    
    @Test
    public void testInstanceWithStartupClassesAndSource() throws Exception
    {
        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(new Source(Origin.ANNOTATION, null), new SimpleSCI(), Integer.class);
        assertEquals(Origin.ANNOTATION, holder.getSource().getOrigin());
        assertEquals(SimpleSCI.class, holder.getHeldClass());
        assertEquals("ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[java.lang.Integer],applicable=[],annotated=[]}", holder.toString());
    }
    
    @Test
    public void testToString() throws Exception
    {
        //test that the stringified ServletContainerInitializerHolder is backward compatible with what was generated by the old ContainerInitializer
        ServletContainerInitializerHolder holder = new ServletContainerInitializerHolder(SimpleSCI.class);
        assertEquals("ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[],applicable=[],annotated=[]}", holder.toString());
    }

    @Test
    public void testFromString() throws Exception
    {
        //test for backward compatibility of string format
        String sci0 = "ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[],applicable=[],annotated=[]}";
        ServletContainerInitializerHolder holder = ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), sci0);
        assertEquals(sci0, holder.toString());
        
        //test with no classes
        String sci1 = "ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[]}";
        String sci1Expected = "ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[],applicable=[],annotated=[]}";
        holder = ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), sci1);
        assertEquals(sci1Expected, holder.toString());

        //test with some startup classes
        String sci2 = "ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[java.lang.String, java.lang.Integer]}";
        holder = ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), sci2);

        final Matcher matcher2 = ServletContainerInitializerHolder.__pattern.matcher(holder.toString());
        matcher2.matches();
        assertEquals("org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI", matcher2.group(1));
        assertThat(StringUtil.arrayFromString("[java.lang.String, java.lang.Integer]"), arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher2.group(2))));
        assertThat(EMPTY_ARRAY, arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher2.group(4))));
        assertThat(EMPTY_ARRAY, arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher2.group(6))));

        //test with old format with startup classes
        String sci3 = "ContainerInitializer{org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI,interested=[java.lang.String, java.lang.Integer],applicable=[java.lang.Boolean],annotated=[java.lang.Long]}";
        holder = ServletContainerInitializerHolder.fromString(Thread.currentThread().getContextClassLoader(), sci3);
        final Matcher matcher3 = ServletContainerInitializerHolder.__pattern.matcher(holder.toString());
        matcher3.matches();
        assertEquals("org.eclipse.jetty.servlet.ServletContainerInitializerHolderTest$SimpleSCI", matcher3.group(1));
        assertThat(StringUtil.arrayFromString("[java.lang.String, java.lang.Integer, java.lang.Boolean, java.lang.Long]"), arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher3.group(2))));
        assertThat(EMPTY_ARRAY, arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher3.group(4))));
        assertThat(EMPTY_ARRAY, arrayContainingInAnyOrder(StringUtil.arrayFromString(matcher3.group(6))));
    }
}
