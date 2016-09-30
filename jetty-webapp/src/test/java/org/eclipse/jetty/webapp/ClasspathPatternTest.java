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

package org.eclipse.jetty.webapp;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.eclipse.jetty.toolchain.test.JDK;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import sun.security.provider.Sun;

public class ClasspathPatternTest
{
    private final ClasspathPattern _pattern = new ClasspathPattern();
    
    @Before
    public void before()
    {
        _pattern.clear();
        _pattern.add("org.package.");
        _pattern.add("-org.excluded.");
        _pattern.add("org.example.FooBar");
        _pattern.add("-org.example.Excluded");
        _pattern.addAll(Arrays.asList(new String[]{
            "-org.example.Nested$Minus",
            "org.example.Nested",
            "org.example.Nested$Something"}));
        
        
        assertThat(_pattern,containsInAnyOrder(
            "org.package.",
            "-org.excluded.",
            "org.example.FooBar",
            "-org.example.Excluded",
            "-org.example.Nested$Minus",
            "org.example.Nested",
            "org.example.Nested$Something"
            ));
    }
    
    
    @Test
    public void testClassMatch()
    {
        assertTrue(_pattern.match("org.example.FooBar"));
        assertTrue(_pattern.match("org.example.Nested"));
        
        assertFalse(_pattern.match("org.example.Unknown"));
        assertFalse(_pattern.match("org.example.FooBar.Unknown"));
    }
    
    @Test
    public void testPackageMatch()
    {
        assertTrue(_pattern.match("org.package.Something"));
        assertTrue(_pattern.match("org.package.other.Something"));
        
        assertFalse(_pattern.match("org.example.Unknown"));
        assertFalse(_pattern.match("org.example.FooBar.Unknown"));
        assertFalse(_pattern.match("org.example.FooBarElse"));
    }

    @Test
    public void testExplicitNestedMatch()
    {
        assertTrue(_pattern.match("org.example.Nested$Something"));
        assertFalse(_pattern.match("org.example.Nested$Minus"));
        assertTrue(_pattern.match("org.example.Nested$Other"));
    }

    @Test
    public void testImplicitNestedMatch()
    {
        assertTrue(_pattern.match("org.example.FooBar$Other"));
        assertTrue(_pattern.match("org.example.Nested$Other"));
    }
    
    @Test
    public void testDoubledNested()
    {
        assertTrue(_pattern.match("org.example.Nested$Something$Else"));
        
        assertFalse(_pattern.match("org.example.Nested$Minus$Else"));
    }

    @Test
    public void testMatchAll()
    {
        _pattern.clear();
        _pattern.add(".");
        assertTrue(_pattern.match("org.example.Anything"));
        assertTrue(_pattern.match("org.example.Anything$Else"));
    }

    /**
     * 
     */
    @SuppressWarnings("restriction")
    @Test
    public void testiIncludedLocations() throws Exception
    {
        // jar from JVM classloader
        Resource loc_string = TypeUtil.getLoadedFrom(String.class);
        // System.err.println(loc_string);
        
        // another jar from JVM classloader
        Resource loc_jsse = TypeUtil.getLoadedFrom(Sun.class);
        // System.err.println(loc_jsse);
        
        // a jar from maven repo jar
        Resource loc_junit = TypeUtil.getLoadedFrom(Test.class);
        // System.err.println(loc_junit);
        
        // a jar from another maven repo jar
        Resource loc_tool = TypeUtil.getLoadedFrom(JDK.class);
        // System.err.println(loc_tool);

        // class file 
        Resource loc_test = TypeUtil.getLoadedFrom(ClasspathPatternTest.class);
        // System.err.println(loc_test);
        
        ClasspathPattern pattern = new ClasspathPattern();
        pattern.include("something");
        assertThat(pattern.match(String.class),is(false));
        assertThat(pattern.match(Sun.class),is(false));
        assertThat(pattern.match(Test.class),is(false));
        assertThat(pattern.match(JDK.class),is(false));
        assertThat(pattern.match(ClasspathPatternTest.class),is(false));
        
        // Add directory for both JVM classes
        pattern.include(loc_string.getFile().getParentFile().toURI().toString());
        
        // Add jar for individual class and classes directory
        pattern.include(loc_junit.toString(),loc_test.toString());
                
        assertThat(pattern.match(String.class),is(true));
        assertThat(pattern.match(Sun.class),is(true));
        assertThat(pattern.match(Test.class),is(true));
        assertThat(pattern.match(JDK.class),is(false));
        assertThat(pattern.match(ClasspathPatternTest.class),is(true));
        
        // exclude by package name still works 
        pattern.add("-sun.security.provider.Sun");
        assertThat(pattern.match(String.class),is(true));
        assertThat(pattern.match(Sun.class),is(false));
        assertThat(pattern.match(Test.class),is(true));
        assertThat(pattern.match(JDK.class),is(false));
        assertThat(pattern.match(ClasspathPatternTest.class),is(true));
        
        
    }
    
    /**
     * 
     */
    @SuppressWarnings("restriction")
    @Test
    public void testExcludeLocations() throws Exception
    {
        // jar from JVM classloader
        Resource loc_string = TypeUtil.getLoadedFrom(String.class);
        // System.err.println(loc_string);
        
        // another jar from JVM classloader
        Resource loc_jsse = TypeUtil.getLoadedFrom(Sun.class);
        // System.err.println(loc_jsse);
        
        // a jar from maven repo jar
        Resource loc_junit = TypeUtil.getLoadedFrom(Test.class);
        // System.err.println(loc_junit);
        
        // a jar from another maven repo jar
        Resource loc_tool = TypeUtil.getLoadedFrom(JDK.class);
        // System.err.println(loc_tool);

        // class file 
        Resource loc_test = TypeUtil.getLoadedFrom(ClasspathPatternTest.class);
        // System.err.println(loc_test);
        
        ClasspathPattern pattern = new ClasspathPattern();
        
        // include everything
        pattern.include(".");
        
        assertThat(pattern.match(String.class),is(true));
        assertThat(pattern.match(Sun.class),is(true));
        assertThat(pattern.match(Test.class),is(true));
        assertThat(pattern.match(JDK.class),is(true));
        assertThat(pattern.match(ClasspathPatternTest.class),is(true));
        
        // Add directory for both JVM classes
        pattern.exclude(loc_string.getFile().getParentFile().toURI().toString());
        
        // Add jar for individual class and classes directory
        pattern.exclude(loc_junit.toString(),loc_test.toString());
                
        assertThat(pattern.match(String.class),is(false));
        assertThat(pattern.match(Sun.class),is(false));
        assertThat(pattern.match(Test.class),is(false));
        assertThat(pattern.match(JDK.class),is(true));
        assertThat(pattern.match(ClasspathPatternTest.class),is(false));
    }
}
