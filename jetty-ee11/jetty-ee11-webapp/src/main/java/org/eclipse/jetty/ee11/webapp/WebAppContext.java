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

package org.eclipse.jetty.ee11.webapp;

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
import org.eclipse.jetty.ee.WebAppClassLoading;
import org.eclipse.jetty.ee11.servlet.ErrorHandler;
import org.eclipse.jetty.ee11.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.ee11.servlet.ServletContextHandler;
import org.eclipse.jetty.ee11.servlet.ServletHandler;
import org.eclipse.jetty.ee11.servlet.SessionHandler;
import org.eclipse.jetty.ee11.servlet.security.ConstraintAware;
import org.eclipse.jetty.ee11.servlet.security.ConstraintMapping;
import org.eclipse.jetty.ee11.servlet.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.ClassMatcher;
import org.eclipse.jetty.util.ExceptionUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.ClassLoaderDump;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.eclipse.jetty.util.resource.Resources;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Web Application Context Handler.
 * <p>
 * The WebAppContext handler is an extension of ContextHandler that
 * coordinates the construction and configuration of nested handlers:
 * {@link ConstraintSecurityHandler}, {@link org.eclipse.jetty.ee11.servlet.SessionHandler}
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

    public static final String WEB_DEFAULTS_XML = "org/eclipse/jetty/ee11/webapp/webdefault-ee11.xml";
    /**
     * @deprecated use {@link WebAppClassLoading#PROTECTED_CLASSES_ATTRIBUTE} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.9")
    public static final String SERVER_SYS_CLASSES = WebAppClassLoading.PROTECTED_CLASSES_ATTRIBUTE;
    /**
     * @deprecated use {@link WebAppClassLoading#HIDDEN_CLASSES_ATTRIBUTE} instead.
     */
    @Deprecated(forRemoval = true, since = "12.0.9")
    public static final String SERVER_SRV_CLASSES = WebAppClassLoading.HIDDEN_CLASSES_ATTRIBUTE;

    private static final String[] __dftProtectedTargets = {"/WEB-INF", "/META-INF"};

    private final ClassMatcher _protectedClasses = new ClassMatcher(WebAppClassLoading.getProtectedClasses(ServletContextHandler.ENVIRONMENT));
    private final ClassMatcher _hiddenClasses = new ClassMatcher(WebAppClassLoading.getHiddenClasses(ServletContextHandler.ENVIRONMENT));

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

    private String _war;
    private List<Resource> _extraClasspath;
    private Throwable _unavailableException;

    private Map<String, String> _resourceAliases;
    private ClassLoader _initialClassLoader;
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
        this(null, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
    }

    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(String webApp, String contextPath)
    {
        this(contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
        setWar(webApp);
    }

    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(Resource webApp, String contextPath)
    {
        this(contextPath, null, null, null, new ErrorPageErrorHandler(), SESSIONS | SECURITY);
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
        this(null, sessionHandler, securityHandler, servletHandler, errorHandler, 0);
    }

    /**
     * @param contextPath the context path
     * @param sessionHandler SessionHandler for this web app
     * @param securityHandler SecurityHandler for this web app
     * @param servletHandler ServletHandler for this web app
     * @param errorHandler ErrorHandler for this web app
     * @param options the options ({@link ServletContextHandler#SESSIONS} and/or {@link ServletContextHandler#SECURITY})
     */
    public WebAppContext(String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler, int options)
    {
        // always pass parent as null and then set below, so that any resulting setServer call
        // is done after this instance is constructed.
        super(contextPath, sessionHandler, securityHandler, servletHandler, errorHandler, options);
        setErrorHandler(errorHandler != null ? errorHandler : new ErrorPageErrorHandler());
        setProtectedTargets(__dftProtectedTargets);
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
                case Deployable.TEMP_DIR -> setTempDirectory(IO.asFile(value));
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
        if (servletContextName != null && cl instanceof WebAppClassLoader webAppClassLoader)
            webAppClassLoader.setName(servletContextName);
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

        if (classLoader instanceof WebAppClassLoader webAppClassLoader && getDisplayName() != null)
            webAppClassLoader.setName(name);
    }

    public ResourceFactory getResourceFactory()
    {
        return ResourceFactory.of(this);
    }

    @Override
    public Resource getResource(String pathInContext) throws MalformedURLException
    {
        if (pathInContext == null || !pathInContext.startsWith("/"))
            throw new MalformedURLException(pathInContext);

        MalformedURLException mue = null;
        Resource resource = null;
        int loop = 0;
        while (pathInContext != null && loop++ < 100)
        {
            try
            {
                resource = super.getResource(pathInContext);
                if (Resources.exists(resource))
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
            _hiddenClasses.include(configuration.getHiddenClasses().getInclusions());
        }

        // Setup Configuration classes for this webapp!
        loadConfigurations();
        _configurations.sort();
        for (Configuration configuration : _configurations)
        {
            _protectedClasses.add(configuration.getProtectedClasses().getPatterns());
            _hiddenClasses.exclude(configuration.getHiddenClasses().getExclusions());
        }

        // Configure classloader
        _initialClassLoader = getClassLoader();
        ClassLoader loader = configureClassLoader(_initialClassLoader);
        if (loader != _initialClassLoader)
            setClassLoader(loader);

        if (LOG.isDebugEnabled())
        {
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

    /**
     * Configure the context {@link ClassLoader}, potentially wrapping it.
     * @param loader The loader initially set on this context by {@link #setClassLoader(ClassLoader)}
     * @return Either the configured loader, or a new {@link ClassLoader} that uses the loader.
     */
    protected ClassLoader configureClassLoader(ClassLoader loader)
    {
        if (loader instanceof WebAppClassLoader)
            return loader;
        return new WebAppClassLoader(loader, this);
    }

    @Override
    protected void createTempDirectory()
    {
        super.createTempDirectory();
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
        Thread.currentThread().setContextClassLoader(ServletContextHandler.ENVIRONMENT.getClassLoader());
        try
        {
            _metadata.setAllowDuplicateFragmentNames(isAllowDuplicateFragmentNames());
            Boolean validate = (Boolean)getAttribute(MetaData.VALIDATE_XML);
            _metadata.setValidateXml((validate != null && validate));
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
        ExceptionUtil.ifExceptionThrowUnchecked(multiException);
    }

    /*
     * Dumps the current web app name and URL to the log
     */
    private void dumpUrl()
    {
        Connector[] connectors = getServer().getConnectors();
        for (Connector connector : connectors)
        {
            String displayName = getDisplayName();
            if (displayName == null)
                displayName = "WebApp@" + Arrays.hashCode(connectors);

            LOG.info("{} at http://{}{}", displayName, connector.toString(), getContextPath());
        }
    }

    /**
     * @return Returns the configurations.
     */
    @ManagedAttribute(value = "configuration classes used to configure webapp", readonly = true)
    public String[] getConfigurationClasses()
    {
        loadConfigurations();
        return _configurations.toStringArray();
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
     * Set the hidden (server) classes patterns.
     * <p>
     * These classes/packages are used to implement the server and are hidden
     * from the context.  If the context needs to load these classes, it must have its
     * own copy of them in WEB-INF/lib or WEB-INF/classes.
     *
     * @param hiddenClasses the server classes pattern
     */
    public void setHiddenClassMatcher(ClassMatcher hiddenClasses)
    {
        _hiddenClasses.clear();
        _hiddenClasses.add(hiddenClasses.getPatterns());
    }

    /**
     * Set the protected (system) classes patterns.
     * <p>
     * These classes/packages are provided by the JVM and
     * cannot be replaced by classes of the same name from WEB-INF,
     * regardless of the value of {@link #setParentLoaderPriority(boolean)}.
     *
     * @param protectedClasses the system classes pattern
     */
    public void setProtectedClassMatcher(ClassMatcher protectedClasses)
    {
        _protectedClasses.clear();
        _protectedClasses.add(protectedClasses.getPatterns());
    }

    /**
     * Add a ClassMatcher for hidden (server) classes by combining with
     * any existing matcher.
     *
     * @param hiddenClasses The class matcher of patterns to add to the server ClassMatcher
     */
    public void addHiddenClassMatcher(ClassMatcher hiddenClasses)
    {
        _hiddenClasses.add(hiddenClasses.getPatterns());
    }

    /**
     * Add a ClassMatcher for protected (system) classes by combining with
     * any existing matcher.
     *
     * @param protectedClasses The class matcher of patterns to add to the system ClassMatcher
     */
    public void addProtectedClassMatcher(ClassMatcher protectedClasses)
    {
        _protectedClasses.add(protectedClasses.getPatterns());
    }

    /**
     * @return The ClassMatcher used to match System (protected) classes
     */
    public ClassMatcher getProtectedClassMatcher()
    {
        return _protectedClasses;
    }

    /**
     * @return The ClassMatcher used to match Server (hidden) classes
     */
    public ClassMatcher getHiddenClassMatcher()
    {
        return _hiddenClasses;
    }

    @ManagedAttribute(value = "classes and packages protected by context classloader", readonly = true)
    public String[] getProtectedClasses()
    {
        return _protectedClasses.getPatterns();
    }

    @ManagedAttribute(value = "classes and packages hidden by the context classloader", readonly = true)
    public String[] getHiddenClasses()
    {
        return _hiddenClasses.getPatterns();
    }

    @Override
    public boolean isHiddenClass(Class<?> clazz)
    {
        return _hiddenClasses.match(clazz);
    }

    @Override
    public boolean isProtectedClass(Class<?> clazz)
    {
        return _protectedClasses.match(clazz);
    }

    @Override
    public boolean isHiddenResource(String name, URL url)
    {
        return _hiddenClasses.match(name, url);
    }

    @Override
    public boolean isProtectedResource(String name, URL url)
    {
        return _protectedClasses.match(name, url);
    }

    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (server != null)
        {
            _protectedClasses.add(WebAppClassLoading.getProtectedClasses(server).getPatterns());
            _hiddenClasses.add(WebAppClassLoading.getHiddenClasses(server).getPatterns());
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
        // ResourceBase could be a CombinedResource
        // The result could be a CombinedResource with multiple WEB-INF directories
        // Can return from WEB-INF/lib/foo.jar!/WEB-INF
        // Can also never return from a META-INF/versions/#/WEB-INF location
        Resource webInf = getBaseResource().resolve("WEB-INF/");
        if (Resources.isReadableDirectory(webInf))
            return webInf;

        return null;
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
        return new Configurations(Configurations.getServerDefault(getServer()).getConfigurations());
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
        List<String> protectedClasses = null;
        if (_protectedClasses != null)
        {
            protectedClasses = new ArrayList<>(_protectedClasses);
            Collections.sort(protectedClasses);
        }

        List<String> hiddenClasses = null;
        if (_hiddenClasses != null)
        {
            hiddenClasses = new ArrayList<>(_hiddenClasses);
            Collections.sort(hiddenClasses);
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
            new DumpableCollection("Protected classes " + name, protectedClasses),
            new DumpableCollection("Hidden classes " + name, hiddenClasses),
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
    public void setConfigurations(Configurations configurations)
    {
        _configurations = configurations == null ? new Configurations() : configurations;
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
     * Set the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists..
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
     * <p>
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
    public List<Resource> getExtraClasspath()
    {
        return _extraClasspath == null ? Collections.emptyList() : _extraClasspath;
    }

    /**
     * Set the Extra ClassPath via delimited String.
     * <p>
     * This is a convenience method for {@link #setExtraClasspath(List)}
     * </p>
     *
     * @param extraClasspath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     * @see #setExtraClasspath(List)
     */
    public void setExtraClasspath(String extraClasspath)
    {
        setExtraClasspath(getResourceFactory().split(extraClasspath));
    }

    public void setExtraClasspath(List<Resource> extraClasspath)
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

    public void resolveMetaData() throws Exception
    {
        LOG.debug("metadata resolve {}", this);

        //Ensure origins is fresh
        _metadata._origins.clear();

        // Set the ordered lib attribute
        List<Resource> orderedWebInfJars;
        if (_metadata.isOrdered())
        {
            orderedWebInfJars = _metadata.getWebInfResources(true);
            List<String> orderedLibs = new ArrayList<>();
            for (Resource jar: orderedWebInfJars)
            {
                URI uri = URIUtil.unwrapContainer(jar.getURI());
                orderedLibs.add(uri.getPath());
            }
            setAttribute(ServletContext.ORDERED_LIBS, Collections.unmodifiableList(orderedLibs));
        }

        // set the webxml version
        if (_metadata._webXmlRoot != null)
        {
            getContext().getServletContext().setEffectiveMajorVersion(_metadata._webXmlRoot.getMajorVersion());
            getContext().getServletContext().setEffectiveMinorVersion(_metadata._webXmlRoot.getMinorVersion());
        }

        //process web-defaults.xml, web.xml and override-web.xmls
        for (DescriptorProcessor p : _metadata._descriptorProcessors)
        {
            p.process(this, _metadata.getDefaultsDescriptor());
            p.process(this, _metadata.getWebDescriptor());
            for (WebDescriptor wd : _metadata.getOverrideDescriptors())
            {
                LOG.debug("process {} {} {}", this, p, wd);
                p.process(this, wd);
            }
        }

        List<Resource> resources = new ArrayList<>();
        resources.add(null); //always apply annotations with no resource first
        resources.addAll(_metadata._orderedContainerResources); //next all annotations from container path
        resources.addAll(_metadata._webInfClasses); //next everything from web-inf classes
        resources.addAll(_metadata.getWebInfResources(_metadata.isOrdered())); //finally annotations (in order) from webinf path

        for (Resource r : resources)
        {
            //Process the web-fragment.xml before applying annotations from a fragment.
            //Note that some fragments, or resources that aren't fragments won't have
            //a descriptor.
            FragmentDescriptor fd = _metadata._webFragmentResourceMap.get(r);
            if (fd != null)
            {
                for (DescriptorProcessor p : _metadata._descriptorProcessors)
                {
                    LOG.debug("process {} {}", this, fd);
                    p.process(this, fd);
                }
            }

            //Then apply the annotations - note that if metadata is complete
            //either overall or for a fragment, those annotations won't have
            //been discovered.
            List<DiscoveredAnnotation> annotations = _metadata._annotations.get(r);
            if (annotations != null)
            {
                for (DiscoveredAnnotation a : annotations)
                {
                    LOG.debug("apply {}", a);
                    a.apply();
                }
            }
        }
    }

    @Override
    protected void startContext()
        throws Exception
    {
        if (configure())
        {
            resolveMetaData();
            startWebapp();
        }
    }

    @Override
    protected void stopContext() throws Exception
    {
        stopWebapp();

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
            ClassLoader loader = getClassLoader();
            if (loader != _initialClassLoader)
            {
                if (loader instanceof URLClassLoader urlClassLoader)
                    urlClassLoader.close();
                setClassLoader(_initialClassLoader);
            }

            _unavailableException = null;

            super.cleanupAfterStop();
        }
    }

    /**
     * Continue the {@link #startContext()} before calling {@code super.startContext()}.
     * @throws Exception If there was a problem starting
     */
    protected void startWebapp() throws Exception
    {
        super.startContext();
    }

    /**
     * Continue the {@link #stopContext()} before calling {@code super.stopContext()}.
     * @throws Exception If there was a problem stopping
     */
    protected void stopWebapp() throws Exception
    {
        super.stopContext();
    }

    /**
     * Prevent the temp directory from being deleted during the normal stop sequence, and require that
     * {@link ContextHandler#cleanupAfterStop()} is explicitly called after the webapp classloader is closed
     */
    @Override
    protected void cleanupAfterStop() throws Exception
    {
        //intentionally left blank
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
        if (pathMappings != null && getSecurityHandler() instanceof ConstraintAware constraintAware)
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
                            constraintAware.addConstraintMapping(m);
                        }
                        constraintAware.checkPathsWithUncoveredHttpMethods();
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
                        List<ConstraintMapping> constraintMappings = ConstraintSecurityHandler.removeConstraintMappingsForPath(pathSpec, constraintAware.getConstraintMappings());

                        List<ConstraintMapping> freshMappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                        constraintMappings.addAll(freshMappings);

                        ((ConstraintSecurityHandler)getSecurityHandler()).setConstraintMappings(constraintMappings);
                        constraintAware.checkPathsWithUncoveredHttpMethods();
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
        public jakarta.servlet.ServletContext getContext(String path)
        {
            jakarta.servlet.ServletContext servletContext = super.getContext(path);

            if (servletContext != null && _contextWhiteList != null)
            {
                for (String context : _contextWhiteList)
                {
                    if (context.equals(path))
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

            // Assumption is that the resource base has been properly setup.
            // Spec requirement is that the WAR file is interrogated first.
            // If a WAR file is mounted, or is extracted to a temp directory,
            // then the first entry of the resource base must be the WAR file.
            Resource resource = WebAppContext.this.getResource(path);
            if (Resources.missing(resource))
                return null;

            for (Resource r: resource)
            {
                // return first entry
                return r.getURI().toURL();
            }

            // A Resource was returned, but did not exist
            return null;
        }
    }

    public MetaData getMetaData()
    {
        return _metadata;
    }
}
