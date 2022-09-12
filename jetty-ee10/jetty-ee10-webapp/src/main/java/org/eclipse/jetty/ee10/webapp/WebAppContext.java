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

package org.eclipse.jetty.ee10.webapp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRegistration.Dynamic;
import jakarta.servlet.ServletSecurityElement;
import jakarta.servlet.http.HttpSessionActivationListener;
import jakarta.servlet.http.HttpSessionAttributeListener;
import jakarta.servlet.http.HttpSessionBindingListener;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;
import org.eclipse.jetty.ee.Deployable;
import org.eclipse.jetty.ee10.servlet.ErrorHandler;
import org.eclipse.jetty.ee10.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee10.servlet.ServletContextHandler;
import org.eclipse.jetty.ee10.servlet.ServletHandler;
import org.eclipse.jetty.ee10.servlet.SessionHandler;
import org.eclipse.jetty.ee10.servlet.security.ConstraintAware;
import org.eclipse.jetty.ee10.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee10.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.ee10.servlet.security.SecurityHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.FileID;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ClassLoaderDump;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web Application Context Handler.
 * <p>
 * The WebAppContext handler is an extension of ContextHandler that
 * coordinates the construction and configuration of nested handlers:
 * {@link ConstraintSecurityHandler}, {@link org.eclipse.jetty.ee10.servlet.SessionHandler}
 * and {@link ServletHandler}.
 * The handlers are configured by pluggable configuration classes, with
 * the default being  {@link WebXmlConfiguration} and
 * {@link JettyWebXmlConfiguration}.
 *
 */
@ManagedObject("Web Application ContextHandler")
public class WebAppContext extends ServletContextHandler implements WebAppClassLoader.Context, Deployable
{
    static final Logger LOG = LoggerFactory.getLogger(WebAppContext.class);

    public static final String TEMPDIR = ServletContext.TEMPDIR;
    public static final String BASETEMPDIR = Server.BASE_TEMP_DIR_ATTR;
    public static final String WEB_DEFAULTS_XML = "org/eclipse/jetty/ee10/webapp/webdefault-ee10.xml";
    public static final String ERROR_PAGE = "org.eclipse.jetty.server.error_page";
    public static final String SERVER_SYS_CLASSES = "org.eclipse.jetty.webapp.systemClasses";
    public static final String SERVER_SRV_CLASSES = "org.eclipse.jetty.webapp.serverClasses";

    private static String[] __dftProtectedTargets = {"/WEB-INF", "/META-INF"};

    // System classes are classes that cannot be replaced by
    // the web application, and they are *always* loaded via
    // system classloader.
    public static final ClassMatcher __dftSystemClasses = new ClassMatcher(
        "java.",                            // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "javax.",                           // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "jakarta.",                         // Jakarta classes (per servlet spec v5.0 / Section 15.2.1)
        "org.xml.",                         // javax.xml
        "org.w3c."                          // javax.xml
    );

    // Server classes are classes that are hidden from being
    // loaded by the web application using system classloader,
    // so if web application needs to load any of such classes,
    // it has to include them in its distribution.
    public static final ClassMatcher __dftServerClasses = new ClassMatcher(
        "org.eclipse.jetty."                // hide jetty classes
    );

    private final ClassMatcher _systemClasses = new ClassMatcher(__dftSystemClasses);
    private final ClassMatcher _serverClasses = new ClassMatcher(__dftServerClasses);

    private Configurations _configurations;
    private String _defaultsDescriptor = WEB_DEFAULTS_XML;
    private String _descriptor = null;
    private final List<String> _overrideDescriptors = new ArrayList<>();
    private boolean _distributable = false;
    private boolean _extractWAR = true;
    private boolean _copyDir = false;
    private boolean _copyWebInf = false;
    private boolean _logUrlOnStart = false;
    private boolean _parentLoaderPriority = Boolean.getBoolean("org.eclipse.jetty.server.webapp.parentLoaderPriority");
    private PermissionCollection _permissions;
    private boolean _defaultContextPath = true;

    private String[] _contextWhiteList = null;

    private File _tmpDir;
    private boolean _persistTmpDir = false;

    private String _war;
    private ResourceCollection _extraClasspath;
    private Throwable _unavailableException;

    private Map<String, String> _resourceAliases;
    private boolean _ownClassLoader = false;
    private boolean _configurationDiscovered = true;
    private boolean _allowDuplicateFragmentNames = false;
    private boolean _throwUnavailableOnStartupException = false;

    private MetaData _metadata = new MetaData();

    public static WebAppContext getCurrentWebAppContext()
    {
        ServletContextHandler handler = ServletContextHandler.getCurrentServletContextHandler();
        if (handler != null)
        {
            if (handler instanceof WebAppContext)
                return (WebAppContext)handler;
        }
        return null;
    }

