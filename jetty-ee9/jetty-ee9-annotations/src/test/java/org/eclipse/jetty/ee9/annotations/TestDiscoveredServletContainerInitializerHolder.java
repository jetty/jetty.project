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

package org.eclipse.jetty.ee9.annotations;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletContainerInitializer;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.HandlesTypes;
import org.eclipse.jetty.ee9.servlet.Source;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public class TestDiscoveredServletContainerInitializerHolder
{
    /**
     * A marker type that is passed as an arg to @HandlesTypes
     */
    interface Ordinary
    {
        
    }
    
    /**
     * An class with an annotation (that is listed in @HandlesTypes)
     */
    @Sample(value = 1)
    public static class ASample
    {  
    }
    
    /**
     * A class that extends a class with an annotation
     */
    public static class BSample extends ASample
    {
    }

    @HandlesTypes({Sample.class})
    public static class SampleServletContainerInitializer implements ServletContainerInitializer
    {
        @Override
        public void onStartup(Set<Class<?>> c, ServletContext ctx) throws ServletException
        {
        }
    }
    
    @Test
    public void test() throws Exception
    {
        //SCI with @HandlesTypes[Ordinary, Sample]
        SampleServletContainerInitializer sci = new SampleServletContainerInitializer();
        
        AnnotationConfiguration.DiscoveredServletContainerInitializerHolder holder =
            new AnnotationConfiguration.DiscoveredServletContainerInitializerHolder(new Source(Source.Origin.ANNOTATION, sci.getClass()),
            sci);

        //add the @HandlesTypes to the holder
        holder.addStartupClasses(Ordinary.class, Sample.class);
        
        //pretend scanned and discovered that ASample has the Sample annotation
        holder.addStartupClasses(ASample.class.getName());
        
        //pretend we scanned the entire class hierarchy and found:
        //   com.acme.tom and com.acme.dick both extend Ordinary
        //   ASample has subclass BSample
        Map<String, Set<String>> classMap = new HashMap<>();
        classMap.put(Ordinary.class.getName(), new HashSet(Arrays.asList("com.acme.tom", "com.acme.dick")));
        classMap.put(ASample.class.getName(), new HashSet(Arrays.asList(BSample.class.getName())));
        holder.resolveClasses(classMap);
        
        //we should now have the following classes that will be passed to the SampleServletContainerInitializer.onStartup
        String toString = holder.toString();
        assertThat(toString, containsString("com.acme.tom"));
        assertThat(toString, containsString("com.acme.dick"));
        assertThat(toString, containsString(ASample.class.getName()));
        assertThat(toString, containsString(BSample.class.getName()));
        assertThat(toString, containsString("applicable=[],annotated=[]"));
    }
}
