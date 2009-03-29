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
package org.eclipse.jetty.plus.jndi;

import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import junit.framework.TestCase;

public class TestNamingEntryUtil extends TestCase
{
    public class MyNamingEntry extends NamingEntry
    {
        public MyNamingEntry(Object scope, String name, Object value)
        throws NamingException
        {
            super(scope, name, value);
        }
    }

    public class ScopeA extends Object
    {
        public String toString()
        {
            return this.getClass().getName()+"@"+super.hashCode();
        }
    }
    public void testGetNameForScope ()
    throws Exception
    {
        ScopeA scope = new ScopeA();
        Name name  = NamingEntryUtil.getNameForScope(scope);
        assertNotNull(name);
        assertEquals(scope.toString(), name.toString());
    }
    
    public void testGetContextForScope()
    throws Exception
    {
        ScopeA scope = new ScopeA();
        try
        {
            Context c = NamingEntryUtil.getContextForScope(scope);
            fail("context should not exist");
        }
        catch (NameNotFoundException e)
        {
            //expected
        }
        
        InitialContext ic = new InitialContext();
        Context scopeContext = ic.createSubcontext(NamingEntryUtil.getNameForScope(scope));
        assertNotNull(scopeContext);
        
        try
        {
            Context c = NamingEntryUtil.getContextForScope(scope);
            assertNotNull(c);
        }
        catch (NameNotFoundException e)
        {
            fail(e.getMessage());
        }
    }
    
    public void testMakeNamingEntryName ()
    throws Exception
    {
        Name name = NamingEntryUtil.makeNamingEntryName(null, "fee/fi/fo/fum");
        assertNotNull(name);
        assertEquals(NamingEntry.__contextName+"/fee/fi/fo/fum", name.toString());
    }
    
    public void testLookupNamingEntry ()
    throws Exception
    {
        ScopeA scope = new ScopeA();
        NamingEntry ne = NamingEntryUtil.lookupNamingEntry(scope, "foo");
        assertNull(ne);
        
        MyNamingEntry mne = new MyNamingEntry(scope, "foo", new Integer(9));
        
        ne = NamingEntryUtil.lookupNamingEntry(scope, "foo");
        assertNotNull(ne);
        assertEquals(ne, mne);
    }
    
    public void testLookupNamingEntries ()
    throws Exception
    {
        ScopeA scope = new ScopeA();
        List list = NamingEntryUtil.lookupNamingEntries(scope, MyNamingEntry.class);
        assertTrue(list.isEmpty());
        
        MyNamingEntry mne1 = new MyNamingEntry(scope, "a/b", new Integer(1));
        MyNamingEntry mne2 = new MyNamingEntry(scope, "a/c", new Integer(2));
        
        ScopeA scope2 = new ScopeA();
        MyNamingEntry mne3 = new MyNamingEntry(scope2, "a/b", new Integer(3));
        
        list = NamingEntryUtil.lookupNamingEntries(scope, MyNamingEntry.class);
        assertEquals(2, list.size());
        assertTrue (list.contains(mne1));
        assertTrue (list.contains(mne2));
        
        list = NamingEntryUtil.lookupNamingEntries(scope2, MyNamingEntry.class);
        assertEquals(1, list.size());
        assertTrue(list.contains(mne3));
        
    }
}
