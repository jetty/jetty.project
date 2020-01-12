//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
