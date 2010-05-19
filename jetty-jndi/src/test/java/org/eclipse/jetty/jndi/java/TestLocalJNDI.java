// ========================================================================
// Copyright (c) 2008-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.jndi.java;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;

import org.eclipse.jetty.jndi.NamingUtil;
import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

/**
 *
 */
public class TestLocalJNDI
{
    @After
    public void tearDown() throws Exception
    {
        InitialContext ic = new InitialContext();
        ic.destroySubcontext("a");
    }

    @Test
    public void testLocal () throws Exception
    {
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");
        ic.bind("foo", "xxx");

        Object o = ic.lookup("foo");
        assertNotNull(o);
        assertEquals("xxx", (String)o);

        ic.unbind("foo");
        try
        {
            ic.lookup("foo");
            fail("Foo exists");
        }
        catch (NameNotFoundException e)
        {
            //expected
        }
        Name name = parser.parse("a");
        name.addAll(parser.parse("b/c/d"));
        NamingUtil.bind(ic, name.toString(), "333");
        assertNotNull(ic.lookup("a"));
        assertNotNull(ic.lookup("a/b"));
        assertNotNull(ic.lookup("a/b/c"));
        Context c = (Context)ic.lookup("a/b/c");
        o = c.lookup("d");
        assertNotNull(o);
        assertEquals("333", (String)o);
        assertEquals("333", ic.lookup(name));
        ic.destroySubcontext("a");

        name = parser.parse("");
        name.add("x");
        Name suffix = parser.parse("y/z");
        name.addAll(suffix);
        NamingUtil.bind(ic, name.toString(), "555");
        assertEquals("555", ic.lookup(name));
        ic.destroySubcontext("x");
    }
}
