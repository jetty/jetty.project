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

package org.eclipse.jetty.ee.osgi.boot;

import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jetty.ee.common.EnterpriseEditionVersion;
import org.eclipse.jetty.ee.common.WebAppClassLoader;
import org.eclipse.jetty.ee.webapp.Configuration;
import org.eclipse.jetty.ee.webapp.Configurations;
import org.eclipse.jetty.ee.webapp.WebAppContext;
import org.eclipse.jetty.osgi.AbstractContextProvider;
import org.eclipse.jetty.osgi.AbstractEEActivator;
import org.eclipse.jetty.osgi.BundleMetadata;
import org.eclipse.jetty.osgi.ContextFactory;
import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.osgi.OSGiWebappClassLoader;
import org.eclipse.jetty.osgi.OSGiWebappConstants;
import org.eclipse.jetty.osgi.util.OSGiClassLoader;
import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * EEActivator
 * <p>
 * Enable deployment of webapps/contexts to EE
 */
public class EEActivator extends AbstractEEActivator
{
    private static final Logger LOG = LoggerFactory.getLogger(EEActivator.class);

    public static final String ENVIRONMENT = EnterpriseEditionVersion.getEnterpriseEditionVersion().environmentName();

    @Override
    public String getEnvironment()
    {
        return ENVIRONMENT;
    }

    @Override
    public ContextFactory newContextFactory(Bundle bundle)
    {
        return new EECommonContextFactory(bundle);
    }

    @Override
    public String getMetaInfContainerBundlePatternAttributeName()
    {
        return OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN;
    }

    @Override
    public ContextFactory newWebAppFactory(Bundle bundle)
    {
        return new EECommonWebAppFactory(bundle);
    }

    protected WebAppContext newWebAppContext()
    {
        return new WebAppContext();
    }

    public class EECommonContextFactory implements ContextFactory
    {
        private final Bundle _myBundle;

        public EECommonContextFactory(Bundle bundle)
        {
            _myBundle = bundle;
        }

        @Override
        public ContextHandler createContextHandler(AbstractContextProvider provider, BundleMetadata metadata)
            throws Exception
        {
            String jettyHome = (String)provider.getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            Path jettyHomePath = (StringUtil.isBlank(jettyHome) ? null : Paths.get(jettyHome));

            ContextHandler contextHandler = new ContextHandler();
            contextHandler.setAttribute(BundleMetadata.BUNDLE, metadata.getBundle());
            contextHandler.setAttribute(OSGiWebappConstants.JETTY_BOOT_BUNDLE_CONTEXT, getBootBundleContext());

            ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);

            //Make base resource that of the bundle
            contextHandler.setBaseResource(Util.newBundleResource(metadata.getBundle(), resourceFactory));

            // provides access to core classes
            ClassLoader coreLoader = (ClassLoader)provider.getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
            if (LOG.isDebugEnabled())
                LOG.debug("Core classloader = {}", coreLoader.getClass());

            //provide access to all ee classes
            ClassLoader environmentLoader = new OSGiClassLoader(coreLoader, _myBundle);

            //Use a classloader that knows about the common jetty parent loader, and also the bundle                  
            OSGiClassLoader classLoader = new OSGiClassLoader(environmentLoader, metadata.getBundle());
            contextHandler.setClassLoader(classLoader);

            //Apply any context xml file
            String tmp = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
            final URI contextXmlURI = Util.resolvePathAsLocalizedURI(tmp, metadata.getBundle(), jettyHomePath);

