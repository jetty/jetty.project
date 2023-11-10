//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.jndi.java;

import java.util.Hashtable;
import java.util.Map;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import org.eclipse.jetty.util.jndi.NamingUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 *
 */
public class TestLocalJNDI
{
    public static class FruitFactory implements ObjectFactory
    {
        public FruitFactory()
        {
        }

        @Override
        public Object getObjectInstance(Object obj, Name name, Context ctx, Hashtable env) throws Exception
        {

            if (!env.containsKey("flavour"))
                throw new Exception("No flavour!");

            if (obj instanceof Reference)
            {
                Reference ref = (Reference)obj;
                if (ref.getClassName().equals(Fruit.class.getName()))
                {
                    RefAddr addr = ref.get("fruit");
                    if (addr != null)
                    {
                        return new Fruit((String)addr.getContent());
                    }
                }
            }
            return null;
        }
    }

    public static class Fruit implements Referenceable
    {
        String fruit;

        public Fruit(String f)
        {
            fruit = f;
        }

        @Override
        public Reference getReference() throws NamingException
        {
            return new Reference(
                Fruit.class.getName(),
                new StringRefAddr("fruit", fruit),
                FruitFactory.class.getName(),
                null);          // Factory location
        }

        @Override
        public String toString()
        {
            return fruit;
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        InitialContext ic = new InitialContext();
        ic.destroySubcontext("a");
    }

    @Test
    public void testLocalReferenceable() throws Exception
    {
        Hashtable<String, String> env1 = new Hashtable<String, String>();
        env1.put("flavour", "orange");
        InitialContext ic1 = new InitialContext(env1);

        ic1.bind("valencia", new Fruit("orange"));

        Object o = ic1.lookup("valencia");

        Hashtable<String, String> env2 = new Hashtable<String, String>();
        InitialContext ic2 = new InitialContext(env2);
        try
        {
            o = ic2.lookup("valencia");
            fail("Constructed object from reference without correct environment");
        }
        catch (Exception e)
        {
            assertEquals("No flavour!", e.getMessage());
        }
    }

    @Test
    public void testLocalEnvironment() throws Exception
    {
        Hashtable<String, String> env1 = new Hashtable<String, String>();
        env1.put("make", "holden");
        env1.put("model", "commodore");

        Object car1 = new Object();

        InitialContext ic = new InitialContext(env1);
        ic.bind("car1", car1);
        assertNotNull(ic.lookup("car1"));
        assertEquals(car1, ic.lookup("car1"));

        Context carz = ic.createSubcontext("carz");
        assertNotNull(carz);
        Hashtable ht = carz.getEnvironment();
        assertNotNull(ht);
        assertEquals("holden", ht.get("make"));
        assertEquals("commodore", ht.get("model"));

        Hashtable<String, String> env2 = new Hashtable<String, String>();
        env2.put("flavour", "strawberry");
        InitialContext ic2 = new InitialContext(env2);
        assertEquals(car1, ic2.lookup("car1"));
        Context c = (Context)ic2.lookup("carz");
        assertNotNull(c);
        ht = c.getEnvironment();
        assertEquals("holden", ht.get("make"));
        assertEquals("commodore", ht.get("model"));

        Context icecreamz = ic2.createSubcontext("icecreamz");
        ht = icecreamz.getEnvironment();
        assertNotNull(ht);
        assertEquals("strawberry", ht.get("flavour"));

        Context hatchbackz = ic2.createSubcontext("carz/hatchbackz");
        assertNotNull(hatchbackz);
        ht = hatchbackz.getEnvironment();
        assertNotNull(ht);
        assertEquals("holden", ht.get("make"));
        assertEquals("commodore", ht.get("model"));
        assertEquals(null, ht.get("flavour"));

        c = (Context)ic.lookup("carz/hatchbackz");
        assertNotNull(c);
        assertEquals(hatchbackz, c);
    }

    @Test
    public void testLocal() throws Exception
    {
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");

        ic.bind("foo", "xxx");

        Object o = ic.lookup("foo");
        assertNotNull(o);
        assertEquals("xxx", (String)o);

        ic.unbind("foo");
        assertThrows(NameNotFoundException.class, () -> ic.lookup("foo"));

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
        assertThrows(NameNotFoundException.class, () -> ic.lookup("a"));

        name = parser.parse("");
        name.add("x");
        Name suffix = parser.parse("y/z");
        name.addAll(suffix);
        NamingUtil.bind(ic, name.toString(), "555");
        assertEquals("555", ic.lookup(name));
        ic.destroySubcontext("x");

        //add some deep bindings into the tree
        parser.parse("");
        final Name thing = parser.parse("top/middle/bottom/thing");
        NamingUtil.bind(ic, thing.toString(), "leaf");
        assertEquals("leaf", ic.lookup(thing));

        parser.parse("");
        final Name thing2 = parser.parse("top2/middle2/bottom2/thing2");
        NamingUtil.bind(ic, thing2.toString(), "leaf2");
        assertEquals("leaf2", ic.lookup(thing2));

        //and a shallow binding
        parser.parse("");
        Name shallow = parser.parse("shallow");
        NamingUtil.bind(ic, shallow.toString(), "leaf3");


        //and a context with no bindings
        Name node = parser.parse("");
        node = parser.parse("node");
        Context nodeContext = ic.createSubcontext(node);
        Context subnodeContext = nodeContext.createSubcontext("subnode");

        //check flatten bindings
        Map<String, Object> map = NamingUtil.flattenBindings(ic, "");
        assertThat(map.keySet(), containsInAnyOrder(thing.toString(), thing2.toString(), shallow.toString(), node.add("subnode").toString()));
        assertEquals(subnodeContext, map.get(node.toString()));

        //check destroying
        nodeContext.destroySubcontext("subnode");
        assertThrows(NameNotFoundException.class, () -> ic.lookup("node/subnode"));

        ic.destroySubcontext("node");
        assertThrows(NameNotFoundException.class, () -> ic.lookup("node"));

        //test NamingUtil.unbind with deep unbind
        NamingUtil.unbind(ic, thing.toString(), true);
        assertThrows(NameNotFoundException.class, () -> ic.lookup("top"));

        //test NamingUtil.unbind without a deep unbind
        NamingUtil.unbind(ic, thing2.toString(), false);
        assertThrows(NameNotFoundException.class, () -> ic.lookup(thing2.toString()));
        assertDoesNotThrow(() -> ic.lookup("top2/middle2/bottom2"));
    }
}
