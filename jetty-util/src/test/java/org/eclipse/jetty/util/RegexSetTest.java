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

package org.eclipse.jetty.util;

import org.junit.Test;

import org.junit.Assert;

public class RegexSetTest
{

    @Test
    public void testEmpty()
    {
        RegexSet set = new RegexSet();
        
        Assert.assertEquals(false,set.contains("foo"));
        Assert.assertEquals(false,set.matches("foo"));
        Assert.assertEquals(false,set.matches(""));
        
    }
    
    @Test
    public void testSimple()
    {
        RegexSet set = new RegexSet();
        set.add("foo.*");
        
        Assert.assertEquals(true,set.contains("foo.*"));
        Assert.assertEquals(true,set.matches("foo"));
        Assert.assertEquals(true,set.matches("foobar"));
        Assert.assertEquals(false,set.matches("bar"));
        Assert.assertEquals(false,set.matches(""));
        
    }
    
    @Test
    public void testSimpleTerminated()
    {
        RegexSet set = new RegexSet();
        set.add("^foo.*$");
        
        Assert.assertEquals(true,set.contains("^foo.*$"));
        Assert.assertEquals(true,set.matches("foo"));
        Assert.assertEquals(true,set.matches("foobar"));
        Assert.assertEquals(false,set.matches("bar"));
        Assert.assertEquals(false,set.matches(""));
    }
    
    @Test
    public void testCombined()
    {
        RegexSet set = new RegexSet();
        set.add("^foo.*$");
        set.add("bar");
        set.add("[a-z][0-9][a-z][0-9]");
        
        Assert.assertEquals(true,set.contains("^foo.*$"));
        Assert.assertEquals(true,set.matches("foo"));
        Assert.assertEquals(true,set.matches("foobar"));
        Assert.assertEquals(true,set.matches("bar"));
        Assert.assertEquals(true,set.matches("c3p0"));
        Assert.assertEquals(true,set.matches("r2d2"));

        Assert.assertEquals(false,set.matches("wibble"));
        Assert.assertEquals(false,set.matches("barfoo"));
        Assert.assertEquals(false,set.matches("2b!b"));
        Assert.assertEquals(false,set.matches(""));
    }
}
