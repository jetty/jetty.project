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

package org.eclipse.jetty.jndi.java;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.fail;

import java.util.Hashtable;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingException;
import javax.naming.RefAddr;
import javax.naming.Reference;
import javax.naming.Referenceable;
import javax.naming.StringRefAddr;
import javax.naming.spi.ObjectFactory;

import org.eclipse.jetty.jndi.NamingUtil;
import org.junit.After;
import org.junit.Test;

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

        public Object getObjectInstance(Object obj, Name name, Context ctx, Hashtable env) throws Exception
        {

            if (!env.containsKey("flavour"))
                throw new Exception ("No flavour!");

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

        public Reference getReference() throws NamingException
        {
            return new Reference(
                Fruit.class.getName(),
                new StringRefAddr("fruit", fruit),
                FruitFactory.class.getName(),
                null);          // Factory location
        }

        public String toString()
        {
            return fruit;
        }
    }








    @After
    public void tearDown() throws Exception
    {
        InitialContext ic = new InitialContext();
        ic.destroySubcontext("a");
    }


    @Test
    public void testLocalReferenceable() throws Exception
    {
        Hashtable<String,String> env1 = new Hashtable<String,String>();
        env1.put("flavour", "orange");
        InitialContext ic1 = new InitialContext(env1);

        ic1.bind("valencia", new Fruit("orange"));

        Object o = ic1.lookup("valencia");

        Hashtable<String,String> env2 = new Hashtable<String,String>();
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
        Hashtable<String,String> env1 = new Hashtable<String,String>();
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

        Hashtable<String,String> env2 = new Hashtable<String,String>();
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
        try
        {
            ic.lookup("a");
            fail("context a was not destroyed");
        }
        catch (NameNotFoundException e)
        {
            //expected
        }

        name = parser.parse("");
        name.add("x");
        Name suffix = parser.parse("y/z");
        name.addAll(suffix);
        NamingUtil.bind(ic, name.toString(), "555");
        assertEquals("555", ic.lookup(name));
        ic.destroySubcontext("x");
    }
}