            if (contextXmlURI != null)
            {
                ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    Thread.currentThread().setContextClassLoader(contextHandler.getClassLoader());
                    WebAppClassLoader.runWithHiddenClassAccess(() ->
                    {
                        Server server = provider.getServer();
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(ResourceFactory.of(contextHandler).newResource(contextXmlURI));
                        xmlConfiguration.setJettyStandardIdsAndProperties(server, null);
                        WebAppClassLoader.runWithHiddenClassAccess(() ->
                        {
                            Map<String, String> properties = new HashMap<>();
                            properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, metadata.getPath().toUri().toString());
                            properties.put(OSGiServerConstants.JETTY_HOME, (String)server.getAttribute(OSGiServerConstants.JETTY_HOME));
                            properties.put("environment", ENVIRONMENT);
                            xmlConfiguration.getProperties().putAll(properties);
                            xmlConfiguration.configure(contextHandler);
                            return null;
                        });
                        return null;
                    });
                }
                catch (Exception e)
                {
                    LOG.warn("Error applying context xml", e);
                    throw e;
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(oldLoader);
                }
            }

            //osgi Enterprise Spec r4 p.427
            contextHandler.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, metadata.getBundle().getBundleContext());

            //make sure we protect also the osgi dirs specified by OSGi Enterprise spec
            String[] targets = contextHandler.getProtectedTargets();
            int length = (targets == null ? 0 : targets.length);

            String[] updatedTargets = null;
            if (targets != null)
            {
                updatedTargets = new String[length + OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
                System.arraycopy(targets, 0, updatedTargets, 0, length);
            }
            else
                updatedTargets = new String[OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
            System.arraycopy(OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS, 0, updatedTargets, length, OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length);
            contextHandler.setProtectedTargets(updatedTargets);

            return contextHandler;
        }
    }

    public class EECommonWebAppFactory implements ContextFactory
    {
        private final Bundle _myBundle;

        public EECommonWebAppFactory(Bundle bundle)
        {
            _myBundle = bundle;
        }

        @Override
        public ContextHandler createContextHandler(AbstractContextProvider provider, BundleMetadata metadata)
            throws Exception
        {
            String jettyHome = (String)provider.getServer().getAttribute(OSGiServerConstants.JETTY_HOME);
            Path jettyHomePath = StringUtil.isBlank(jettyHome) ? null : ResourceFactory.of(provider.getServer()).newResource(jettyHome).getPath();

            WebAppContext webApp = newWebAppContext();
            webApp.setAttribute(BundleMetadata.BUNDLE, metadata.getBundle());
            webApp.setAttribute(OSGiWebappConstants.JETTY_BOOT_BUNDLE_CONTEXT, getBootBundleContext());
            ResourceFactory resourceFactory = ResourceFactory.of(webApp);

            //Apply defaults from the deployer providers
            webApp.initializeDefaults(provider.getAttributes());

            // provides access to core classes
            ClassLoader coreLoader = (ClassLoader)provider.getServer().getAttribute(OSGiServerConstants.SERVER_CLASSLOADER);
            if (LOG.isDebugEnabled())
                LOG.debug("Core classloader = {}", coreLoader);

            //provide access to all ee classes
            ClassLoader environmentLoader = new OSGiClassLoader(coreLoader, _myBundle);
            if (LOG.isDebugEnabled())
                LOG.debug("Environment classloader = {}", environmentLoader);

            //Ensure Configurations.getKnown is called with a classloader that can see all of the ee and core classes

            ClassLoader old = Thread.currentThread().getContextClassLoader();
            try
            {
                Thread.currentThread().setContextClassLoader(environmentLoader);
                WebAppClassLoader.runWithHiddenClassAccess(() ->
                {
                    Configurations.getKnown();
                    return null;
                });
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(old);
            }

            webApp.setConfigurations(Configurations.getKnown().stream()
                .filter(Configuration::isEnabledByDefault)
                .toArray(Configuration[]::new));

            //Make a webapp classloader
            OSGiWebappClassLoader webAppLoader = new OSGiWebappClassLoader(environmentLoader, webApp, metadata.getBundle());

            //Handle Require-TldBundle
            //This is a comma separated list of names of bundles that contain tlds that this webapp uses.
            //We add them to the webapp classloader.
            String requireTldBundles = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE);

            List<Path> pathsToTldBundles = Util.getPathsToBundlesBySymbolicNames(requireTldBundles, metadata.getBundle().getBundleContext());
            for (Path p : pathsToTldBundles)
            {
                webAppLoader.addClassPath(p.toUri().toString());
            }

            //Set up configuration from manifest headers
            //extra classpath
            String extraClasspath = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.JETTY_EXTRA_CLASSPATH);
            if (extraClasspath != null)
                webApp.setExtraClasspath(extraClasspath);

            webApp.setClassLoader(webAppLoader);

            //Take care of extra provider properties
            webApp.setAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN, provider.getAttributes().getAttribute(OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN));

            //TODO needed?
            webApp.setAttribute(OSGiWebappConstants.REQUIRE_TLD_BUNDLE, requireTldBundles);

            //Set up some attributes
            // rfc66
            webApp.setAttribute(OSGiWebappConstants.RFC66_OSGI_BUNDLE_CONTEXT, metadata.getBundle().getBundleContext());

            // spring-dm-1.2.1 looks for the BundleContext as a different attribute.
            // not a spec... but if we want to support
            // org.springframework.osgi.web.context.support.OsgiBundleXmlWebApplicationContext
            // then we need to do this to:
            webApp.setAttribute("org.springframework.osgi.web." + BundleContext.class.getName(), metadata.getBundle().getBundleContext());

            // also pass the bundle directly. sometimes a bundle does not have a
            // bundlecontext.
            // it is still useful to have access to the Bundle from the servlet
            // context.
            webApp.setAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE, metadata.getBundle());

            // apply any META-INF/context.xml file that is found to configure
            // the webapp first
            //First try looking for one in /META-INF
            URI tmpUri = null;

            URL contextXmlURL = Util.getLocalizedEntry("/META-INF/jetty-webapp-context.xml", metadata.getBundle());
            if (contextXmlURL != null)
                tmpUri = contextXmlURL.toURI();

            //Then look in the property OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH and apply the first one
            if (contextXmlURL == null)
            {
                String tmp = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH);
                if (tmp != null)
                {
                    String[] filenames = tmp.split("[,;]");
                    tmpUri = Util.resolvePathAsLocalizedURI(filenames[0], metadata.getBundle(), jettyHomePath);
                }
            }

            //apply a context xml if there is one
            if (tmpUri != null)
            {
                final URI contextXmlUri = tmpUri;
                ClassLoader oldLoader = Thread.currentThread().getContextClassLoader();
                try
                {
                    Thread.currentThread().setContextClassLoader(webApp.getClassLoader());
                    WebAppClassLoader.runWithHiddenClassAccess(() ->
                    {
                        Server server = provider.getServer();
                        XmlConfiguration xmlConfiguration = new XmlConfiguration(ResourceFactory.of(webApp).newResource(contextXmlUri));
                        xmlConfiguration.setJettyStandardIdsAndProperties(server, null);
                        WebAppClassLoader.runWithHiddenClassAccess(() ->
                        {
                            Map<String, String> properties = new HashMap<>();
                            properties.put(OSGiWebappConstants.JETTY_BUNDLE_ROOT, metadata.getPath().toUri().toString());
                            properties.put(OSGiServerConstants.JETTY_HOME, (String)server.getAttribute(OSGiServerConstants.JETTY_HOME));
                            properties.put("environment", ENVIRONMENT);
                            xmlConfiguration.getProperties().putAll(properties);
                            xmlConfiguration.configure(webApp);
                            return null;
                        });
                        return null;
                    });
                }
                catch (Exception e)
                {
                    LOG.warn("Error applying context xml", e);
                    throw e;
                }
                finally
                {
                    Thread.currentThread().setContextClassLoader(oldLoader);
                }
            }

            //ensure the context path is set
            webApp.setContextPath(metadata.getContextPath());

            //osgi Enterprise Spec r4 p.427
            webApp.setAttribute(OSGiWebappConstants.OSGI_BUNDLECONTEXT, metadata.getBundle().getBundleContext());

            //Indicate the webapp has been deployed, so that we don't try and redeploy again
            webApp.setAttribute(OSGiWebappConstants.WATERMARK, OSGiWebappConstants.WATERMARK);

            //make sure we protect also the osgi dirs specified by OSGi Enterprise spec
            String[] targets = webApp.getProtectedTargets();
            String[] updatedTargets = null;
            if (targets != null)
            {
                updatedTargets = new String[targets.length + OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
                System.arraycopy(targets, 0, updatedTargets, 0, targets.length);
            }
            else
                updatedTargets = new String[OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length];
            System.arraycopy(OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS, 0, updatedTargets, targets.length, OSGiWebappConstants.DEFAULT_PROTECTED_OSGI_TARGETS.length);
            webApp.setProtectedTargets(updatedTargets);

            Resource bundleResource = Util.newBundleResource(metadata.getBundle(), resourceFactory);

            String pathToResourceBase = metadata.getPathToResourceBase();

            //if the path wasn't set or it was ., then it is the root of the bundle's installed location
            if (StringUtil.isBlank(pathToResourceBase) || ".".equals(pathToResourceBase))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("Webapp base using bundle install location: {}", bundleResource);
                webApp.setWarResource(bundleResource);
            }
            else
            {
                if (pathToResourceBase.startsWith("/") || pathToResourceBase.startsWith("file:"))
                {
                    //The baseResource is outside of the bundle
                    Path p = Paths.get(pathToResourceBase);
                    webApp.setWar(p.toUri().toString());
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using absolute location: {}", p);
                }
                else
                {
                    //The baseResource is relative to the root of the bundle
                    Resource r = bundleResource.resolve(pathToResourceBase);
                    webApp.setWarResource(r);
                    if (LOG.isDebugEnabled())
                        LOG.debug("Webapp base using path relative to bundle unpacked install location: {}", r);
                }
            }

            //web.xml
            String tmp = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.JETTY_WEB_XML_PATH);
            if (!StringUtil.isBlank(tmp))
            {
                URI webXml = Util.resolvePathAsLocalizedURI(tmp, metadata.getBundle(), jettyHomePath);
                if (webXml != null)
                    webApp.setDescriptor(webXml.toString());
            }

            // webdefault-ee.xml
            tmp = (String)metadata.getAttributes().getAttribute(OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH);
            if (tmp != null)
            {
                URI defaultWebXml = Util.resolvePathAsLocalizedURI(tmp, metadata.getBundle(), jettyHomePath);
                if (defaultWebXml != null)
                {
                    webApp.setDefaultsDescriptor(defaultWebXml.toString());
                }
            }

            return webApp;
        }
    }
}
