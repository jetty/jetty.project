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
import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.util.log.Logger;




/** 
 * javaRootURLContext
 * <p>
 * This is the root of the <code>java:</code> url namespace
 * <p>
 * (Thanks to Rickard Oberg for the idea of binding an ObjectFactory at "comp")
 */
public class javaRootURLContext implements Context
{
    private static Logger __log = NamingUtil.__log;

    public static final String URL_PREFIX = "java:";

    protected Hashtable _env;

    protected static NamingContext __nameRoot;

    protected static NameParser __javaNameParser;


    static
    {
        try
        {
            __javaNameParser = new javaNameParser();
            __nameRoot = new NamingContext(null,null,null,__javaNameParser);

            StringRefAddr parserAddr = new StringRefAddr("parser", __javaNameParser.getClass().getName());

            Reference ref = new Reference ("javax.naming.Context",
                                           parserAddr,
                                           ContextFactory.class.getName(),
                                           (String)null);

            // bind special object factory at comp
            __nameRoot.bind ("comp", ref);
        }
        catch (Exception e)
        {
            __log.warn(e);
        }
    }



    /*------------------------------------------------*/
    /**
     * Creates a new <code>javaRootURLContext</code> instance.
     *
     * @param env a <code>Hashtable</code> value
     */
    public javaRootURLContext(Hashtable env)
    {
        _env = env;
    }

    public Object lookup(Name name)
        throws NamingException
    {
        return getRoot().lookup(stripProtocol(name));
    }


    public Object lookup(String name)
        throws NamingException
    {
        return getRoot().lookup(stripProtocol(name));
    }

    public void bind(Name name, Object obj)
        throws NamingException
    {
        getRoot().bind(stripProtocol(name), obj);
    }

    public void bind(String name, Object obj)
        throws NamingException
    {
        getRoot().bind(stripProtocol(name), obj);
    }

    public void unbind (String name)
        throws NamingException
    {
        getRoot().unbind(stripProtocol(name));
    }

    public void unbind (Name name)
        throws NamingException
    {
        getRoot().unbind(stripProtocol(name));
    }

    public void rename (String oldStr, String newStr)
        throws NamingException
    {
        getRoot().rename (stripProtocol(oldStr), stripProtocol(newStr));
    }

    public void rename (Name oldName, Name newName)
        throws NamingException
    {
        getRoot().rename (stripProtocol(oldName), stripProtocol(newName));
    }

    public void rebind (Name name, Object obj)
        throws NamingException
    {
        getRoot().rebind(stripProtocol(name), obj);
    }

    public void rebind (String name, Object obj)
        throws NamingException
    {
        getRoot().rebind(stripProtocol(name), obj);
    }


    public Object lookupLink (Name name)
        throws NamingException
    {
        return getRoot().lookupLink(stripProtocol(name));
    }

    public Object lookupLink (String name)
        throws NamingException
    {
        return getRoot().lookupLink(stripProtocol(name));
    }


    public Context createSubcontext (Name name)
        throws NamingException
    {
        return getRoot().createSubcontext(stripProtocol(name));
    }

    public Context createSubcontext (String name)
        throws NamingException
    {
        return getRoot().createSubcontext(stripProtocol(name));
    }


    public void destroySubcontext (Name name)
        throws NamingException
    {
        getRoot().destroySubcontext(stripProtocol(name));
    }

    public void destroySubcontext (String name)
        throws NamingException
    {
        getRoot().destroySubcontext(stripProtocol(name));
    }


    public NamingEnumeration list(Name name)
        throws NamingException
    {
        return getRoot().list(stripProtocol(name));
    }


    public NamingEnumeration list(String name)
        throws NamingException
    {
        return getRoot().list(stripProtocol(name));
    }

    public NamingEnumeration listBindings(Name name)
        throws NamingException
    {
        return getRoot().listBindings(stripProtocol(name));
    }

    public NamingEnumeration listBindings(String name)
        throws NamingException
    {
        return getRoot().listBindings(stripProtocol(name));
    }


    public Name composeName (Name name,
                             Name prefix)
        throws NamingException
    {
        return getRoot().composeName(name, prefix);
    }

    public String composeName (String name,
                               String prefix)
        throws NamingException
    {
        return getRoot().composeName(name, prefix);
    }


    public void close ()
        throws NamingException
    {
    }

    public String getNameInNamespace ()
        throws NamingException
    {
        return URL_PREFIX;
    }

    public NameParser getNameParser (Name name)
        throws NamingException
    {
        return __javaNameParser;
    }

    public NameParser getNameParser (String name)
        throws NamingException
    {
        return __javaNameParser;
    }


    public Object addToEnvironment(String propName,
                                   Object propVal)
        throws NamingException
    {
       return _env.put (propName,propVal);
    }

    public Object removeFromEnvironment(String propName)
        throws NamingException
    {
        return _env.remove (propName);
    }

    public Hashtable getEnvironment ()
    {
        return _env;
    }

    public static NamingContext getRoot ()
    {
        return __nameRoot;
    }


    protected Name stripProtocol (Name name)
        throws NamingException
    {
        if ((name != null) && (name.size() > 0))
        {
            String head = name.get(0);

            if(__log.isDebugEnabled())__log.debug("Head element of name is: "+head);

            if (head.startsWith(URL_PREFIX))
            {
                head = head.substring (URL_PREFIX.length());
                name.remove(0);
                if (head.length() > 0)
                    name.add(0, head);

                if(__log.isDebugEnabled())__log.debug("name modified to "+name.toString());
            }
        }

        return name;
    }



    protected String stripProtocol (String name)
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
