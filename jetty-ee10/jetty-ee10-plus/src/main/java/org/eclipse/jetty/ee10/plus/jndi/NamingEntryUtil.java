//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.naming.Binding;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;

import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class NamingEntryUtil
{
    private static final Logger LOG = LoggerFactory.getLogger(NamingEntryUtil.class);

    /**
     * Link a name in a webapp's java:/comp/evn namespace to a pre-existing
     * resource. The pre-existing resource can be either in the webapp's
     * naming environment, or in the container's naming environment. Webapp's
     * environment takes precedence over the server's namespace.
     *
     * @param scope the scope of the lookup
     * @param asName the name to bind as
     * @param mappedName the name from the environment to link to asName
     * @return true if bind success, false if not bound
     * @throws NamingException if unable to bind
     */
    public static boolean bindToENC(Object scope, String asName, String mappedName)
        throws NamingException
    {
        if (asName == null || asName.trim().equals(""))
            throw new NamingException("No name for NamingEntry");

        if (mappedName == null || "".equals(mappedName))
            mappedName = asName;

        NamingEntry entry = lookupNamingEntry(scope, mappedName);
        if (entry == null)
            return false;

        entry.bindToENC(asName);
        return true;
    }

    /**
     * Find a NamingEntry in the given scope.
     *
     * @param scope the object scope
     * @param jndiName the jndi name
     * @return the naming entry for the given scope
     * @throws NamingException if unable to lookup naming entry
     */
    public static NamingEntry lookupNamingEntry(Object scope, String jndiName)
        throws NamingException
    {
        NamingEntry entry = null;
        try
        {
            Name scopeName = getNameForScope(scope);
            InitialContext ic = new InitialContext();
            NameParser parser = ic.getNameParser("");
            Name namingEntryName = makeNamingEntryName(parser, jndiName);
            scopeName.addAll(namingEntryName);
            entry = (NamingEntry)ic.lookup(scopeName);
        }
        catch (NameNotFoundException ignored)
        {
        }

        return entry;
    }

    public static Object lookup(Object scope, String jndiName) throws NamingException
    {
        Name scopeName = getNameForScope(scope);
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");
        scopeName.addAll(parser.parse(jndiName));
        return ic.lookup(scopeName);
    }

    /**
     * Get all NameEntries of a certain type in the given naming
     * environment scope (server-wide names or context-specific names)
     *
     * @param scope the object scope
     * @param clazz the type of the entry
     * @return all NameEntries of a certain type in the given naming environment scope (server-wide names or context-specific names)
     * @throws NamingException if unable to lookup the naming entries
     */
    public static <T> List<? extends T> lookupNamingEntries(Object scope, Class<T> clazz)
        throws NamingException
    {
        try
        {
            Context scopeContext = getContextForScope(scope);
            Context namingEntriesContext = (Context)scopeContext.lookup(NamingEntry.__contextName);
            ArrayList<Object> list = new ArrayList<Object>();
            lookupNamingEntries(list, namingEntriesContext, clazz);
            return (List<T>)list;
        }
        catch (NameNotFoundException e)
        {
            return Collections.emptyList();
        }
    }

    /**
     * Build up a list of NamingEntry objects that are of a specific type.
     */
    private static List<Object> lookupNamingEntries(List<Object> list, Context context, Class<?> clazz)
        throws NamingException
    {
        try
        {
            NamingEnumeration<Binding> nenum = context.listBindings("");
            while (nenum.hasMoreElements())
            {
                Binding binding = nenum.next();
                if (binding.getObject() instanceof Context)
                    lookupNamingEntries(list, (Context)binding.getObject(), clazz);
                else if (clazz.isInstance(binding.getObject()))
                    list.add(binding.getObject());
            }
        }
        catch (NameNotFoundException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("No entries of type {} in context={}", clazz.getName(), context);
        }

        return list;
    }

    public static Name makeNamingEntryName(NameParser parser, NamingEntry namingEntry)
        throws NamingException
    {
        return makeNamingEntryName(parser, (namingEntry == null ? null : namingEntry.getJndiName()));
    }

    public static Name makeNamingEntryName(NameParser parser, String jndiName)
        throws NamingException
    {
        if (jndiName == null)
            return null;

        if (parser == null)
        {
            InitialContext ic = new InitialContext();
            parser = ic.getNameParser("");
        }

        Name name = parser.parse("");
        name.add(NamingEntry.__contextName);
        name.addAll(parser.parse(jndiName));
        return name;
    }

    public static Name getNameForScope(Object scope)
    {
        try
        {
            InitialContext ic = new InitialContext();
            NameParser parser = ic.getNameParser("");
            Name name = parser.parse("");
            if (scope != null)
            {
                name.add(canonicalizeScope(scope));
            }
            return name;
        }
        catch (NamingException e)
        {
            LOG.warn("Unable to get name for scope {}", scope, e);
            return null;
        }
    }

    public static Context getContextForScope(Object scope)
        throws NamingException
    {
        InitialContext ic = new InitialContext();
        NameParser parser = ic.getNameParser("");
        Name name = parser.parse("");
        if (scope != null)
        {
            name.add(canonicalizeScope(scope));
        }
        return (Context)ic.lookup(name);
    }

    public static Context getContextForNamingEntries(Object scope)
        throws NamingException
    {
        Context scopeContext = getContextForScope(scope);
        return (Context)scopeContext.lookup(NamingEntry.__contextName);
    }

    private static String canonicalizeScope(Object scope)
    {
        if (scope == null)
            return "";

        String str = scope.getClass().getName() + "@" + Long.toHexString(scope.hashCode());
        str = StringUtil.replace(str, '/', '_');
        str = StringUtil.replace(str, ' ', '_');
        return str;
    }
}
