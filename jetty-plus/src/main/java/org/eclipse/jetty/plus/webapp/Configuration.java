// ========================================================================
// Copyright (c) 2006-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plus.webapp;

import java.util.Random;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NameNotFoundException;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.Link;
import org.eclipse.jetty.plus.jndi.NamingEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.plus.jndi.Transaction;
import org.eclipse.jetty.util.log.Log;


/**
 * Configuration
 *
 *
 */
public class Configuration extends AbstractConfiguration
{

    private Integer _key;
    
    
    public Configuration () throws ClassNotFoundException
    {
        super();
    }
    
    /** 
     * @see org.eclipse.jetty.plus.webapp.AbstractConfiguration#bindEnvEntry(java.lang.String, java.lang.String)
     * @param name
     * @param value
     * @throws Exception
     */
    public void bindEnvEntry(String name, Object value) throws Exception
    {    
        InitialContext ic = null;
        boolean bound = false;
        //check to see if we bound a value and an EnvEntry with this name already
        //when we processed the server and the webapp's naming environment
        //@see EnvConfiguration.bindEnvEntries()
        ic = new InitialContext();
        try
        {
            NamingEntry ne = (NamingEntry)ic.lookup("java:comp/env/"+NamingEntryUtil.makeNamingEntryName(ic.getNameParser(""), name));
            if (ne!=null && ne instanceof EnvEntry)
            {
                EnvEntry ee = (EnvEntry)ne;
                bound = ee.isOverrideWebXml();
            }
        }
        catch (NameNotFoundException e)
        {
            bound = false;
        }

        if (!bound)
        {
            //either nothing was bound or the value from web.xml should override
            Context envCtx = (Context)ic.lookup("java:comp/env");
            NamingUtil.bind(envCtx, name, value);
        }
    }

    /** 
     * Bind a resource reference.
     * 
     * If a resource reference with the same name is in a jetty-env.xml
     * file, it will already have been bound.
     * 
     * @see org.eclipse.jetty.plus.webapp.AbstractConfiguration#bindResourceRef(java.lang.String)
     * @param name
     * @throws Exception
     */
    public void bindResourceRef(String name, Class typeClass)
    throws Exception
    {
        bindEntry(name, typeClass);
    }

    /** 
     * @see org.eclipse.jetty.plus.webapp.AbstractConfiguration#bindResourceEnvRef(java.lang.String)
     * @param name
     * @throws Exception
     */
    public void bindResourceEnvRef(String name, Class typeClass)
    throws Exception
    {
        bindEntry(name, typeClass);
    }
    
    
    public void bindMessageDestinationRef(String name, Class typeClass)
    throws Exception
    {
        bindEntry(name, typeClass);
    }
    
    public void bindUserTransaction ()
    throws Exception
    {
        try
        {
           Transaction.bindToENC();
        }
        catch (NameNotFoundException e)
        {
            Log.info("No Transaction manager found - if your webapp requires one, please configure one.");
        }
    }
    
    public void configureClassLoader ()
    throws Exception
    {      
        super.configureClassLoader();
    }

    
    public void configureDefaults ()
    throws Exception
    {
        super.configureDefaults();
    }


    public void configureWebApp ()
    throws Exception
    {
        super.configureWebApp();
        //lock this webapp's java:comp namespace as per J2EE spec
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getWebAppContext().getClassLoader());
        lockCompEnv();
        Thread.currentThread().setContextClassLoader(oldLoader);
    }
    
    public void deconfigureWebApp() throws Exception
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getWebAppContext().getClassLoader());
        unlockCompEnv();
        Thread.currentThread().setContextClassLoader(oldLoader);
        super.deconfigureWebApp();
    }
    
    protected void lockCompEnv ()
    throws Exception
    {
        Random random = new Random ();
        _key = new Integer(random.nextInt());
        Context context = new InitialContext();
        Context compCtx = (Context)context.lookup("java:comp");
        compCtx.addToEnvironment("org.eclipse.jndi.lock", _key);
    }
    
    protected void unlockCompEnv ()
    throws Exception
    {
        if (_key!=null)
        {
            Context context = new InitialContext();
            Context compCtx = (Context)context.lookup("java:comp");
            compCtx.addToEnvironment("org.eclipse.jndi.unlock", _key); 
        }
    }

    /** 
     * @see org.eclipse.jetty.plus.webapp.AbstractConfiguration#parseAnnotations()
     */
    public void parseAnnotations() throws Exception
    {
        //see org.eclipse.jetty.annotations.Configuration instead
    }
    
    /**
     * Bind a resource with the given name from web.xml of the given type
     * with a jndi resource from either the server or the webapp's naming 
     * environment.
     * 
     * As the servlet spec does not cover the mapping of names in web.xml with
     * names from the execution environment, jetty uses the concept of a Link, which is
     * a subclass of the NamingEntry class. A Link defines a mapping of a name
     * from web.xml with a name from the execution environment (ie either the server or the
     * webapp's naming environment).
     * 
     * @param name name of the resource from web.xml
     * @param typeClass 
     * @throws Exception
     */
    private void bindEntry (String name, Class typeClass)
    throws Exception
    {
        String nameInEnvironment = name;
        boolean bound = false;
        
        //check if the name in web.xml has been mapped to something else
        //check a context-specific naming environment first
        Object scope = getWebAppContext();
        NamingEntry ne = NamingEntryUtil.lookupNamingEntry(scope, name);
    
        if (ne!=null && (ne instanceof Link))
        {
            //if we found a mapping, get out name it is mapped to in the environment
            nameInEnvironment = (String)((Link)ne).getObjectToBind();
            Link l = (Link)ne;
        }

        //try finding that mapped name in the webapp's environment first
        scope = getWebAppContext();
        bound = NamingEntryUtil.bindToENC(scope, name, nameInEnvironment);
        
        if (bound)
            return;

        //try the server's environment
        scope = getWebAppContext().getServer();
        bound = NamingEntryUtil.bindToENC(scope, name, nameInEnvironment);
        if (bound)
            return;

        //try the jvm environment
        bound = NamingEntryUtil.bindToENC(null, name, nameInEnvironment);
        if (bound)
            return;


        //There is no matching resource so try a default name.
        //The default name syntax is: the [res-type]/default
        //eg       javax.sql.DataSource/default
        nameInEnvironment = typeClass.getName()+"/default";
        //First try the server scope
        NamingEntry defaultNE = NamingEntryUtil.lookupNamingEntry(getWebAppContext().getServer(), nameInEnvironment);
        if (defaultNE==null)
            defaultNE = NamingEntryUtil.lookupNamingEntry(null, nameInEnvironment);
        
        if (defaultNE!=null)
            defaultNE.bindToENC(name);
        else
            throw new IllegalStateException("Nothing to bind for name "+nameInEnvironment);
    }
}
