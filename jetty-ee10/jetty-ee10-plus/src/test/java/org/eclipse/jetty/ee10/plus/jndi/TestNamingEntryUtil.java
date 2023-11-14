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

package org.eclipse.jetty.ee10.plus.jndi;

import java.util.List;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.plus.jndi.NamingEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;

@Isolated("jndi entries")
public class TestNamingEntryUtil
{
    public class MyNamingEntry extends NamingEntry
    {
        public MyNamingEntry(Object scope, String name, Object value)
            throws NamingException
        {
            super(scope, name, value);
        }
    }

    public class ScopeA
    {
        @Override
        public String toString()
        {
            return this.getClass().getName() + "@" + Long.toHexString(super.hashCode());
        }
    }

    @Test
    public void testGetNameForScope() throws Exception
    {
        ScopeA scope = new ScopeA();
        Name name = NamingEntryUtil.getNameForScope(scope);
        assertNotNull(name);
        assertEquals(scope.toString(), name.toString());
    }

    @Test
    public void testGetContextForScope() throws Exception
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

    @Test
    public void testDestroySubcontext() throws Exception
    {
        //create some NamingEntry in scope of a webapp
        WebAppContext wac = new WebAppContext();
        MyNamingEntry namingEntry1 = new MyNamingEntry(wac, "xxx", "111");
        MyNamingEntry namingEntry2 = new MyNamingEntry(wac, "yyy", "222");

        assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "xxx"));
        assertNotNull(NamingEntryUtil.lookupNamingEntry(wac, "yyy"));

        NamingEntryUtil.destroyContextForScope(wac);
        assertNull(NamingEntryUtil.lookupNamingEntry(wac, "xxx"));
        assertNull(NamingEntryUtil.lookupNamingEntry(wac, "yyy"));
    }

    @Test
    public void testMakeNamingEntryName() throws Exception
    {
        Name name = NamingEntryUtil.makeNamingEntryName(null, "fee/fi/fo/fum");
        assertNotNull(name);
        assertEquals(NamingEntry.__contextName + "/fee/fi/fo/fum", name.toString());
    }

    @Test
    public void testLookupNamingEntry() throws Exception
    {
        ScopeA scope = new ScopeA();
        NamingEntry ne = NamingEntryUtil.lookupNamingEntry(scope, "foo");
        assertNull(ne);

        MyNamingEntry mne = new MyNamingEntry(scope, "foo", 9);

        ne = NamingEntryUtil.lookupNamingEntry(scope, "foo");
        assertNotNull(ne);
        assertEquals(ne, mne);
    }

    @Test
    public void testLookupNamingEntries() throws Exception
    {
        ScopeA scope = new ScopeA();
        List<? extends MyNamingEntry> list = NamingEntryUtil.lookupNamingEntries(scope, MyNamingEntry.class);
        assertThat(list, is(empty()));

        MyNamingEntry mne1 = new MyNamingEntry(scope, "a/b", 1);
        MyNamingEntry mne2 = new MyNamingEntry(scope, "a/c", 2);

        ScopeA scope2 = new ScopeA();
        MyNamingEntry mne3 = new MyNamingEntry(scope2, "a/b", 3);

        list = NamingEntryUtil.lookupNamingEntries(scope, MyNamingEntry.class);
        assertThat(list, containsInAnyOrder(mne1, mne2));

        list = NamingEntryUtil.lookupNamingEntries(scope2, MyNamingEntry.class);
        assertThat(list, containsInAnyOrder(mne3));
    }
}
