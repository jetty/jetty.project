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

package org.eclipse.jetty.ee9.plus.webapp;

import java.net.URL;
import java.util.Set;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.Name;
import javax.naming.NameNotFoundException;
import javax.naming.NamingException;

import org.eclipse.jetty.ee9.nested.ContextHandler;
import org.eclipse.jetty.ee9.webapp.AbstractConfiguration;
import org.eclipse.jetty.ee9.webapp.FragmentConfiguration;
import org.eclipse.jetty.ee9.webapp.JettyWebXmlConfiguration;
import org.eclipse.jetty.ee9.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebXmlConfiguration;
import org.eclipse.jetty.jndi.ContextFactory;
import org.eclipse.jetty.plus.jndi.EnvEntry;
import org.eclipse.jetty.plus.jndi.NamingEntryUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.jndi.NamingDump;
import org.eclipse.jetty.util.jndi.NamingUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EnvConfiguration
 */
public class EnvConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(EnvConfiguration.class);

    private static final String JETTY_ENV_BINDINGS = "org.eclipse.jetty.jndi.EnvConfiguration";
    private static final String JETTY_ENV_XML = "jetty-env.xml";
    private static final String JETTY_EE_ENV_XML = "jetty-ee9-env.xml";
    private Resource jettyEnvXmlResource;
    private NamingDump _dumper;
    private ResourceFactory.Closeable _resourceFactory;

    public EnvConfiguration()
    {
        addDependencies(WebXmlConfiguration.class, MetaInfConfiguration.class, FragmentConfiguration.class);
        addDependents(PlusConfiguration.class, JettyWebXmlConfiguration.class);
        protectAndExpose("org.eclipse.jetty.jndi.");
    }

    public void setJettyEnvResource(Resource resource)
    {
        this.jettyEnvXmlResource = resource;
    }

    public void setJettyEnvXml(URL url)
    {
        this.jettyEnvXmlResource = _resourceFactory.newResource(url);
    }

    @Override
    public void preConfigure(WebAppContext context) throws Exception
    {
        _resourceFactory = ResourceFactory.closeable();
        //create a java:comp/env
        createEnvContext(context);
    }

    @Override
    public void configure(WebAppContext context) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("Created java:comp/env for webapp {}", context.getContextPath());

        //check to see if an explicit file has been set, if not,
        //look in WEB-INF/jetty-env.xml
        if (jettyEnvXmlResource == null)
        {
            //look for a configuration file
            jettyEnvXmlResource = resolveJettyEnvXml(context.getWebInf());
        }

        if (jettyEnvXmlResource != null)
        {
            //need to parse jetty-env.xml, but we also need to be able to delete
            //any NamingEntries that it creates when this WebAppContext is destroyed.
            Set<String> boundNamesBefore = NamingUtil.flattenBindings(new InitialContext(), "").keySet();

            try
            {
                XmlConfiguration configuration = new XmlConfiguration(jettyEnvXmlResource);
                configuration.setJettyStandardIdsAndProperties(context.getServer(), null);
                WebAppClassLoader.runWithServerClassAccess(() ->
                {
                    configuration.configure(context);
                    return null;
                });
            }
            finally
            {
                Set<String> boundNamesAfter = NamingUtil.flattenBindings(new InitialContext(), "").keySet();
                boundNamesAfter.removeAll(boundNamesBefore);
                context.setAttribute(JETTY_ENV_BINDINGS, boundNamesAfter);
            }
        }

        //add java:comp/env entries for any EnvEntries that have been defined so far
        bindEnvEntries(context);

        _dumper = new NamingDump(context.getClassLoader(), "java:comp");
        context.addBean(_dumper);
    }

    /**
     * Remove jndi setup from start
     *
     * @throws Exception if unable to deconfigure
     */
    @Override
    public void deconfigure(WebAppContext context) throws Exception
    {
        context.removeBean(_dumper);
        _dumper = null;

        //get rid of any bindings for comp/env for webapp
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(context.getClassLoader());
        ContextFactory.associateClassLoader(context.getClassLoader());
        try
        {
            Context ic = new InitialContext();
            Context compCtx = (Context)ic.lookup("java:comp");
            compCtx.destroySubcontext("env");

            //unbind any NamingEntries that were configured in this webapp's name space
            @SuppressWarnings("unchecked")
            Set<String> jettyEnvBoundNames = (Set<String>)context.getAttribute(JETTY_ENV_BINDINGS);
            context.setAttribute(JETTY_ENV_BINDINGS, null);
            if (jettyEnvBoundNames != null)
            {
                for (String name : jettyEnvBoundNames)
                {
                    NamingUtil.unbind(ic, name, true);
                }
            }
        }
        catch (NameNotFoundException e)
        {
            LOG.warn("Unable to destroy InitialContext", e);
        }
        finally
        {
            ContextFactory.disassociateClassLoader();
            Thread.currentThread().setContextClassLoader(oldLoader);
            IO.close(_resourceFactory);
            _resourceFactory = null;
        }
    }

    /**
     * Remove all jndi setup
     *
     * @throws Exception if unable to destroy
     */
    @Override
    public void destroy(WebAppContext context) throws Exception
    {
        try
        {
            //unbind any NamingEntries that were configured in this webapp's name space
            NamingEntryUtil.destroyContextForScope(context);
        }
        catch (NameNotFoundException e)
        {
            LOG.trace("IGNORED", e);
            LOG.debug("No jndi entries scoped to webapp {}", context);
        }
        catch (NamingException e)
        {
            LOG.debug("Error unbinding jndi entries scoped to webapp {}", context, e);
        }
    }

    /**
     * Bind all EnvEntries that have been declared, so that the processing of the
     * web.xml file can potentially override them.
     *
     * We first bind EnvEntries declared in Server scope, then WebAppContext scope.
     *
     * @param context the context to use for the object scope
     * @throws NamingException if unable to bind env entries
     */
    public void bindEnvEntries(WebAppContext context)
        throws NamingException
    {
        InitialContext ic = new InitialContext();
        Context envCtx = (Context)ic.lookup("java:comp/env");

        LOG.debug("Binding env entries from the jvm scope");
        doBindings(envCtx, null);

        LOG.debug("Binding env entries from the server scope");
        doBindings(envCtx, context.getServer());
        
        LOG.debug("Binding env entries from environment {} scope", ContextHandler.ENVIRONMENT.getName());
        doBindings(envCtx, ContextHandler.ENVIRONMENT.getName());

        LOG.debug("Binding env entries from the context scope");
        doBindings(envCtx, context);
    }

    private void doBindings(Context envCtx, Object scope) throws NamingException
    {
        for (EnvEntry ee : NamingEntryUtil.lookupNamingEntries(scope, EnvEntry.class))
        {
            ee.bindToENC(ee.getJndiName());
            Name namingEntryName = NamingEntryUtil.makeNamingEntryName(null, ee);
            NamingUtil.bind(envCtx, namingEntryName.toString(), ee); //also save the EnvEntry in the context so we can check it later
        }
    }

    protected void createEnvContext(WebAppContext wac)
        throws NamingException
    {
        ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(wac.getClassLoader());
        //ensure that we create a unique comp/env context for this webapp based off
        //its classloader
        ContextFactory.associateClassLoader(wac.getClassLoader());

        try
        {
            WebAppClassLoader.runWithServerClassAccess(() ->
            {
                Context context = new InitialContext();
                Context compCtx = (Context)context.lookup("java:comp");
                compCtx.createSubcontext("env");
                return null;
            });
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
        finally
        {
            ContextFactory.disassociateClassLoader();
            Thread.currentThread().setContextClassLoader(oldLoader);
        }
    }

    /**
     * Obtain a WEB-INF/jetty-ee9-env.xml, falling back to
     * looking for WEB-INF/jetty-env.xml.
     *
     * @param webInf the WEB-INF of the context to search
     * @return the file if it exists or null otherwise
     */
    private Resource resolveJettyEnvXml(Resource webInf)
    {
        try
        {
            if (webInf == null || !webInf.isDirectory())
                return null;

            //try to find jetty-ee9-env.xml
            Resource xmlResource = webInf.resolve(JETTY_EE_ENV_XML);
            if (!Resources.missing(xmlResource))
                return xmlResource;

            //failing that, look for jetty-env.xml
            xmlResource = webInf.resolve(JETTY_ENV_XML);
            if (!Resources.missing(xmlResource))
                return xmlResource;

            return null;
        }
        catch (Exception e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Error resolving", e);
            return null;
        }
    }
}
