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

package org.eclipse.jetty.deploy.internal;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.Attributes;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.xml.XmlConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DefaultContextHandlerFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(DefaultContextHandlerFactory.class);
    public static final String CONTEXT_HANDLER_CLASS = "jetty.deploy.contextHandlerClass";
    public static final String CONTEXT_HANDLER_CLASS_DEFAULT = "jetty.deploy.default.contextHandlerClass";
    public static final String ENVIRONMENT = "jetty.deploy.environment";
    public static final String ENV_XML_PATHS = "jetty.deploy.defaultApp.envXMLs";
    public static final String ENVIRONMENT_XML = "jetty.deploy.environmentXml";

    private static Map<String, String> asProperties(Attributes attributes)
    {
        Map<String, String> props = new HashMap<>();
        attributes.getAttributeNameSet().forEach((name) ->
        {
            Object value = attributes.getAttribute(name);
            String key = name.startsWith(Deployable.ATTRIBUTE_PREFIX)
                ? name.substring(Deployable.ATTRIBUTE_PREFIX.length())
                : name;
            props.put(key, Objects.toString(value));
        });
        return props;
    }

    public static List<Path> getEnvironmentXmlPaths(Attributes attributes)
    {
        //noinspection unchecked
        return (List<Path>)attributes.getAttribute(ENV_XML_PATHS);
    }

    public static void setEnvironmentXmlPaths(Attributes attributes, List<Path> paths)
    {
        attributes.setAttribute(ENV_XML_PATHS, paths);
    }

    /**
     * TODO: DOCUMENT THIS
     *
     * @param server the server reference (used by XML configurations)
     * @param app the tracked app
     * @param deployAttributes the attributes to use for creation of this ContextHandler
     * @return the ContextHandler
     * @throws Exception if unable to create the ContextHandler
     */
    public ContextHandler newContextHandler(Server server, TrackedPaths app, Attributes deployAttributes) throws Exception
    {
        Path mainPath = app.getMainPath();
        if (mainPath == null)
        {
            throw new IllegalStateException("Unable to create ContextHandler for app with no main path defined: " + app);
        }

        // Resolve real file (hopefully eliminating alias issues)
        mainPath = mainPath.toRealPath();

        // Can happen if the file existed when notified by scanner (as either an ADD or CHANGE),
        // and then the file was deleted before reaching this code.
        if (!Files.exists(mainPath))
            throw new IllegalStateException("App path does not exist " + mainPath);

        deployAttributes.setAttribute(Deployable.MAIN_PATH, mainPath);
        deployAttributes.setAttribute(Deployable.OTHER_PATHS, app.getPaths().keySet());

        Environment environment = app.getEnvironment();
        if (environment == null)
        {
            String error = String.format("Environment not declared for app [%s].  The available environments are: %s",
                app,
                Environment.getAll().stream()
                    .map(Environment::getName)
                    .collect(Collectors.joining(", ", "[", "]"))
            );
            throw new IllegalStateException(error);
        }

        if (LOG.isDebugEnabled())
            LOG.debug("createContextHandler {} in {}", app, environment.getName());

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader(environment.getClassLoader());

            /*
             * The process now is to figure out the context object to use.
             * This can come from a number of places.
             * 1. If an XML deployable, this is the <Configure class="contextClass"> entry.
             * 2. If another deployable (like a web archive, or directory), then check attributes.
             *    a. use the app attributes to figure out the context handler class.
             *    b. use the environment attributes default context handler class.
             */
            Object context = newContextInstance(server, environment, app, deployAttributes, mainPath);
            if (context == null)
                throw new IllegalStateException("unable to create ContextHandler for " + app);

            if (LOG.isDebugEnabled())
                LOG.debug("Context {} created from app {}", context.getClass().getName(), app);

            // Apply environment properties and XML to context
            if (applyEnvironmentXml(server, context, environment, deployAttributes))
            {
                // If an XML deployable, apply full XML over environment XML changes
                if (FileID.isXml(mainPath))
                    context = applyXml(server, context, mainPath, environment, deployAttributes);
            }

            // Get the ContextHandler out from the constructed context.
            ContextHandler contextHandler = getContextHandler(context);

            // Copy non-deploy attributes into ContextHandler attributes for context use
            deployAttributes.getAttributeNameSet().stream()
                .filter(k -> !k.startsWith("jetty.deploy."))
                .forEach(k -> contextHandler.setAttribute(k, deployAttributes.getAttribute(k)));

            return contextHandler;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    protected Object applyXml(Server server, Object context, Path xml, Environment environment, Attributes attributes) throws Exception
    {
        if (!FileID.isXml(xml))
            return null;

        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            XmlConfiguration xmlConfiguration = new XmlConfiguration(resourceFactory.newResource(xml), null, asProperties(attributes))
            {
                @Override
                public void initializeDefaults(Object context)
                {
                    super.initializeDefaults(context);
                    ContextHandler contextHandler = getContextHandler(context);
                    if (contextHandler == null)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Not a ContextHandler: Not initializing Context {}", context);
                    }
                    else
                    {
                        DefaultContextHandlerFactory.this.initializeContextPath(contextHandler, xml);
                        DefaultContextHandlerFactory.this.initializeContextHandler(contextHandler, xml, attributes);
                    }
                }
            };

            xmlConfiguration.getIdMap().put("Environment", environment.getName());
            xmlConfiguration.setJettyStandardIdsAndProperties(server, xml.getParent());

            // Put all Environment attributes into XmlConfiguration as properties that can be used.
            attributes.getAttributeNameSet()
                .stream()
                .filter(k -> !k.startsWith("jetty.home") &&
                    !k.startsWith("jetty.base") &&
                    !k.startsWith("jetty.webapps"))
                .forEach(k ->
                {
                    Object v = attributes.getAttribute(k);
                    if (v == null)
                        xmlConfiguration.getProperties().remove(k);
                    else
                        xmlConfiguration.getProperties().put(k, Objects.toString(v));
                });

            // Run configure against appropriate classloader.
            ClassLoader xmlClassLoader = getClassLoader(context, environment);
            ClassLoader oldClassLoader = Thread.currentThread().getContextClassLoader();
            Thread.currentThread().setContextClassLoader(xmlClassLoader);

            try
            {
                // Create or configure the context
                if (context == null)
                    return xmlConfiguration.configure();

                return xmlConfiguration.configure(context);
            }
            finally
            {
                Thread.currentThread().setContextClassLoader(oldClassLoader);
            }
        }
    }

    private ClassLoader getClassLoader(Object context, Environment environment)
    {
        ContextHandler contextHandler = getContextHandler(context);
        if (context != null)
        {
            ClassLoader classLoader = contextHandler.getClassLoader();
            if (classLoader != null)
                return classLoader;
        }

        return environment.getClassLoader();
    }

    protected void initializeContextHandler(ContextHandler contextHandler, Path path, Attributes attributes)
    {
        if (LOG.isDebugEnabled())
            LOG.debug("initializeContextHandler {}", contextHandler);

        assert contextHandler != null;

        if (contextHandler.getBaseResource() == null)
        {
            if (Files.isDirectory(path))
            {
                ResourceFactory resourceFactory = ResourceFactory.of(contextHandler);
                contextHandler.setBaseResource(resourceFactory.newResource(path));
            }
        }

        // pass through properties as attributes directly
        attributes.getAttributeNameSet().stream()
            .filter((name) -> name.startsWith(Deployable.ATTRIBUTE_PREFIX))
            .forEach((name) ->
            {
                Object value = attributes.getAttribute(name);
                String key = name.substring(Deployable.ATTRIBUTE_PREFIX.length());
                if (LOG.isDebugEnabled())
                    LOG.debug("Setting attribute [{}] to [{}] in context {}", key, value, contextHandler);
                contextHandler.setAttribute(key, value);
            });

        String contextPath = (String)attributes.getAttribute(Deployable.CONTEXT_PATH);
        if (StringUtil.isNotBlank(contextPath))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Context {} initialized with contextPath: {}", contextHandler, contextPath);
            contextHandler.setContextPath(contextPath);
        }
    }

    protected void initializeContextPath(ContextHandler contextHandler, Path path)
    {
        if (contextHandler == null)
            return;

        // Strip any 3 char extension from non directories
        String basename = FileID.getBasename(path);
        String contextPath = basename;

        // special case of archive (or dir) named "root" is / context
        if (contextPath.equalsIgnoreCase("root"))
        {
            contextPath = "/";
        }
        // handle root with virtual host form
        else if (StringUtil.asciiStartsWithIgnoreCase(contextPath, "root-"))
        {
            int dash = contextPath.indexOf('-');
            String virtual = contextPath.substring(dash + 1);
            contextHandler.setVirtualHosts(Arrays.asList(virtual.split(",")));
            contextPath = "/";
        }

        // Ensure "/" is Prepended to all context paths.
        if (contextPath.charAt(0) != '/')
            contextPath = "/" + contextPath;

        if (LOG.isDebugEnabled())
            LOG.debug("ContextHandler {} initialized with displayName: {}", contextHandler, basename);
        contextHandler.setDisplayName(basename);
        if (LOG.isDebugEnabled())
            LOG.debug("ContextHandler {} initialized with contextPath: {}", contextHandler, contextPath);
        contextHandler.setContextPath(contextPath);
    }

    /**
     * Apply optional environment specific XML to context.
     *
     * @param server the Server instance for referencing in XML
     * @param context the context to apply environment specific behavior to
     * @param environment the environment to use
     * @param attributes the attributes used to deploy the app
     * @return true if environment specific XML was applied.
     * @throws Exception if unable to apply environment configuration.
     */
    private boolean applyEnvironmentXml(Server server, Object context, Environment environment, Attributes attributes) throws Exception
    {
        // Collect the optional environment context xml files.
        // Order them according to the name of their property key names.
        List<Path> sortedEnvXmlPaths = getEnvironmentXmlPaths(attributes);

        if (sortedEnvXmlPaths == null || sortedEnvXmlPaths.isEmpty())
            // nothing to do here
            return false;

        // apply each context environment xml file
        for (Path envXmlPath : sortedEnvXmlPaths)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Applying environment specific context file {}", envXmlPath);
            context = applyXml(server, context, envXmlPath, environment, attributes);
        }
        return true;
    }

    /**
     * Find the {@link ContextHandler} for the provided {@link Object}
     *
     * @param context the raw context object
     * @return the {@link ContextHandler} for the context, or null if no ContextHandler associated with context.
     */
    private ContextHandler getContextHandler(Object context)
    {
        if (context == null)
            return null;

        if (context instanceof ContextHandler handler)
            return handler;

        if (Supplier.class.isAssignableFrom(context.getClass()))
        {
            @SuppressWarnings("unchecked")
            Supplier<ContextHandler> provider = (Supplier<ContextHandler>)context;
            return provider.get();
        }

        if (LOG.isDebugEnabled())
            LOG.debug("Not a context {}", context);
        return null;
    }

    /**
     * Initialize a new Context object instance.
     *
     * <p>
     * The search order is:
     * </p>
     * <ol>
     * <li>If app attribute {@link #CONTEXT_HANDLER_CLASS} is specified, use it, and initialize context</li>
     * <li>If App deployable path is XML, apply XML {@code <Configuration>}</li>
     * <li>Fallback to environment attribute {@link #CONTEXT_HANDLER_CLASS_DEFAULT}, and initialize context.</li>
     * </ol>
     *
     * @param environment the environment context applies to
     * @param app the App for the context
     * @param attributes the Attributes used to deploy the App
     * @param path the path of the deployable
     * @return the Context Object.
     * @throws Exception if unable to create Object instance.
     */
    private Object newContextInstance(Server server, Environment environment, TrackedPaths app, Attributes attributes, Path path) throws Exception
    {
        if (LOG.isDebugEnabled())
            LOG.debug("newContextInstance({}, {}, {}, {})", server, environment, app, path);

        Object context = newInstance((String)attributes.getAttribute(CONTEXT_HANDLER_CLASS));
        if (context != null)
        {
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);

            initializeContextPath(contextHandler, path);
            initializeContextHandler(contextHandler, path, attributes);
        }

        // Allow context created from CONTEXT_HANDLER_CLASS to be initialized
        // before the XML executes, and possibly references content that only
        // the context will know about (such as from a classloader)
        initializeDeployable(context, attributes);

        if (FileID.isXml(path))
        {
            // track if context is created from XML or an existing one is just being configured by XML
            boolean createdContext = (context == null);
            context = applyXml(server, context, path, environment, attributes);
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);

            if (createdContext)
                initializeDeployable(context, attributes);
            return context;
        }

        if (context != null)
            return context;

        // fallback to default from environment.
        context = newInstance((String)environment.getAttribute(CONTEXT_HANDLER_CLASS_DEFAULT));
        if (context != null)
        {
            ContextHandler contextHandler = getContextHandler(context);
            if (contextHandler == null)
                throw new IllegalStateException("Unknown context type of " + context);

            initializeContextPath(contextHandler, path);
            initializeContextHandler(contextHandler, path, attributes);
            initializeDeployable(context, attributes);
            return context;
        }

        return null;
    }

    private void initializeDeployable(Object context, Attributes attributes)
    {
        // Ensure that WAR fallback String (that WebInfConfiguration needs) is
        // only created once.
        if (attributes.getAttribute(Deployable.WAR) == null)
        {
            Path mainPath = (Path)attributes.getAttribute(Deployable.MAIN_PATH);
            if (FileID.isWebArchive(mainPath))
            {
                // Set a backup value for the path to the war in case it hasn't already been set
                // via a different means.  This is especially important for a deployable App
                // that is only a <name>.war file (no XML).  The eventual WebInfConfiguration
                // will use this attribute.
                attributes.setAttribute(Deployable.WAR, mainPath.toString());
            }
        }

        if (context instanceof Deployable deployable)
            deployable.initializeDefaults(attributes);
    }

    private Object newInstance(String className) throws Exception
    {
        if (StringUtil.isBlank(className))
            return null;
        if (LOG.isDebugEnabled())
            LOG.debug("Attempting to load class {}", className);
        Class<?> clazz = Loader.loadClass(className);
        if (clazz == null)
            return null;
        return clazz.getConstructor().newInstance();
    }
}
