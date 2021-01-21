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

package org.eclipse.jetty.jndi.java;

import java.util.Hashtable;
import javax.naming.Context;
import javax.naming.Name;
import javax.naming.NameParser;
import javax.naming.NamingEnumeration;
import javax.naming.NamingException;
import javax.naming.Reference;
import javax.naming.StringRefAddr;

import org.eclipse.jetty.jndi.ContextFactory;
import org.eclipse.jetty.jndi.NamingContext;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

// This is the required name for JNDI
// @checkstyle-disable-check : TypeNameCheck

/**
 * javaRootURLContext
 * <p>
 * This is the root of the <code>java:</code> url namespace
 * <p>
 * (Thanks to Rickard Oberg for the idea of binding an ObjectFactory at "comp")
 */
public class javaRootURLContext implements Context
{
    private static final Logger LOG = Log.getLogger(javaRootURLContext.class);

    public static final String URL_PREFIX = "java:";

    protected Hashtable _env;

    protected static NamingContext __nameRoot;

    protected static NameParser __javaNameParser;

    static
    {
        try
        {
            __javaNameParser = new javaNameParser();
            __nameRoot = new NamingContext(null, null, null, __javaNameParser);

            StringRefAddr parserAddr = new StringRefAddr("parser", __javaNameParser.getClass().getName());

            Reference ref = new Reference("javax.naming.Context",
                parserAddr,
                ContextFactory.class.getName(),
                null);

            // bind special object factory at comp
            __nameRoot.bind("comp", ref);
        }
        catch (Exception e)
        {
            LOG.warn(e);
        }
    }

    /**
     * Creates a new <code>javaRootURLContext</code> instance.
     *
     * @param env a <code>Hashtable</code> value
     */
    public javaRootURLContext(Hashtable env)
    {
        _env = env;
    }

    @Override
    public Object lookup(Name name)
        throws NamingException
    {
        return getRoot().lookup(stripProtocol(name));
    }

    @Override
    public Object lookup(String name)
        throws NamingException
    {
        return getRoot().lookup(stripProtocol(name));
    }

    @Override
    public void bind(Name name, Object obj)
        throws NamingException
    {
        getRoot().bind(stripProtocol(name), obj);
    }

    @Override
    public void bind(String name, Object obj)
        throws NamingException
    {
        getRoot().bind(stripProtocol(name), obj);
    }

    @Override
    public void unbind(String name)
        throws NamingException
    {
        getRoot().unbind(stripProtocol(name));
    }

    @Override
    public void unbind(Name name)
        throws NamingException
    {
        getRoot().unbind(stripProtocol(name));
    }

    @Override
    public void rename(String oldStr, String newStr)
        throws NamingException
    {
        getRoot().rename(stripProtocol(oldStr), stripProtocol(newStr));
    }

    @Override
    public void rename(Name oldName, Name newName)
        throws NamingException
    {
        getRoot().rename(stripProtocol(oldName), stripProtocol(newName));
    }

    @Override
    public void rebind(Name name, Object obj)
        throws NamingException
    {
        getRoot().rebind(stripProtocol(name), obj);
    }

    @Override
    public void rebind(String name, Object obj)
        throws NamingException
    {
        getRoot().rebind(stripProtocol(name), obj);
    }

    @Override
    public Object lookupLink(Name name)
        throws NamingException
    {
        return getRoot().lookupLink(stripProtocol(name));
    }

    @Override
    public Object lookupLink(String name)
        throws NamingException
    {
        return getRoot().lookupLink(stripProtocol(name));
    }

    @Override
    public Context createSubcontext(Name name)
        throws NamingException
    {
        return getRoot().createSubcontext(stripProtocol(name));
    }

    @Override
    public Context createSubcontext(String name)
        throws NamingException
    {
        return getRoot().createSubcontext(stripProtocol(name));
    }

    @Override
    public void destroySubcontext(Name name)
        throws NamingException
    {
        getRoot().destroySubcontext(stripProtocol(name));
    }

    @Override
    public void destroySubcontext(String name)
        throws NamingException
    {
        getRoot().destroySubcontext(stripProtocol(name));
    }

    @Override
    public NamingEnumeration list(Name name)
        throws NamingException
    {
        return getRoot().list(stripProtocol(name));
    }

    @Override
    public NamingEnumeration list(String name)
        throws NamingException
    {
        return getRoot().list(stripProtocol(name));
    }

    @Override
    public NamingEnumeration listBindings(Name name)
        throws NamingException
    {
        return getRoot().listBindings(stripProtocol(name));
    }

    @Override
    public NamingEnumeration listBindings(String name)
        throws NamingException
    {
        return getRoot().listBindings(stripProtocol(name));
    }

    @Override
    public Name composeName(Name name,
                            Name prefix)
        throws NamingException
    {
        return getRoot().composeName(name, prefix);
    }

    @Override
    public String composeName(String name,
                              String prefix)
        throws NamingException
    {
        return getRoot().composeName(name, prefix);
    }

    @Override
    public void close()
        throws NamingException
    {
    }

    @Override
    public String getNameInNamespace()
        throws NamingException
    {
        return URL_PREFIX;
    }

    @Override
    public NameParser getNameParser(Name name)
        throws NamingException
    {
        return __javaNameParser;
    }

    @Override
    public NameParser getNameParser(String name)
        throws NamingException
    {
        return __javaNameParser;
    }

    @Override
    public Object addToEnvironment(String propName,
                                   Object propVal)
        throws NamingException
    {
        return _env.put(propName, propVal);
    }

    @Override
    public Object removeFromEnvironment(String propName)
        throws NamingException
    {
        return _env.remove(propName);
    }

    @Override
    public Hashtable getEnvironment()
    {
        return _env;
    }

    public static NamingContext getRoot()
    {
        return __nameRoot;
    }

    protected Name stripProtocol(Name name)
        throws NamingException
    {
        if ((name != null) && (name.size() > 0))
        {
            String head = name.get(0);

            if (LOG.isDebugEnabled())
                LOG.debug("Head element of name is: " + head);

            if (head.startsWith(URL_PREFIX))
            {
                head = head.substring(URL_PREFIX.length());
                name.remove(0);
                if (head.length() > 0)
                    name.add(0, head);

                if (LOG.isDebugEnabled())
                    LOG.debug("name modified to " + name.toString());
            }
        }

        return name;
    }

    protected String stripProtocol(String name)
    {
        String newName = name;

        if ((name != null) && (!name.equals("")))
        {
            if (name.startsWith(URL_PREFIX))
                newName = name.substring(URL_PREFIX.length());
        }

        return newName;
    }
}
