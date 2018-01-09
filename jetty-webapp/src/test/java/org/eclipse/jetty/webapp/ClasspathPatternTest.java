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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Before;
import org.junit.Test;

public class ClasspathPatternTest
{
    private final ClasspathPattern pattern = new ClasspathPattern();
    
    @Before
    public void before()
    {
        pattern.clear();
        pattern.add("org.package.");
        pattern.add("-org.excluded.");
        pattern.add("org.example.FooBar");
        pattern.add("-org.example.Excluded");
        pattern.addAll(Arrays.asList(new String[]{"-org.example.Nested$Minus","org.example.Nested","org.example.Nested$Something"}));
    }
    
    @Test
    public void testClassMatch()
    {
        assertTrue(pattern.match("org.example.FooBar"));
        assertTrue(pattern.match("org.example.Nested"));
        
        assertFalse(pattern.match("org.example.Unknown"));
        assertFalse(pattern.match("org.example.FooBar.Unknown"));
    }
    
    @Test
    public void testPackageMatch()
    {
        assertTrue(pattern.match("org.package.Something"));
        assertTrue(pattern.match("org.package.other.Something"));
        
        assertFalse(pattern.match("org.example.Unknown"));
        assertFalse(pattern.match("org.example.FooBar.Unknown"));
        assertFalse(pattern.match("org.example.FooBarElse"));
    }

    @Test
    public void testExplicitNestedMatch()
    {
        assertTrue(pattern.match("org.example.Nested$Something"));
        assertFalse(pattern.match("org.example.Nested$Minus"));
        assertTrue(pattern.match("org.example.Nested$Other"));
    }

    @Test
    public void testImplicitNestedMatch()
    {
        assertTrue(pattern.match("org.example.FooBar$Other"));
        assertTrue(pattern.match("org.example.Nested$Other"));
    }
    
    @Test
    public void testAddBefore()
    {
        pattern.addBefore("-org.excluded.","org.excluded.ExceptionOne","org.excluded.ExceptionTwo");

        assertTrue(pattern.match("org.excluded.ExceptionOne"));
        assertTrue(pattern.match("org.excluded.ExceptionTwo"));
        
        assertFalse(pattern.match("org.example.Unknown"));
    }
    
    @Test
    public void testAddAfter()
    {
        pattern.addAfter("org.package.","org.excluded.ExceptionOne","org.excluded.ExceptionTwo");

        assertTrue(pattern.match("org.excluded.ExceptionOne"));
        assertTrue(pattern.match("org.excluded.ExceptionTwo"));
        
        assertFalse(pattern.match("org.example.Unknown"));
    }

    @Test
    public void testDoubledNested()
    {
        assertTrue(pattern.match("org.example.Nested$Something$Else"));
        
        assertFalse(pattern.match("org.example.Nested$Minus$Else"));
    }

    @Test
    public void testMatchAll()
    {
        pattern.clear();
        pattern.add(".");
        assertTrue(pattern.match("org.example.Anything"));
        assertTrue(pattern.match("org.example.Anything$Else"));
    }
}
