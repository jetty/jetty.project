// ========================================================================
// Copyright (c) 1999-2009 Mort Bay Consulting Pty. Ltd.
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




/** javaRootURLContext
 * <p>This is the root of the java: url namespace
 *
 * <p><h4>Notes</h4>
 * <p>Thanks to Rickard Oberg for the idea of binding an ObjectFactory at "comp".
 *
 * <p><h4>Usage</h4>
 * <pre>
 */
/*
* </pre>
*
* @see
*
* 
* @version 1.0
*/
public class javaRootURLContext implements Context
{
    public static final String URL_PREFIX = "java:";

    protected Hashtable _env;

    protected static NamingContext _nameRoot;

    protected static NameParser _javaNameParser;

    
    static 
    {   
        try
        {
            _javaNameParser = new javaNameParser();       
            _nameRoot = new NamingContext();
            _nameRoot.setNameParser(_javaNameParser);
          
            StringRefAddr parserAddr = new StringRefAddr("parser", _javaNameParser.getClass().getName());
            
            Reference ref = new Reference ("javax.naming.Context",
                                           parserAddr,
                                           ContextFactory.class.getName(),
                                           (String)null);

            //bind special object factory at comp
            _nameRoot.bind ("comp", ref);
        }
        catch (Exception e)
        {
            Log.warn(e);
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
        return _javaNameParser;
    }
    
    public NameParser getNameParser (String name) 
        throws NamingException
    {
        return _javaNameParser;
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


    protected NamingContext getRoot ()
    {
        return _nameRoot;
    }


    protected Name stripProtocol (Name name)
        throws NamingException
    {
        if ((name != null) && (name.size() > 0))
        {
            String head = name.get(0);
            
            if(Log.isDebugEnabled())Log.debug("Head element of name is: "+head);

            if (head.startsWith(URL_PREFIX))
            {
                head = head.substring (URL_PREFIX.length());
                name.remove(0);
                if (head.length() > 0)
                    name.add(0, head);

                if(Log.isDebugEnabled())Log.debug("name modified to "+name.toString());
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
