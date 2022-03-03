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

package org.eclipse.jetty.ee10.webapp;

import java.net.URI;
import java.util.Arrays;
import java.util.function.Supplier;

import org.eclipse.jetty.ee10.webapp.ClassMatcher.ByLocationOrModule;
import org.eclipse.jetty.ee10.webapp.ClassMatcher.ByPackageOrName;
import org.eclipse.jetty.ee10.webapp.ClassMatcher.Entry;
import org.eclipse.jetty.util.IncludeExcludeSet;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ClassMatcherTest
{
    private final ClassMatcher _pattern = new ClassMatcher();
    
    protected static Supplier<URI> NULL_SUPPLIER = new Supplier<URI>()
    {
        public URI get()
        {
            return null;
        } 
    };
    
    @BeforeEach
    public void before()
    {
        _pattern.clear();
        _pattern.add("org.package.");
        _pattern.add("-org.excluded.");
        _pattern.add("org.example.FooBar");
        _pattern.add("-org.example.Excluded");
        _pattern.addAll(Arrays.asList(
            "-org.example.Nested$Minus",
            "org.example.Nested",
            "org.example.Nested$Something"));

        assertThat(_pattern, Matchers.containsInAnyOrder(
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

    @Test
    public void testCopy()
    {
        ClassMatcher copy = new ClassMatcher(_pattern);
        assertThat(copy.toString(), is(_pattern.toString()));
    }

    @Test
    public void testMatchFundamentalExcludeSpecific()
    {
        _pattern.clear();
        _pattern.add("jakarta.");
        _pattern.add("-jakarta.ws.rs.", "-jakarta.inject.");
        assertFalse(_pattern.match("org.example.Anything"));
        assertTrue(_pattern.match("jakarta.servlet.HttpServlet"));
        assertFalse(_pattern.match("jakarta.ws.rs.ProcessingException"));
    }

    @SuppressWarnings("restriction")
    @Test
    public void testIncludedLocations() throws Exception
    {
        // jar from JVM classloader
        URI locString = TypeUtil.getLocationOfClass(String.class);

        // a jar from maven repo jar
        URI locJunit = TypeUtil.getLocationOfClass(Test.class);

        // class file 
        URI locTest = TypeUtil.getLocationOfClass(ClassMatcherTest.class);

        ClassMatcher pattern = new ClassMatcher();
        pattern.include("something");
        assertThat(pattern.match(String.class), is(false));
        assertThat(pattern.match(Test.class), is(false));
        assertThat(pattern.match(ClassMatcherTest.class), is(false));

        // Add directory for both JVM classes
        pattern.include(locString.toASCIIString());

        // Add jar for individual class and classes directory
        pattern.include(locJunit.toString(), locTest.toString());

        assertThat(pattern.match(String.class), is(true));
        assertThat(pattern.match(Test.class), is(true));
        assertThat(pattern.match(ClassMatcherTest.class), is(true));

        pattern.add("-java.lang.String");
        assertThat(pattern.match(String.class), is(false));
        assertThat(pattern.match(Test.class), is(true));
        assertThat(pattern.match(ClassMatcherTest.class), is(true));
    }

    @SuppressWarnings("restriction")
    @Test
    public void testIncludedLocationsOrModule() throws Exception
    {
        // jar from JVM classloader
        URI modString = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(modString);

        // a jar from maven repo jar
        URI locJunit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(locJunit);

        // class file
        URI locTest = TypeUtil.getLocationOfClass(ClassMatcherTest.class);
        // System.err.println(locTest);

        ClassMatcher pattern = new ClassMatcher();
        pattern.include("something");
        assertThat(pattern.match(String.class), is(false));
        assertThat(pattern.match(Test.class), is(false));
        assertThat(pattern.match(ClassMatcherTest.class), is(false));

        // Add module for all JVM base classes
        pattern.include("jrt:/java.base");

        // Add jar for individual class and classes directory
        pattern.include(locJunit.toString(), locTest.toString());

        assertThat(pattern.match(String.class), is(true));
        assertThat(pattern.match(Test.class), is(true));
        assertThat(pattern.match(ClassMatcherTest.class), is(true));

        pattern.add("-java.lang.String");
        assertThat(pattern.match(String.class), is(false));
        assertThat(pattern.match(Test.class), is(true));
        assertThat(pattern.match(ClassMatcherTest.class), is(true));
    }

    @SuppressWarnings("restriction")
    @Test
    public void testExcludeLocationsOrModule() throws Exception
    {
        // jar from JVM classloader
        URI modString = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(modString);

        // a jar from maven repo jar
        URI locJunit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(locJunit);

        // class file
        URI locTest = TypeUtil.getLocationOfClass(ClassMatcherTest.class);
        // System.err.println(locTest);

        ClassMatcher pattern = new ClassMatcher();

        // include everything
        pattern.include(".");

        assertThat(pattern.match(String.class), is(true));
        assertThat(pattern.match(Test.class), is(true));
        assertThat(pattern.match(ClassMatcherTest.class), is(true));

        // Add directory for both JVM classes
        pattern.exclude("jrt:/java.base/");

        // Add jar for individual class and classes directory
        pattern.exclude(locJunit.toString(), locTest.toString());

        assertThat(pattern.match(String.class), is(false));
        assertThat(pattern.match(Test.class), is(false));
        assertThat(pattern.match(ClassMatcherTest.class), is(false));
    }
    
    @Test
    public void testWithNullLocation() throws Exception
    {
        ClassMatcher matcher = new ClassMatcher();
        
        IncludeExcludeSet<Entry, String> names = new IncludeExcludeSet<>(ByPackageOrName.class);
        IncludeExcludeSet<Entry, URI> locations = new IncludeExcludeSet<>(ByLocationOrModule.class);

        //Test no name or location includes or excludes - should match
        assertThat(ClassMatcher.combine(names, "a.b.c", locations, NULL_SUPPLIER), is(true));
        
        names.include(matcher.newEntry("a.b.", true));
        names.exclude(matcher.newEntry("d.e.", false));
       
        //Test explicit include by name no locations - should match
        assertThat(ClassMatcher.combine(names, "a.b.c", locations, NULL_SUPPLIER), is(true));
        
        //Test explicit exclude by name no locations - should not match
        assertThat(ClassMatcher.combine(names, "d.e.f", locations, NULL_SUPPLIER), is(false));
        
        //Test include by name with location includes - should match
        locations.include(matcher.newEntry("file:/foo/bar", true));
        assertThat(ClassMatcher.combine(names, "a.b.c", locations, NULL_SUPPLIER), is(true));
        
        //Test include by name but with location exclusions - should not match
        locations.clear();
        locations.exclude(matcher.newEntry("file:/high/low", false));
        assertThat(ClassMatcher.combine(names, "a.b.c", locations, NULL_SUPPLIER), is(false));
        
        //Test neither included or excluded by name, but with location exclusions - should not match
        assertThat(ClassMatcher.combine(names, "g.b.r", locations, NULL_SUPPLIER), is(false));
        
        //Test neither included nor excluded by name, but with location inclusions - should not match
        locations.clear();
        locations.include(matcher.newEntry("file:/foo/bar", true));
        assertThat(ClassMatcher.combine(names, "g.b.r", locations, NULL_SUPPLIER), is(false));
    }

    @Test
    public void testLarge()
    {
        ClassMatcher pattern = new ClassMatcher();
        for (int i = 0; i < 500; i++)
        {
            assertTrue(pattern.add("n" + i + "." + Integer.toHexString(100 + i) + ".Name"));
        }

        for (int i = 0; i < 500; i++)
        {
            assertTrue(pattern.match("n" + i + "." + Integer.toHexString(100 + i) + ".Name"));
        }
    }

    @Test
    public void testJvmModule()
    {
        URI uri = TypeUtil.getLocationOfClass(String.class);
        System.err.println(uri);
        System.err.println(uri.toString().split("/")[0]);
        System.err.println(uri.toString().split("/")[1]);
    }
}
