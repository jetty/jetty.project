//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.file.Paths;
import java.util.Arrays;

import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnJre;
import org.junit.jupiter.api.condition.EnabledOnJre;
import org.junit.jupiter.api.condition.JRE;

public class ClasspathPatternTest
{
    private final ClasspathPattern _pattern = new ClasspathPattern();

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

    @SuppressWarnings("restriction")
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testIncludedLocations() throws Exception
    {
        // jar from JVM classloader
        URI loc_string = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(loc_string);

        // a jar from maven repo jar
        URI loc_junit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(loc_junit);

        // class file 
        URI loc_test = TypeUtil.getLocationOfClass(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();
        pattern.include("something");
        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(false));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));

        // Add directory for both JVM classes
        pattern.include(Paths.get(loc_string).getParent().toUri().toString());

        // Add jar for individual class and classes directory
        pattern.include(loc_junit.toString(), loc_test.toString());

        assertThat(pattern.match(String.class), Matchers.is(true));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        pattern.add("-java.lang.String");
        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));
    }

    @SuppressWarnings("restriction")
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testIncludedLocationsOrModule() throws Exception
    {
        // jar from JVM classloader
        URI mod_string = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(mod_string);

        // a jar from maven repo jar
        URI loc_junit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(loc_junit);

        // class file
        URI loc_test = TypeUtil.getLocationOfClass(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();
        pattern.include("something");
        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(false));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));

        // Add module for all JVM base classes
        pattern.include("jrt:/java.base");

        // Add jar for individual class and classes directory
        pattern.include(loc_junit.toString(), loc_test.toString());

        assertThat(pattern.match(String.class), Matchers.is(true));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        pattern.add("-java.lang.String");
        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));
    }

    @SuppressWarnings("restriction")
    @Test
    @EnabledOnJre(JRE.JAVA_8)
    public void testExcludeLocations() throws Exception
    {
        // jar from JVM classloader
        URI loc_string = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(loc_string);

        // a jar from maven repo jar
        URI loc_junit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(loc_junit);

        // class file 
        URI loc_test = TypeUtil.getLocationOfClass(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();

        // include everything
        pattern.include(".");

        assertThat(pattern.match(String.class), Matchers.is(true));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        // Add directory for both JVM classes
        pattern.exclude(Paths.get(loc_string).getParent().toUri().toString());

        // Add jar for individual class and classes directory
        pattern.exclude(loc_junit.toString(), loc_test.toString());

        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(false));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));
    }

    @SuppressWarnings("restriction")
    @Test
    @DisabledOnJre(JRE.JAVA_8)
    public void testExcludeLocationsOrModule() throws Exception
    {
        // jar from JVM classloader
        URI mod_string = TypeUtil.getLocationOfClass(String.class);
        // System.err.println(mod_string);

        // a jar from maven repo jar
        URI loc_junit = TypeUtil.getLocationOfClass(Test.class);
        // System.err.println(loc_junit);

        // class file
        URI loc_test = TypeUtil.getLocationOfClass(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();

        // include everything
        pattern.include(".");

        assertThat(pattern.match(String.class), Matchers.is(true));
        assertThat(pattern.match(Test.class), Matchers.is(true));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        // Add directory for both JVM classes
        pattern.exclude("jrt:/java.base/");

        // Add jar for individual class and classes directory
        pattern.exclude(loc_junit.toString(), loc_test.toString());

        assertThat(pattern.match(String.class), Matchers.is(false));
        assertThat(pattern.match(Test.class), Matchers.is(false));
        assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));
    }

    @Test
    public void testLarge()
    {
        ClasspathPattern pattern = new ClasspathPattern();
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
