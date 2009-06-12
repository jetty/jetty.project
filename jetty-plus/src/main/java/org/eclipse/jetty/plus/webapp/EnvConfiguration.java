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

package org.eclipse.jetty.plus.webapp;

import java.net.URL;
import java.util.Iterator;
import java.util.List;

import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.eclipse.jetty.jndi.NamingUtil;
import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.NamingEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;


/**
 * EnvConfiguration
 *
 *
 */
public class EnvConfiguration implements Configuration
{
    private URL jettyEnvXmlUrl;

  
 

    public void setJettyEnvXml (URL url)
    {
        this.jettyEnvXmlUrl = url;
    }
    
 

    /** 
     * @see org.eclipse.jetty.webapp.Configuration#configureDefaults()
     * @throws Exception
     */
    public void preConfigure (WebAppContext context) throws Exception
    {        
        //create a java:comp/env
        createEnvContext(context);
    }

    /** 
     * @throws Exception
     */
    public void configure (WebAppContext context) throws Exception
    {  
        if (Log.isDebugEnabled())
            Log.debug("Created java:comp/env for webapp "+context.getContextPath());
        
        //check to see if an explicit file has been set, if not,
        //look in WEB-INF/jetty-env.xml
        if (jettyEnvXmlUrl == null)
        {
            
            //look for a file called WEB-INF/jetty-env.xml
            //and process it if it exists
            org.eclipse.jetty.util.resource.Resource web_inf = context.getWebInf();
            if(web_inf!=null && web_inf.isDirectory())
            {
                org.eclipse.jetty.util.resource.Resource jettyEnv = web_inf.addPath("jetty-env.xml");
                if(jettyEnv.exists())
                {
                    jettyEnvXmlUrl = jettyEnv.getURL();
                }
            }
        }
        
        //apply the jetty-env.xml file
        if (jettyEnvXmlUrl != null)
        {
            XmlConfiguration configuration = new XmlConfiguration(jettyEnvXmlUrl);
            configuration.configure(context);
        }
     
        //add java:comp/env entries for any EnvEntries that have been defined so far
        bindEnvEntries(context);
    }

    public void postConfigure(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub
        
    }
    
    
    /** 
     * Remove all jndi setup
     * @see org.eclipse.jetty.webapp.Configuration#deconfigureWebApp()
     * @throws Exception
     */
    public void deconfigure (WebAppContext context) throws Exception
    {
        //get rid of any bindings for comp/env for webapp
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        try
        {
            Context ic = new InitialContext();
            Context compCtx =  (Context)ic.lookup ("java:comp");
            compCtx.destroySubcontext("env");
        }
        catch (NameNotFoundException e)
        {
            Log.warn(e);
        }

        //unbind any NamingEntries that were configured in this webapp's name space
        try
        {
            Context scopeContext = NamingEntryUtil.getContextForScope(context);
            scopeContext.destroySubcontext(NamingEntry.__contextName);
        }
        catch (NameNotFoundException e)
        {
            Log.ignore(e);
            Log.debug("No naming entries configured in environment for webapp "+context);
        }
        Thread.currentThread().setContextClassLoader(oldLoader);
    }
    
    /**
     * Bind all EnvEntries that have been declared, so that the processing of the
     * web.xml file can potentially override them.
     * 
     * We first bind EnvEntries declared in Server scope, then WebAppContext scope.
     * @throws NamingException
     */
    public void bindEnvEntries (WebAppContext context)
    throws NamingException
    {
        Log.debug("Binding env entries from the jvm scope");
        InitialContext ic = new InitialContext();
        Context envCtx = (Context)ic.lookup("java:comp/env");
        Object scope = null;
        List list = NamingEntryUtil.lookupNamingEntries(scope, EnvEntry.class);
        Iterator itor = list.iterator();
        while (itor.hasNext())
        {
            EnvEntry ee = (EnvEntry)itor.next();
            ee.bindToENC(ee.getJndiName());
            Name namingEntryName = NamingEntryUtil.makeNamingEntryName(null, ee);
            NamingUtil.bind(envCtx, namingEntryName.toString(), ee);//also save the EnvEntry in the context so we can check it later          
        }
        
        Log.debug("Binding env entries from the server scope");
        
        scope = context.getServer();
        list = NamingEntryUtil.lookupNamingEntries(scope, EnvEntry.class);
        itor = list.iterator();
        while (itor.hasNext())
        {
            EnvEntry ee = (EnvEntry)itor.next();
            ee.bindToENC(ee.getJndiName());
            Name namingEntryName = NamingEntryUtil.makeNamingEntryName(null, ee);
            NamingUtil.bind(envCtx, namingEntryName.toString(), ee);//also save the EnvEntry in the context so we can check it later          
        }
        
        Log.debug("Binding env entries from the context scope");
        scope = context;
        list = NamingEntryUtil.lookupNamingEntries(scope, EnvEntry.class);
        itor = list.iterator();
        while (itor.hasNext())
        {
            EnvEntry ee = (EnvEntry)itor.next();
            ee.bindToENC(ee.getJndiName());
            Name namingEntryName = NamingEntryUtil.makeNamingEntryName(null, ee);
            NamingUtil.bind(envCtx, namingEntryName.toString(), ee);//also save the EnvEntry in the context so we can check it later
        }
    }  
    
    protected void createEnvContext (WebAppContext wac)
    throws NamingException
    {
        ClassLoader old_loader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(wac.getClassLoader());
        try
        {
            Context context = new InitialContext();
            Context compCtx =  (Context)context.lookup ("java:comp");
            compCtx.createSubcontext("env");
        }
        finally 
        {
           Thread.currentThread().setContextClassLoader(old_loader);
       }
    }
}
