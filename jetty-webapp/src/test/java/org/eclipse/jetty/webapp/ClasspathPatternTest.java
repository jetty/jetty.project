//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.util.Arrays;

import org.eclipse.jetty.toolchain.test.JDK;
import org.eclipse.jetty.util.TypeUtil;
import org.hamcrest.Matchers;
import org.junit.Assert;
import org.junit.Assume;
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
        _pattern.addAll(Arrays.asList(
                "-org.example.Nested$Minus",
                "org.example.Nested",
                "org.example.Nested$Something"));

        Assert.assertThat(_pattern, Matchers.containsInAnyOrder(
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
        Assert.assertTrue(_pattern.match("org.example.FooBar"));
        Assert.assertTrue(_pattern.match("org.example.Nested"));

        Assert.assertFalse(_pattern.match("org.example.Unknown"));
        Assert.assertFalse(_pattern.match("org.example.FooBar.Unknown"));
    }

    @Test
    public void testPackageMatch()
    {
        Assert.assertTrue(_pattern.match("org.package.Something"));
        Assert.assertTrue(_pattern.match("org.package.other.Something"));

        Assert.assertFalse(_pattern.match("org.example.Unknown"));
        Assert.assertFalse(_pattern.match("org.example.FooBar.Unknown"));
        Assert.assertFalse(_pattern.match("org.example.FooBarElse"));
    }

    @Test
    public void testExplicitNestedMatch()
    {
        Assert.assertTrue(_pattern.match("org.example.Nested$Something"));
        Assert.assertFalse(_pattern.match("org.example.Nested$Minus"));
        Assert.assertTrue(_pattern.match("org.example.Nested$Other"));
    }

    @Test
    public void testImplicitNestedMatch()
    {
        Assert.assertTrue(_pattern.match("org.example.FooBar$Other"));
        Assert.assertTrue(_pattern.match("org.example.Nested$Other"));
    }

    @Test
    public void testDoubledNested()
    {
        Assert.assertTrue(_pattern.match("org.example.Nested$Something$Else"));

        Assert.assertFalse(_pattern.match("org.example.Nested$Minus$Else"));
    }

    @Test
    public void testMatchAll()
    {
        _pattern.clear();
        _pattern.add(".");
        Assert.assertTrue(_pattern.match("org.example.Anything"));
        Assert.assertTrue(_pattern.match("org.example.Anything$Else"));
    }

    @SuppressWarnings("restriction")
    @Test
    public void testIncludedLocations() throws Exception
    {
        Assume.assumeFalse(JDK.IS_9);

        // jar from JVM classloader
        File loc_string = TypeUtil.getLocationOfClassAsFile(String.class);
        // System.err.println(loc_string);

        // another jar from JVM classloader
        File loc_jsse = TypeUtil.getLocationOfClassAsFile(Sun.class);
        // System.err.println(loc_jsse);

        // a jar from maven repo jar
        File loc_junit = TypeUtil.getLocationOfClassAsFile(Test.class);
        // System.err.println(loc_junit);

        // a jar from another maven repo jar
        File loc_tool = TypeUtil.getLocationOfClassAsFile(JDK.class);
        // System.err.println(loc_tool);

        // class file 
        File loc_test = TypeUtil.getLocationOfClassAsFile(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();
        pattern.include("something");
        Assert.assertThat(pattern.match(String.class), Matchers.is(false));
        Assert.assertThat(pattern.match(Sun.class), Matchers.is(false));
        Assert.assertThat(pattern.match(Test.class), Matchers.is(false));
        Assert.assertThat(pattern.match(JDK.class), Matchers.is(false));
        Assert.assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));

        // Add directory for both JVM classes
        pattern.include(loc_string.getParentFile().toURI().toString());

        // Add jar for individual class and classes directory
        pattern.include(loc_junit.toString(), loc_test.toString());

        Assert.assertThat(pattern.match(String.class), Matchers.is(true));
        Assert.assertThat(pattern.match(Sun.class), Matchers.is(true));
        Assert.assertThat(pattern.match(Test.class), Matchers.is(true));
        Assert.assertThat(pattern.match(JDK.class), Matchers.is(false));
        Assert.assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        // exclude by package name still works 
        pattern.add("-sun.security.provider.Sun");
        Assert.assertThat(pattern.match(String.class), Matchers.is(true));
        Assert.assertThat(pattern.match(Sun.class), Matchers.is(false));
        Assert.assertThat(pattern.match(Test.class), Matchers.is(true));
        Assert.assertThat(pattern.match(JDK.class), Matchers.is(false));
        Assert.assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));
    }

    @SuppressWarnings("restriction")
    @Test
    public void testExcludeLocations() throws Exception
    {
        Assume.assumeFalse(JDK.IS_9);

        // jar from JVM classloader
        File loc_string = TypeUtil.getLocationOfClassAsFile(String.class);
        // System.err.println(loc_string);

        // another jar from JVM classloader
        File loc_jsse = TypeUtil.getLocationOfClassAsFile(Sun.class);
        // System.err.println(loc_jsse);

        // a jar from maven repo jar
        File loc_junit = TypeUtil.getLocationOfClassAsFile(Test.class);
        // System.err.println(loc_junit);

        // a jar from another maven repo jar
        File loc_tool = TypeUtil.getLocationOfClassAsFile(JDK.class);
        // System.err.println(loc_tool);

        // class file 
        File loc_test = TypeUtil.getLocationOfClassAsFile(ClasspathPatternTest.class);
        // System.err.println(loc_test);

        ClasspathPattern pattern = new ClasspathPattern();

        // include everything
        pattern.include(".");

        Assert.assertThat(pattern.match(String.class), Matchers.is(true));
        Assert.assertThat(pattern.match(Sun.class), Matchers.is(true));
        Assert.assertThat(pattern.match(Test.class), Matchers.is(true));
        Assert.assertThat(pattern.match(JDK.class), Matchers.is(true));
        Assert.assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(true));

        // Add directory for both JVM classes
        pattern.exclude(loc_string.getParentFile().toURI().toString());

        // Add jar for individual class and classes directory
        pattern.exclude(loc_junit.toString(), loc_test.toString());

        Assert.assertThat(pattern.match(String.class), Matchers.is(false));
        Assert.assertThat(pattern.match(Sun.class), Matchers.is(false));
        Assert.assertThat(pattern.match(Test.class), Matchers.is(false));
        Assert.assertThat(pattern.match(JDK.class), Matchers.is(true));
        Assert.assertThat(pattern.match(ClasspathPatternTest.class), Matchers.is(false));
    }

    @Test
    public void testLarge()
    {
        ClasspathPattern pattern = new ClasspathPattern();
        for (int i = 0; i < 500; i++)
        {
            Assert.assertTrue(pattern.add("n" + i + "." + Integer.toHexString(100 + i) + ".Name"));
        }

        for (int i = 0; i < 500; i++)
        {
            Assert.assertTrue(pattern.match("n" + i + "." + Integer.toHexString(100 + i) + ".Name"));
        }
    }
}