    public WebAppContext()
    {
        this(null, null, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
    }

    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(String webApp, String contextPath)
    {
        this(null, contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
        setWar(webApp);
    }

    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(Resource webApp, String contextPath)
    {
        this(null, contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
        setWarResource(webApp);
    }

    /**
     * @param parent The parent container.
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(Container parent, String webApp, String contextPath)
    {
        this(parent, contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
        setWar(webApp);
    }

    /**
     * @param parent The parent container.
     * @param contextPath The context path
     * @param webApp The webapp directory or war file.
     */
    public WebAppContext(Container parent, Resource webApp, String contextPath)
    {
        this(parent, contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
        setWarResource(webApp);
    }

    /**
     * @param sessionHandler SessionHandler for this web app
     * @param securityHandler SecurityHandler for this web app
     * @param servletHandler ServletHandler for this web app
     * @param errorHandler ErrorHandler for this web app
     */
    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        this(null, null, sessionHandler, securityHandler, servletHandler, errorHandler, 0);
    }

    /**
     * @param parent the parent container
     * @param contextPath the context path
     * @param sessionHandler SessionHandler for this web app
     * @param securityHandler SecurityHandler for this web app
     * @param servletHandler ServletHandler for this web app
     * @param errorHandler ErrorHandler for this web app
     * @param options the options ({@link ServletContextHandler#SESSIONS} and/or {@link ServletContextHandler#SECURITY})
     */
    public WebAppContext(Container parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        // always pass parent as null and then set below, so that any resulting setServer call
        // is done after this instance is constructed.
        super(null, contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
        setErrorProcessor(errorHandler != null ? errorHandler : new ErrorPageErrorHandler());
        setProtectedTargets(__dftProtectedTargets);
        if (parent != null)
            setParent(parent);
    }

    @Override
    public void initializeDefaults(Map<String, String> properties)
    {
        for (String property : properties.keySet())
        {
            String value = properties.get(property);
            if (LOG.isDebugEnabled())
                LOG.debug("init {}: {}", property, value);

            switch (property)
            {
                case Deployable.WAR -> setWar(value);
                case Deployable.BASE_TEMP_DIR -> setAttribute(BASETEMPDIR, value);
                case Deployable.CONFIGURATION_CLASSES -> setConfigurationClasses(value == null ? null : value.split(","));
                case Deployable.CONTAINER_SCAN_JARS -> setAttribute(MetaInfConfiguration.CONTAINER_JAR_PATTERN, value);
                case Deployable.EXTRACT_WARS -> setExtractWAR(Boolean.parseBoolean(value));
                case Deployable.PARENT_LOADER_PRIORITY -> setParentLoaderPriority(Boolean.parseBoolean(value));
                case Deployable.WEBINF_SCAN_JARS -> setAttribute(MetaInfConfiguration.WEBINF_JAR_PATTERN, value);
                case Deployable.DEFAULTS_DESCRIPTOR -> setDefaultsDescriptor(value);
                case Deployable.SCI_EXCLUSION_PATTERN -> setAttribute("org.eclipse.jetty.containerInitializerExclusionPattern", value);
                case Deployable.SCI_ORDER -> setAttribute("org.eclipse.jetty.containerInitializerOrder", value);
                default ->
                {
                    if (LOG.isDebugEnabled() && StringUtil.isNotBlank(value))
                        LOG.debug("unknown property {}={}", property, value);
                }
            }
        }
        _defaultContextPath = true;
    }

    public boolean isContextPathDefault()
    {
        return _defaultContextPath;
    }

    @Override
    public void setContextPath(String contextPath)
    {
        super.setContextPath(contextPath);
        _defaultContextPath = false;
    }

    public void setDefaultContextPath(String contextPath)
    {
        super.setContextPath(contextPath);
        _defaultContextPath = true;
    }

    /**
     * @param servletContextName The servletContextName to set.
     */
    @Override
    public void setDisplayName(String servletContextName)
    {
        super.setDisplayName(servletContextName);
        ClassLoader cl = getClassLoader();
        if (cl instanceof WebAppClassLoader && servletContextName != null)
            ((WebAppClassLoader)cl).setName(servletContextName);
    }

    /**
     * Get an exception that caused the webapp to be unavailable
     *
     * @return A throwable if the webapp is unavailable or null
     */
    public Throwable getUnavailableException()
    {
        return _unavailableException;
    }

    /**
     * Set Resource Alias.
     * Resource aliases map resource uri's within a context.
     * They may optionally be used by a handler when looking for
     * a resource.
     *
     * @param alias the alias for a resource
     * @param uri the uri for the resource
     */
    public void setResourceAlias(String alias, String uri)
    {
        if (_resourceAliases == null)
            _resourceAliases = new HashMap<>(5);
        _resourceAliases.put(alias, uri);
    }

    public Map<String, String> getResourceAliases()
    {
        if (_resourceAliases == null)
            return null;
        return _resourceAliases;
    }

    public void setResourceAliases(Map<String, String> map)
    {
        _resourceAliases = map;
    }

    public String getResourceAlias(String path)
    {
        if (_resourceAliases == null)
            return null;
        String alias = _resourceAliases.get(path);

        int slash = path.length();
        while (alias == null)
        {
            slash = path.lastIndexOf("/", slash - 1);
            if (slash < 0)
                break;
            String match = _resourceAliases.get(path.substring(0, slash + 1));
            if (match != null)
                alias = match + path.substring(slash + 1);
        }
        return alias;
    }

    public String removeResourceAlias(String alias)
    {
        if (_resourceAliases == null)
            return null;
        return _resourceAliases.remove(alias);
    }

    @Override
    public void setClassLoader(ClassLoader classLoader)
    {
        super.setClassLoader(classLoader);

        String name = getDisplayName();
        if (name == null)
            name = getContextPath();

        if (classLoader instanceof WebAppClassLoader && getDisplayName() != null)
            ((WebAppClassLoader)classLoader).setName(name);
    }

    public ResourceFactory getResourceFactory()
    {
        return ResourceFactory.of(this);
    }

    @Override
    public Resource getResource(String pathInContext) throws MalformedURLException
    {
        if (pathInContext == null || !pathInContext.startsWith(URIUtil.SLASH))
            throw new MalformedURLException(pathInContext);

        MalformedURLException mue = null;
        Resource resource = null;
        int loop = 0;
        while (pathInContext != null && loop++ < 100)
        {
            try
            {
                resource = super.getResource(pathInContext);
                if (resource != null && resource.exists())
                    return resource;

                pathInContext = getResourceAlias(pathInContext);
            }
            catch (MalformedURLException e)
            {
                LOG.trace("IGNORED", e);
                if (mue == null)
                    mue = e;
            }
        }

        if (mue != null)
            throw mue;

        return resource;
    }

    /**
     * Is the context Automatically configured.
     *
     * @return true if configuration discovery.
     */
    public boolean isConfigurationDiscovered()
    {
        return _configurationDiscovered;
    }

    /**
     * Set the configuration discovery mode.
     * If configuration discovery is set to true, then the JSR315
     * servlet 3.0 discovered configuration features are enabled.
     * These are:<ul>
     * <li>Web Fragments</li>
     * <li>META-INF/resource directories</li>
     * </ul>
     *
     * @param discovered true if configuration discovery is enabled for automatic configuration from the context
     */
    public void setConfigurationDiscovered(boolean discovered)
    {
        _configurationDiscovered = discovered;
    }

    /**
     * Pre configure the web application.
     * <p>
     * The method is normally called from {@link #start()}. It performs
     * the discovery of the configurations to be applied to this context,
     * specifically:
     * <ul>
     * <li>Instantiate the {@link Configuration} instances with a call to {@link #loadConfigurations()}.
     * <li>Instantiates a classloader (if one is not already set)
     * <li>Calls the {@link Configuration#preConfigure(WebAppContext)} method of all
     * Configuration instances.
     * </ul>
     *
     * @throws Exception if unable to pre configure
     */
    public void preConfigure() throws Exception
    {
        // Add the known server class inclusions for all known configurations
        for (Configuration configuration : Configurations.getKnown())
        {
            _serverClasses.include(configuration.getServerClasses().getInclusions());
        }

        // Setup Configuration classes for this webapp!
        loadConfigurations();
        _configurations.sort();
        for (Configuration configuration : _configurations)
        {
            _systemClasses.add(configuration.getSystemClasses().getPatterns());
            _serverClasses.exclude(configuration.getServerClasses().getExclusions());
        }

        // Configure classloader
        _ownClassLoader = false;
        if (getClassLoader() == null)
        {
            WebAppClassLoader classLoader = new WebAppClassLoader(this);
            setClassLoader(classLoader);
            _ownClassLoader = true;
        }

        if (LOG.isDebugEnabled())
        {
            ClassLoader loader = getClassLoader();
            LOG.debug("Thread Context classloader {}", loader);
            loader = loader.getParent();
            while (loader != null)
            {
                LOG.debug("Parent class loader: {} ", loader);
                loader = loader.getParent();
            }
        }

        _configurations.preConfigure(this);
    }

    public boolean configure() throws Exception
    {
        return _configurations.configure(this);
    }

    public void postConfigure() throws Exception
    {
        _configurations.postConfigure(this);
    }

    @Override
    protected void doStart() throws Exception
    {
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(__environment.getClassLoader());
        try
        {
            _metadata.setAllowDuplicateFragmentNames(isAllowDuplicateFragmentNames());
            Boolean validate = (Boolean)getAttribute(MetaData.VALIDATE_XML);
            _metadata.setValidateXml((validate != null && validate));
            wrapConfigurations();
            preConfigure();
            super.doStart();
            postConfigure();

            if (isLogUrlOnStart())
                dumpUrl();
        }
        catch (Throwable t)
        {
            // start up of the webapp context failed, make sure it is not started
            LOG.warn("Failed startup of context {}", this, t);
            _unavailableException = t;
            setAvailable(false); // webapp cannot be accessed (results in status code 503)
            if (isThrowUnavailableOnStartupException())
                throw t;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    private void wrapConfigurations()
    {
        java.util.Collection<Configuration.WrapperFunction> wrappers = getBeans(Configuration.WrapperFunction.class);
        if (wrappers == null || wrappers.isEmpty())
            return;

        List<Configuration> configs = new ArrayList<>(_configurations.getConfigurations());
        _configurations.clear();

        for (Configuration config : configs)
        {
            Configuration wrapped = config;
            for (Configuration.WrapperFunction wrapperFunction : getBeans(Configuration.WrapperFunction.class))
            {
                wrapped = wrapperFunction.wrapConfiguration(wrapped);
            }
            _configurations.add(wrapped);
        }
    }

    @Override
    public void destroy()
    {
        // Prepare for configuration
        Throwable multiException = null;
        if (_configurations != null)
        {
            for (Configuration configuration : _configurations)
            {
                try
                {
                    configuration.destroy(this);
                }
                catch (Exception e)
                {
                    multiException = ExceptionUtil.combine(multiException, e);
                }
            }
        }
        _configurations = null;
        super.destroy();
        ExceptionUtil.ifExceptionThrowRuntime(multiException);
    }

    /*
     * Dumps the current web app name and URL to the log
     */
    private void dumpUrl()
    {
        Connector[] connectors = getServer().getConnectors();
        for (int i = 0; i < connectors.length; i++)
        {
            String displayName = getDisplayName();
            if (displayName == null)
                displayName = "WebApp@" + Arrays.hashCode(connectors);

            LOG.info("{} at http://{}{}", displayName, connectors[i].toString(), getContextPath());
        }
    }

    /**
     * @return Returns the configurations.
     */
    @ManagedAttribute(value = "configuration classes used to configure webapp", readonly = true)
    public String[] getConfigurationClasses()
    {
        loadConfigurations();
        return _configurations.toArray();
    }

    /**
     * @return Returns the configurations.
     */
    public Configurations getConfigurations()
    {
        loadConfigurations();
        return _configurations;
    }

    /**
     * The default descriptor is a web.xml format file that is applied to the context before the standard WEB-INF/web.xml
     *
     * @return Returns the defaultsDescriptor.
     */
    @ManagedAttribute(value = "default web.xml deascriptor applied before standard web.xml", readonly = true)
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     *
     * @return Returns the Override Descriptor.
     */
    public String getOverrideDescriptor()
    {
        if (_overrideDescriptors.size() != 1)
            return null;
        return _overrideDescriptors.get(0);
    }

    /**
     * An override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     *
     * @return Returns the Override Descriptor list
     */
    @ManagedAttribute(value = "web.xml deascriptors applied after standard web.xml", readonly = true)
    public List<String> getOverrideDescriptors()
    {
        return Collections.unmodifiableList(_overrideDescriptors);
    }

    /**
     * @return Returns the permissions.
     */
    @Override
    public PermissionCollection getPermissions()
    {
        return _permissions;
    }

    /**
     * Set the server classes patterns.
     * <p>
     * Server classes/packages are classes used to implement the server and are hidden
     * from the context.  If the context needs to load these classes, it must have its
     * own copy of them in WEB-INF/lib or WEB-INF/classes.
     *
     * @param serverClasses the server classes pattern
     */
    public void setServerClassMatcher(ClassMatcher serverClasses)
    {
        _serverClasses.clear();
        _serverClasses.add(serverClasses.getPatterns());
    }

    /**
     * Set the system classes patterns.
     * <p>
     * System classes/packages are classes provided by the JVM and that
     * cannot be replaced by classes of the same name from WEB-INF,
     * regardless of the value of {@link #setParentLoaderPriority(boolean)}.
     *
     * @param systemClasses the system classes pattern
     */
    public void setSystemClassMatcher(ClassMatcher systemClasses)
    {
        _systemClasses.clear();
        _systemClasses.add(systemClasses.getPatterns());
    }

    /**
     * Add a ClassMatcher for server classes by combining with
     * any existing matcher.
     *
     * @param serverClasses The class matcher of patterns to add to the server ClassMatcher
     */
    public void addServerClassMatcher(ClassMatcher serverClasses)
    {
        _serverClasses.add(serverClasses.getPatterns());
    }

    /**
     * Add a ClassMatcher for system classes by combining with
     * any existing matcher.
     *
     * @param systemClasses The class matcher of patterns to add to the system ClassMatcher
     */
    public void addSystemClassMatcher(ClassMatcher systemClasses)
    {
        _systemClasses.add(systemClasses.getPatterns());
    }

    /**
     * @return The ClassMatcher used to match System (protected) classes
     */
    public ClassMatcher getSystemClassMatcher()
    {
        return _systemClasses;
    }

    /**
     * @return The ClassMatcher used to match Server (hidden) classes
     */
    public ClassMatcher getServerClassMatcher()
    {
        return _serverClasses;
    }

    @ManagedAttribute(value = "classes and packages protected by context classloader", readonly = true)
    public String[] getSystemClasses()
    {
        return _systemClasses.getPatterns();
    }

    @ManagedAttribute(value = "classes and packages hidden by the context classloader", readonly = true)
    public String[] getServerClasses()
    {
        return _serverClasses.getPatterns();
    }

    @Override
    public boolean isServerClass(Class<?> clazz)
    {
        return _serverClasses.match(clazz);
    }

    @Override
    public boolean isSystemClass(Class<?> clazz)
    {
        return _systemClasses.match(clazz);
    }

    @Override
    public boolean isServerResource(String name, URL url)
    {
        return _serverClasses.match(name, url);
    }

    @Override
    public boolean isSystemResource(String name, URL url)
    {
        return _systemClasses.match(name, url);
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (server != null)
        {
            if (__dftSystemClasses.equals(_systemClasses))
            {
                Object systemClasses = server.getAttribute(SERVER_SYS_CLASSES);
                if (systemClasses instanceof String[])
                    systemClasses = new ClassMatcher((String[])systemClasses);
                if (systemClasses instanceof ClassMatcher)
                    _systemClasses.add(((ClassMatcher)systemClasses).getPatterns());
            }

            if (__dftServerClasses.equals(_serverClasses))
            {
                Object serverClasses = server.getAttribute(SERVER_SRV_CLASSES);
                if (serverClasses instanceof String[])
                    serverClasses = new ClassMatcher((String[])serverClasses);
                if (serverClasses instanceof ClassMatcher)
                    _serverClasses.add(((ClassMatcher)serverClasses).getPatterns());
            }
        }
    }

    /**
     * @return Returns the war as a file or URL string (Resource).
     * The war may be different to the @link {@link #getBaseResource()}
     * if the war has been expanded and/or copied.
     */
    @ManagedAttribute(value = "war file location", readonly = true)
    public String getWar()
    {
        if (_war == null)
        {
            if (getBaseResource() != null)
            {
                Path warPath = getBaseResource().getPath();
                if (warPath != null)
                    _war = warPath.toUri().toASCIIString();
            }
        }
        return _war;
    }

    public Resource getWebInf() throws IOException
    {
        if (getBaseResource() == null)
            return null;

        // Is there a WEB-INF directory anywhere in the Resource Base?
        // ResourceBase could be a ResourceCollection
        // The result could be a ResourceCollection with multiple WEB-INF directories
        // Can return from WEB-INF/lib/foo.jar!/WEB-INF
        // Can also never return from a META-INF/versions/#/WEB-INF location
        Resource webInf = getBaseResource().resolve("WEB-INF/");
        if (!webInf.exists() || !webInf.isDirectory())
            return null;

        return webInf;
    }

    /**
     * @return Returns the distributable.
     */
    @ManagedAttribute("web application distributable")
    public boolean isDistributable()
    {
        return _distributable;
    }

    /**
     * @return Returns the extractWAR.
     */
    @ManagedAttribute(value = "extract war", readonly = true)
    public boolean isExtractWAR()
    {
        return _extractWAR;
    }

    /**
     * @return True if the webdir is copied (to allow hot replacement of jars on windows)
     */
    @ManagedAttribute(value = "webdir copied on deploy (allows hot replacement on windows)", readonly = true)
    public boolean isCopyWebDir()
    {
        return _copyDir;
    }

    /**
     * @return True if the web-inf lib and classes directories are copied (to allow hot replacement of jars on windows)
     */
    public boolean isCopyWebInf()
    {
        return _copyWebInf;
    }

    /**
     * @return True if the classloader should delegate first to the parent
     * classloader (standard java behaviour) or false if the classloader
     * should first try to load from WEB-INF/lib or WEB-INF/classes (servlet
     * spec recommendation). Default is false or can be set by the system
     * property org.eclipse.jetty.server.webapp.parentLoaderPriority
     */
    @Override
    @ManagedAttribute(value = "parent classloader given priority", readonly = true)
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    protected void loadConfigurations()
    {
        //if the configuration instances have been set explicitly, use them
        if (_configurations != null)
            return;
        if (isStarted())
            throw new IllegalStateException();
        _configurations = newConfigurations();
    }

    protected Configurations newConfigurations()
    {
        Configurations configurations = new Configurations();
        configurations.add(Configurations.getServerDefault(getServer()).toArray());
        return configurations;
    }
    
    @Override
    public ServletContextApi newServletContextApi()
    {
        return new WebAppContext.ServletApiContext();
    }

    @Override
    public String toString()
    {
        if (_war != null)
            return super.toString() + "{" + _war + "}";
        return super.toString();
    }

    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<String> systemClasses = null;
        if (_systemClasses != null)
        {
            systemClasses = new ArrayList<>(_systemClasses);
            Collections.sort(systemClasses);
        }

        List<String> serverClasses = null;
        if (_serverClasses != null)
        {
            serverClasses = new ArrayList<>(_serverClasses);
            Collections.sort(serverClasses);
        }

        String name = getDisplayName();
        if (name == null)
        {
            if (_war != null)
            {
                int webapps = _war.indexOf("/webapps/");
                if (webapps >= 0)
                    name = _war.substring(webapps + 8);
                else
                    name = _war;
            }
            else if (getBaseResource() != null)
            {
                name = getBaseResource().getURI().toASCIIString();
                int webapps = name.indexOf("/webapps/");
                if (webapps >= 0)
                    name = name.substring(webapps + 8);
            }
            else
            {
                name = this.getClass().getSimpleName();
            }
        }

        name = String.format("%s@%x", name, hashCode());

        dumpObjects(out, indent,
            new ClassLoaderDump(getClassLoader()),
            new DumpableCollection("Systemclasses " + name, systemClasses),
            new DumpableCollection("Serverclasses " + name, serverClasses),
            new DumpableCollection("Configurations " + name, _configurations),
            new DumpableCollection("Handler attributes " + name, asAttributeMap().entrySet()),
            new DumpableCollection("Context attributes " + name, getContext().asAttributeMap().entrySet()),
            new DumpableCollection("EventListeners " + this, getEventListeners()),
            new DumpableCollection("Initparams " + name, getInitParams().entrySet())
        );
    }

    /**
     * @param configurations The configuration class names.  If setConfigurations is not called
     * these classes are used to create a configurations array.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        if (_configurations == null)
            _configurations = new Configurations();
        _configurations.set(configurations);
    }

    public void setConfigurationClasses(List<String> configurations)
    {
        setConfigurationClasses(configurations.toArray(new String[0]));
    }

    /**
     * @param configurations The configurations to set.
     */
    public void setConfigurations(Configuration[] configurations)
    {
        if (_configurations == null)
            _configurations = new Configurations();
        _configurations.set(configurations);
    }

    public void addConfiguration(Configuration... configuration)
    {
        loadConfigurations();
        _configurations.add(configuration);
    }

    public <T> T getConfiguration(Class<? extends T> configClass)
    {
        loadConfigurations();
        return _configurations.get(configClass);
    }

    public void removeConfiguration(Configuration... configurations)
    {
        if (_configurations != null)
            _configurations.remove(configurations);
    }

    public void removeConfiguration(Class<? extends Configuration>... configurations)
    {
        if (_configurations != null)
            _configurations.remove(configurations);
    }

    /**
     * The default descriptor is a web.xml format file that is applied to the context before the standard WEB-INF/web.xml
     *
     * @param defaultsDescriptor The defaultsDescriptor to set.
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     *
     * @param overrideDescriptor The overrideDescritpor to set.
     */
    public void setOverrideDescriptor(String overrideDescriptor)
    {
        _overrideDescriptors.clear();
        _overrideDescriptors.add(overrideDescriptor);
    }

    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     *
     * @param overrideDescriptors The overrideDescriptors (file or URL) to set.
     */
    public void setOverrideDescriptors(List<String> overrideDescriptors)
    {
        _overrideDescriptors.clear();
        _overrideDescriptors.addAll(overrideDescriptors);
    }

    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     *
     * @param overrideDescriptor The overrideDescriptor (file or URL) to add.
     */
    public void addOverrideDescriptor(String overrideDescriptor)
    {
        _overrideDescriptors.add(overrideDescriptor);
    }

    /**
     * @return the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists.
     */
    @ManagedAttribute(value = "standard web.xml descriptor", readonly = true)
    public String getDescriptor()
    {
        return _descriptor;
    }

    /**
     * @param descriptor the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists.
     */
    public void setDescriptor(String descriptor)
    {
        _descriptor = descriptor;
    }

    /**
     * @param distributable The distributable to set.
     */
    public void setDistributable(boolean distributable)
    {
        this._distributable = distributable;
    }

    @Override
    public boolean removeEventListener(EventListener listener)
    {
        if (super.removeEventListener(listener))
        {
            if ((listener instanceof HttpSessionActivationListener) ||
                (listener instanceof HttpSessionAttributeListener) ||
                (listener instanceof HttpSessionBindingListener) ||
                (listener instanceof HttpSessionListener) ||
                (listener instanceof HttpSessionIdListener))
            {
                if (_sessionHandler != null)
                    _sessionHandler.removeEventListener(listener);
            }
            return true;
        }
        return false;
    }

    /**
     * @param extractWAR True if war files are extracted
     */
    public void setExtractWAR(boolean extractWAR)
    {
        _extractWAR = extractWAR;
    }

    /**
     * @param copy True if the webdir is copied (to allow hot replacement of jars)
     */
    public void setCopyWebDir(boolean copy)
    {
        _copyDir = copy;
    }

    /**
     * @param copyWebInf True if the web-inf lib and classes directories are copied (to allow hot replacement of jars on windows)
     */
    public void setCopyWebInf(boolean copyWebInf)
    {
        _copyWebInf = copyWebInf;
    }

    /**
     * @param java2compliant True if the classloader should delegate first to the parent
     * classloader (standard java behaviour) or false if the classloader
     * should first try to load from WEB-INF/lib or WEB-INF/classes (servlet
     * spec recommendation).  Default is false or can be set by the system
     * property org.eclipse.jetty.server.webapp.parentLoaderPriority
     */
    public void setParentLoaderPriority(boolean java2compliant)
    {
        _parentLoaderPriority = java2compliant;
    }

    /**
     * @param permissions The permissions to set.
     */
    public void setPermissions(PermissionCollection permissions)
    {
        _permissions = permissions;
    }

    /**
     * Set the context white list
     *
     * In certain circumstances you want may want to deny access of one webapp from another
     * when you may not fully trust the webapp.  Setting this white list will enable a
     * check when a servlet called <code>ServletContextHandler.Context#getContext(String)</code>,
     * validating that the uriInPath for the given webapp has been declaratively allows access to the context.
     *
     * @param contextWhiteList the whitelist of contexts
     */
    public void setContextWhiteList(String... contextWhiteList)
    {
        _contextWhiteList = contextWhiteList;
    }

    /**
     * Set temporary directory for context.
     * The jakarta.servlet.context.tempdir attribute is also set.
     *
     * @param dir Writable temporary directory.
     */
    public void setTempDirectory(File dir)
    {
        if (isStarted())
            throw new IllegalStateException("Started");

        if (dir != null)
        {
            try
            {
                dir = new File(dir.getCanonicalPath());
            }
            catch (IOException e)
            {
                LOG.warn("Unable to find canonical path for {}", dir, e);
            }
        }

        _tmpDir = dir;
        setAttribute(TEMPDIR, _tmpDir);
    }

    @ManagedAttribute(value = "temporary directory location", readonly = true)
    public File getTempDirectory()
    {
        return _tmpDir;
    }

    /**
     * If true the temp directory for this
     * webapp will be kept when the webapp stops. Otherwise,
     * it will be deleted.
     *
     * @param persist true to persist the temp directory on shutdown / exit of the webapp
     */
    public void setPersistTempDirectory(boolean persist)
    {
        _persistTmpDir = persist;
    }

    /**
     * @return true if tmp directory will persist between startups of the webapp
     */
    public boolean isPersistTempDirectory()
    {
        return _persistTmpDir;
    }

    /**
     * Set the war of the webapp. From this value a {@link #setBaseResource(Resource)}
     * value is computed by {@link WebInfConfiguration}, which may be changed from
     * the war URI by unpacking and/or copying.
     *
     * @param war The war to set as a file name or URL.
     */
    public void setWar(String war)
    {
        _war = war;
    }

    /**
     * Set the war of the webapp as a {@link Resource}.
     *
     * @param war The war to set as a Resource.
     * @see #setWar(String)
     */
    public void setWarResource(Resource war)
    {
        setWar(war == null ? null : war.toString());
    }

    /**
     * @return Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     */
    @Override
    @ManagedAttribute(value = "extra classpath for context classloader", readonly = true)
    public ResourceCollection getExtraClasspath()
    {
        return _extraClasspath;
    }

    /**
     * Set the Extra ClassPath via delimited String.
     * <p>
     * This is a convenience method for {@link #setExtraClasspath(ResourceCollection)}
     * </p>
     *
     * @param extraClasspath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     * @see #setExtraClasspath(ResourceCollection)
     */
    public void setExtraClasspath(String extraClasspath)
    {
        List<URI> uris = URIUtil.split(extraClasspath);
        setExtraClasspath(ResourceFactory.of(this).newResource(uris));
    }

    public void setExtraClasspath(ResourceCollection extraClasspath)
    {
        _extraClasspath = extraClasspath;
    }

    public boolean isLogUrlOnStart()
    {
        return _logUrlOnStart;
    }

    /**
     * Sets whether or not the web app name and URL is logged on startup
     *
     * @param logOnStart whether or not the log message is created
     */
    public void setLogUrlOnStart(boolean logOnStart)
    {
        this._logUrlOnStart = logOnStart;
    }

    public boolean isAllowDuplicateFragmentNames()
    {
        return _allowDuplicateFragmentNames;
    }

    public void setAllowDuplicateFragmentNames(boolean allowDuplicateFragmentNames)
    {
        _allowDuplicateFragmentNames = allowDuplicateFragmentNames;
    }

    public void setThrowUnavailableOnStartupException(boolean throwIfStartupException)
    {
        _throwUnavailableOnStartupException = throwIfStartupException;
    }

    public boolean isThrowUnavailableOnStartupException()
    {
        return _throwUnavailableOnStartupException;
    }

    @Override
    protected void startContext()
        throws Exception
    {
        if (configure())
        {
            //resolve the metadata
            _metadata.resolve(this);
            super.startContext();
        }
    }

    @Override
    protected void stopContext() throws Exception
    {
        super.stopContext();
        try
        {
            for (int i = _configurations.size(); i-- > 0; )
            {
                _configurations.get(i).deconfigure(this);
            }

            if (_metadata != null)
                _metadata.clear();
            _metadata = new MetaData();
        }
        finally
        {
            if (_ownClassLoader)
            {
                ClassLoader loader = getClassLoader();
                if (loader instanceof URLClassLoader)
                    ((URLClassLoader)loader).close();
                setClassLoader(null);
            }

            _unavailableException = null;
        }
    }

    @Override
    public Set<String> setServletSecurity(Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        Set<String> unchangedURLMappings = new HashSet<>();
        //From javadoc for ServletSecurityElement:
        /*
        If a URL pattern of this ServletRegistration is an exact target of a security-constraint that 
        was established via the portable deployment descriptor, then this method does not change the 
        security-constraint for that pattern, and the pattern will be included in the return value.

        If a URL pattern of this ServletRegistration is an exact target of a security constraint 
        that was established via the ServletSecurity annotation or a previous call to this method, 
        then this method replaces the security constraint for that pattern.

        If a URL pattern of this ServletRegistration is neither the exact target of a security constraint 
        that was established via the ServletSecurity annotation or a previous call to this method, 
        nor the exact target of a security-constraint in the portable deployment descriptor, then 
        this method establishes the security constraint for that pattern from the argument ServletSecurityElement. 
         */

        java.util.Collection<String> pathMappings = registration.getMappings();
        if (pathMappings != null)
        {
            ConstraintSecurityHandler.createConstraint(registration.getName(), servletSecurityElement);

            for (String pathSpec : pathMappings)
            {
                Origin origin = getMetaData().getOrigin("constraint.url." + pathSpec);

                switch (origin)
                {
                    case NotSet:
                    {
                        //No mapping for this url already established
                        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                        for (ConstraintMapping m : mappings)
                        {
                            ((ConstraintAware)getSecurityHandler()).addConstraintMapping(m);
                        }
                        ((ConstraintAware)getSecurityHandler()).checkPathsWithUncoveredHttpMethods();
                        getMetaData().setOriginAPI("constraint.url." + pathSpec);
                        break;
                    }
                    case WebXml:
                    case WebDefaults:
                    case WebOverride:
                    case WebFragment:
                    {
                        //a mapping for this url was created in a descriptor, which overrides everything
                        unchangedURLMappings.add(pathSpec);
                        break;
                    }
                    case Annotation:
                    case API:
                    {
                        //mapping established via an annotation or by previous call to this method,
                        //replace the security constraint for this pattern
                        List<ConstraintMapping> constraintMappings = ConstraintSecurityHandler.removeConstraintMappingsForPath(pathSpec, ((ConstraintAware)getSecurityHandler()).getConstraintMappings());

                        List<ConstraintMapping> freshMappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                        constraintMappings.addAll(freshMappings);

                        ((ConstraintSecurityHandler)getSecurityHandler()).setConstraintMappings(constraintMappings);
                        ((ConstraintAware)getSecurityHandler()).checkPathsWithUncoveredHttpMethods();
                        break;
                    }
                    default:
                        throw new IllegalStateException(origin.toString());
                }
            }
        }

        return unchangedURLMappings;
    }
    
    public class ServletApiContext extends ServletContextHandler.ServletContextApi
    {
        
        @Override
        public ServletContext getContext(String uripath)
        {
            ServletContext servletContext = super.getContext(uripath);

            if (servletContext != null && _contextWhiteList != null)
            {
                for (String context : _contextWhiteList)
                {
                    if (context.equals(uripath))
                    {
                        return servletContext;
                    }
                }

                return null;
            }
            else
            {
                return servletContext;
            }
        }
        
        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            if (path == null)
                return null;

            Resource resource = WebAppContext.this.getResource(path);
            if (resource == null || !resource.exists())
                return null;

            // Should we go to the original war?
            if (resource.isDirectory() && !WebAppContext.this.isExtractWAR())
            {
                for (Resource r: resource)
                {
                    if (FileID.isWebArchive(r.getFileName()))
                        return r.getURI().toURL();
                }
            }

            return resource.getURI().toURL();
        }
    }

    public MetaData getMetaData()
    {
        return _metadata;
    }

    public static void addServerClasses(Server server, String... pattern)
    {
        addClasses(__dftServerClasses, SERVER_SRV_CLASSES, server, pattern);
    }

    public static void addSystemClasses(Server server, String... pattern)
    {
        addClasses(__dftSystemClasses, SERVER_SYS_CLASSES, server, pattern);
    }

    private static void addClasses(ClassMatcher matcher, String attribute, Server server, String... pattern)
    {
        if (pattern == null || pattern.length == 0)
            return;

        // look for a Server attribute with the list of System classes
        // to apply to every web application. If not present, use our defaults.
        Object o = server.getAttribute(attribute);
        if (o instanceof ClassMatcher)
        {
            ((ClassMatcher)o).add(pattern);
            return;
        }

        String[] classes;
        if (o instanceof String[])
            classes = (String[])o;
        else
            classes = matcher.getPatterns();
        int l = classes.length;
        classes = Arrays.copyOf(classes, l + pattern.length);
        System.arraycopy(pattern, 0, classes, l, pattern.length);
        server.setAttribute(attribute, classes);
    }
}
