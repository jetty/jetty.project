//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RegexSetTest
{

    @Test
    public void testEmpty()
    {
        RegexSet set = new RegexSet();

        assertEquals(false, set.contains("foo"));
        assertEquals(false, set.matches("foo"));
        assertEquals(false, set.matches(""));
    }

    @Test
    public void testSimple()
    {
        RegexSet set = new RegexSet();
        set.add("foo.*");

        assertEquals(true, set.contains("foo.*"));
        assertEquals(true, set.matches("foo"));
        assertEquals(true, set.matches("foobar"));
        assertEquals(false, set.matches("bar"));
        assertEquals(false, set.matches(""));
    }

    @Test
    public void testSimpleTerminated()
    {
        RegexSet set = new RegexSet();
        set.add("^foo.*$");

        assertEquals(true, set.contains("^foo.*$"));
        assertEquals(true, set.matches("foo"));
        assertEquals(true, set.matches("foobar"));
        assertEquals(false, set.matches("bar"));
        assertEquals(false, set.matches(""));
    }

    @Test
    public void testCombined()
    {
        RegexSet set = new RegexSet();
        set.add("^foo.*$");
        set.add("bar");
        set.add("[a-z][0-9][a-z][0-9]");

        assertEquals(true, set.contains("^foo.*$"));
        assertEquals(true, set.matches("foo"));
        assertEquals(true, set.matches("foobar"));
        assertEquals(true, set.matches("bar"));
        assertEquals(true, set.matches("c3p0"));
        assertEquals(true, set.matches("r2d2"));

        assertEquals(false, set.matches("wibble"));
        assertEquals(false, set.matches("barfoo"));
        assertEquals(false, set.matches("2b!b"));
        assertEquals(false, set.matches(""));
    }
}
