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

package org.eclipse.jetty.util;

import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class IncludeExcludeTest
{
    @Test
    public void testEmpty()
    {
        IncludeExclude<String> ie = new IncludeExclude<>();
        
        assertThat("Empty IncludeExclude", ie.size(), is(0));
        assertThat("Matches 'foo'",ie.matches("foo"),is(true));
    }
    
    @Test
    public void testIncludeOnly()
    {
        IncludeExclude<String> ie = new IncludeExclude<>();
        ie.include("foo");
        ie.include("bar");
        
        assertThat("IncludeExclude.size", ie.size(), is(2));
        assertEquals(false,ie.matches(""));
        assertEquals(true,ie.matches("foo"));
        assertEquals(true,ie.matches("bar"));
        assertEquals(false,ie.matches("foobar"));
    }
    
    @Test
    public void testExcludeOnly()
    {
        IncludeExclude<String> ie = new IncludeExclude<>();
        ie.exclude("foo");
        ie.exclude("bar");
        
        assertEquals(2,ie.size());
        
        assertEquals(false,ie.matches("foo"));
        assertEquals(false,ie.matches("bar"));
        assertEquals(true,ie.matches(""));
        assertEquals(true,ie.matches("foobar"));
        assertEquals(true,ie.matches("wibble"));
    }
    
    @Test
    public void testIncludeExclude()
    {
        IncludeExclude<String> ie = new IncludeExclude<>();
        ie.include("foo");
        ie.include("bar");
        ie.exclude("bar");
        ie.exclude("xxx");
        
        assertEquals(4,ie.size());
        
        assertEquals(true,ie.matches("foo"));
        assertEquals(false,ie.matches("bar"));
        assertEquals(false,ie.matches(""));
        assertEquals(false,ie.matches("foobar"));
        assertEquals(false,ie.matches("xxx"));
    }
    
    

    @Test
    public void testEmptyRegex()
    {
        IncludeExclude<String> ie = new IncludeExclude<>(RegexSet.class);
        
        assertEquals(0,ie.size());
        assertEquals(true,ie.matches("foo"));
    }
    
    @Test
    public void testIncludeRegex()
    {
        IncludeExclude<String> ie = new IncludeExclude<>(RegexSet.class);
        ie.include("f..");
        ie.include("b((ar)|(oo))");
        
        assertEquals(2,ie.size());
        assertEquals(false,ie.matches(""));
        assertEquals(true,ie.matches("foo"));
        assertEquals(true,ie.matches("far"));
        assertEquals(true,ie.matches("bar"));
        assertEquals(true,ie.matches("boo"));
        assertEquals(false,ie.matches("foobar"));
        assertEquals(false,ie.matches("xxx"));
    }
    
    @Test
    public void testExcludeRegex()
    {
        IncludeExclude<String> ie = new IncludeExclude<>(RegexSet.class);
        ie.exclude("f..");
        ie.exclude("b((ar)|(oo))");
        
        assertEquals(2,ie.size());
        
        assertEquals(false,ie.matches("foo"));
        assertEquals(false,ie.matches("far"));
        assertEquals(false,ie.matches("bar"));
        assertEquals(false,ie.matches("boo"));
        assertEquals(true,ie.matches(""));
        assertEquals(true,ie.matches("foobar"));
        assertEquals(true,ie.matches("xxx"));
    }
    
    @Test
    public void testIncludeExcludeRegex()
    {
        IncludeExclude<String> ie = new IncludeExclude<>(RegexSet.class);
        ie.include(".*[aeiou].*");
        ie.include("[AEIOU].*");
        ie.exclude("f..");
        ie.exclude("b((ar)|(oo))");
        
        assertEquals(4,ie.size());
        assertEquals(false,ie.matches("foo"));
        assertEquals(false,ie.matches("far"));
        assertEquals(false,ie.matches("bar"));
        assertEquals(false,ie.matches("boo"));
        assertEquals(false,ie.matches(""));
        assertEquals(false,ie.matches("xxx"));

        assertEquals(true,ie.matches("foobar"));
        assertEquals(true,ie.matches("Ant"));
    }
}
