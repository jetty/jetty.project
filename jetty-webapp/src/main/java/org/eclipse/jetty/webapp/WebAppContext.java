//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.PermissionCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.servlet.ServletContext;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.ServletSecurityElement;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionIdListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.security.ConstraintAware;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.ClassLoaderDump;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AbstractHandler;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.handler.ManagedAttributeListener;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.TopologicalSort;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.eclipse.jetty.util.component.DumpableCollection;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

/** 
 * Web Application Context Handler.
 * <p>
 * The WebAppContext handler is an extension of ContextHandler that
 * coordinates the construction and configuration of nested handlers:
 * {@link org.eclipse.jetty.security.ConstraintSecurityHandler}, {@link org.eclipse.jetty.server.session.SessionHandler}
 * and {@link org.eclipse.jetty.servlet.ServletHandler}.
 * The handlers are configured by pluggable configuration classes, with
 * the default being  {@link org.eclipse.jetty.webapp.WebXmlConfiguration} and
 * {@link org.eclipse.jetty.webapp.JettyWebXmlConfiguration}.
 *
 *
 * <p>
 * The Start/Configuration of a WebAppContext is rather complex so as to allow
 * pluggable behaviour to be added in almost arbitrary ordering.  The
 * sequence of a WebappContext start is as follows:
 * <blockquote>
 * {@link #doStart()}:
 *   <ul>
 *     <li>{@link #preConfigure()}
 *       <ul>
 *         <li>Add all Server class inclusions from all known configurations {@link Configurations#getKnown()}</li>
 *         <li>{@link #loadConfigurations()}, which uses either explicitly set Configurations or takes the server
 *         default (which is all known non {@link Configuration.DisabledByDefault} Configurations.</li>
 *         <li>Sort the configurations using {@link TopologicalSort} in {@link Configurations#sort()}.</li>
 *         <li>Add all Server class exclusions from this webapps {@link Configurations}</li>
 *         <li>Add all System classes inclusions and exclusions for this webapps {@link Configurations}</li>
 *         <li>Instantiate the WebAppClassLoader (if one not already explicitly set)</li>
 *         <li>{@link Configuration#preConfigure(WebAppContext)} which calls
 *         {@link Configuration#preConfigure(WebAppContext)} for this webapps {@link Configurations}</li>
 *       </ul>
 *     </li>
 *     <li>{@link ServletContextHandler#doStart()}
 *       <ul>
 *         <li>{@link ContextHandler#doStart()}
 *           <ul>
 *             <li>Init {@link MimeTypes}</li>
 *             <li>enterScope
 *               <ul>
 *                 <li>{@link #startContext()}
 *                   <ul>
 *                     <li>{@link #configure()}
 *                       <ul>
 *                         <li>Call {@link Configuration#configure(WebAppContext)} on enabled {@link Configurations}</li>
 *                       </ul>
 *                     </li>
 *                     <li>{@link MetaData#resolve(WebAppContext)}</li>
 *                     <li>{@link #startContext()}
 *                       <li>QuickStart may generate here and/or abort start
 *                       <ul>
 *                         <li>{@link ServletContextHandler#startContext}
 *                           <ul>
 *                             <li>Decorate listeners</li>
 *                             <li>{@link ContextHandler#startContext}
 *                               <ul>
 *                                 <li>add {@link ManagedAttributeListener}</li>
 *                                 <li>{@link AbstractHandler#doStart}</li>
 *                                 <li>{@link #callContextInitialized(javax.servlet.ServletContextListener, javax.servlet.ServletContextEvent)}</li>
 *                               </ul>
 *                             </li>
 *                             <li>{@link ServletHandler#initialize()}</li>
 *                           </ul>
 *                         </li>
 *                       </ul>
 *                     </li>
 *                   </ul>
 *                 </li>
 *               </ul>
 *             </li>
 *             <li>exitScope</li>
 *           </ul>
 *         </li>
 *       </ul>
 *     </li>
 *     <li>{@link #postConfigure()}</li>
 *   </ul>
 *
 * </blockquote>
 *
 */
@ManagedObject("Web Application ContextHandler")
public class WebAppContext extends ServletContextHandler implements WebAppClassLoader.Context
{
    static final Logger LOG = Log.getLogger(WebAppContext.class);

    public static final String TEMPDIR = "javax.servlet.context.tempdir";
    public static final String BASETEMPDIR = "org.eclipse.jetty.webapp.basetempdir";
    public final static String WEB_DEFAULTS_XML="org/eclipse/jetty/webapp/webdefault.xml";
    public final static String ERROR_PAGE="org.eclipse.jetty.server.error_page";
    public final static String SERVER_SYS_CLASSES = "org.eclipse.jetty.webapp.systemClasses";
    public final static String SERVER_SRV_CLASSES = "org.eclipse.jetty.webapp.serverClasses";

    private String[] __dftProtectedTargets = {"/web-inf", "/meta-inf"};

    // System classes are classes that cannot be replaced by
    // the web application, and they are *always* loaded via
    // system classloader.
    public final static ClasspathPattern __dftSystemClasses = new ClasspathPattern
    (
        "java.",                            // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "javax.",                           // Java SE classes (per servlet spec v2.5 / SRV.9.7.2)
        "org.xml.",                         // javax.xml
        "org.w3c."                          // javax.xml
    ) ;

    // Server classes are classes that are hidden from being
    // loaded by the web application using system classloader,
    // so if web application needs to load any of such classes,
    // it has to include them in its distribution.
    public final static ClasspathPattern __dftServerClasses =new ClasspathPattern
    (
        "org.eclipse.jetty."                // hide jetty classes
    );

    private final Configurations _configurations = new Configurations();
    private final ClasspathPattern _systemClasses = new ClasspathPattern(__dftSystemClasses);
    private final ClasspathPattern _serverClasses = new ClasspathPattern(__dftServerClasses);

    private String _defaultsDescriptor=WEB_DEFAULTS_XML;
    private String _descriptor=null;
    private final List<String> _overrideDescriptors = new ArrayList<>();
    private boolean _distributable=false;
    private boolean _extractWAR=true;
    private boolean _copyDir=false;
    private boolean _copyWebInf=false;
    private boolean _logUrlOnStart =false;
    private boolean _parentLoaderPriority= Boolean.getBoolean("org.eclipse.jetty.server.webapp.parentLoaderPriority");
    private PermissionCollection _permissions;

    private String[] _contextWhiteList = null;

    private File _tmpDir;
    private boolean _persistTmpDir = false;
 
    private String _war;
    private String _extraClasspath;
    private Throwable _unavailableException;

    private Map<String, String> _resourceAliases;
    private boolean _ownClassLoader=false;
    private boolean _configurationDiscovered=true;
    private boolean _allowDuplicateFragmentNames = false;
    private boolean _throwUnavailableOnStartupException = false;
    


    private MetaData _metadata=new MetaData();

    public static WebAppContext getCurrentWebAppContext()
    {
        ContextHandler.Context context=ContextHandler.getCurrentContext();
        if (context!=null)
        {
            ContextHandler handler = context.getContextHandler();
            if (handler instanceof WebAppContext)
                return (WebAppContext)handler;
        }
        return null;
    }

    /* ------------------------------------------------------------ */
    public WebAppContext()
    {
        this(null,null,null,null,null,new ErrorPageErrorHandler(),SESSIONS|SECURITY);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(String webApp,String contextPath)
    {
        this(null,contextPath,null,null,null,new ErrorPageErrorHandler(),SESSIONS|SECURITY);
        setWar(webApp);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param parent The parent HandlerContainer.
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(HandlerContainer parent, String webApp, String contextPath)
    {
        this(parent,contextPath,null,null,null,new ErrorPageErrorHandler(),SESSIONS|SECURITY);
        setWar(webApp);
    }

    /* ------------------------------------------------------------ */

    /**
     * This constructor is used in the geronimo integration.
     *
     * @param sessionHandler SessionHandler for this web app
     * @param securityHandler SecurityHandler for this web app
     * @param servletHandler ServletHandler for this web app
     * @param errorHandler ErrorHandler for this web app
     */
    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler) 
    {
        this(null, null, sessionHandler, securityHandler, servletHandler, errorHandler,0);
    }

    /* ------------------------------------------------------------ */
    /**
     * This constructor is used in the geronimo integration.
     * 
     * @param parent the parent handler 
     * @param contextPath the context path
     * @param sessionHandler SessionHandler for this web app
     * @param securityHandler SecurityHandler for this web app
     * @param servletHandler ServletHandler for this web app
     * @param errorHandler ErrorHandler for this web app
     * @param options the options ({@link ServletContextHandler#SESSIONS} and/or {@link ServletContextHandler#SECURITY}) 
     */
    public WebAppContext(HandlerContainer parent, String contextPath, SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler,int options) 
    {
        // always pass parent as null and then set below, so that any resulting setServer call
        // is done after this instance is constructed.
        super(null,contextPath,sessionHandler, securityHandler, servletHandler, errorHandler,options);
        _scontext = new Context();
        setErrorHandler(errorHandler != null ? errorHandler : new ErrorPageErrorHandler());
        setProtectedTargets(__dftProtectedTargets);
        if (parent!=null)
            setParent(parent);
    }

    /* ------------------------------------------------------------ */
    /**
     * @param servletContextName The servletContextName to set.
     */
    @Override
    public void setDisplayName(String servletContextName)
    {
        super.setDisplayName(servletContextName);
        ClassLoader cl = getClassLoader();
        if (cl!=null && cl instanceof WebAppClassLoader && servletContextName!=null)
            ((WebAppClassLoader)cl).setName(servletContextName);
    }

    /* ------------------------------------------------------------ */
    /** Get an exception that caused the webapp to be unavailable
     * @return A throwable if the webapp is unavailable or null
     */
    public Throwable getUnavailableException()
    {
        return _unavailableException;
    }

    /* ------------------------------------------------------------ */
    /** 
     * Set Resource Alias.
     * Resource aliases map resource uri's within a context.
     * They may optionally be used by a handler when looking for
     * a resource.
     * @param alias the alias for a resource
     * @param uri the uri for the resource
     */
    public void setResourceAlias(String alias, String uri)
    {
        if (_resourceAliases == null)
            _resourceAliases= new HashMap<String, String>(5);
        _resourceAliases.put(alias, uri);
    }

    /* ------------------------------------------------------------ */
    public Map<String, String> getResourceAliases()
    {
        if (_resourceAliases == null)
            return null;
        return _resourceAliases;
    }

    /* ------------------------------------------------------------ */
    public void setResourceAliases(Map<String, String> map)
    {
        _resourceAliases = map;
    }

    /* ------------------------------------------------------------ */
    public String getResourceAlias(String path)
    {
        if (_resourceAliases == null)
            return null;
        String alias = _resourceAliases.get(path);

        int slash=path.length();
        while (alias==null)
        {
            slash=path.lastIndexOf("/",slash-1);
            if (slash<0)
                break;
            String match=_resourceAliases.get(path.substring(0,slash+1));
            if (match!=null)
                alias=match+path.substring(slash+1);
        }
        return alias;
    }

    /* ------------------------------------------------------------ */
    public String removeResourceAlias(String alias)
    {
        if (_resourceAliases == null)
            return null;
        return _resourceAliases.remove(alias);
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.ContextHandler#setClassLoader(java.lang.ClassLoader)
     */
    @Override
    public void setClassLoader(ClassLoader classLoader)
    {
        super.setClassLoader(classLoader);

        String name = getDisplayName();
        if (name==null) 
            name=getContextPath();
        
        if (classLoader!=null && classLoader instanceof WebAppClassLoader && getDisplayName()!=null)
            ((WebAppClassLoader)classLoader).setName(name);
    }

    /* ------------------------------------------------------------ */
    @Override
    public Resource getResource(String uriInContext) throws MalformedURLException
    {
        if (uriInContext==null || !uriInContext.startsWith(URIUtil.SLASH))
            throw new MalformedURLException(uriInContext);

        IOException ioe= null;
        Resource resource= null;
        int loop=0;
        while (uriInContext!=null && loop++<100)
        {
            try
            {
                resource= super.getResource(uriInContext);
                if (resource != null && resource.exists())
                    return resource;

                uriInContext = getResourceAlias(uriInContext);
            }
            catch (IOException e)
            {
                LOG.ignore(e);
                if (ioe==null)
                    ioe= e;
            }
        }

        if (ioe != null && ioe instanceof MalformedURLException)
            throw (MalformedURLException)ioe;

        return resource;
    }


    /* ------------------------------------------------------------ */
    /** Is the context Automatically configured.
     *
     * @return true if configuration discovery.
     */
    public boolean isConfigurationDiscovered()
    {
        return _configurationDiscovered;
    }

    /* ------------------------------------------------------------ */
    /** Set the configuration discovery mode.
     * If configuration discovery is set to true, then the JSR315
     * servlet 3.0 discovered configuration features are enabled.
     * These are:<ul>
     * <li>Web Fragments</li>
     * <li>META-INF/resource directories</li>
     * </ul>
     * @param discovered true if configuration discovery is enabled for automatic configuration from the context
     */
    public void setConfigurationDiscovered(boolean discovered)
    {
        _configurationDiscovered = discovered;
    }

    /* ------------------------------------------------------------ */
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
     * @throws Exception if unable to pre configure
     */
    public void preConfigure() throws Exception
    {
        // Add the known server class inclusions for all known configurations
        for (Configuration configuration : Configurations.getKnown())
            _serverClasses.include(configuration.getServerClasses().getInclusions());
        
        // Setup Configuration classes for this webapp!
        loadConfigurations();
        _configurations.sort();
        for (Configuration configuration:_configurations)
        {
            _systemClasses.add(configuration.getSystemClasses().getPatterns());
            _serverClasses.exclude(configuration.getServerClasses().getExclusions());
        }
        
        // Configure classloader
        _ownClassLoader=false;
        if (getClassLoader()==null)
        {
            WebAppClassLoader classLoader = new WebAppClassLoader(this);
            setClassLoader(classLoader);
            _ownClassLoader=true;
        }

        if (LOG.isDebugEnabled())
        {
            ClassLoader loader = getClassLoader();
            LOG.debug("Thread Context classloader {}",loader);
            loader=loader.getParent();
            while(loader!=null)
            {
                LOG.debug("Parent class loader: {} ",loader);
                loader=loader.getParent();
            }
        }

        _configurations.preConfigure(this);
    }

    /* ------------------------------------------------------------ */
    public boolean configure() throws Exception
    {
        return _configurations.configure(this);
    }

    /* ------------------------------------------------------------ */
    public void postConfigure() throws Exception
    {
        _configurations.postConfigure(this);
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    @Override
    protected void doStart() throws Exception
    {
        try
        {
            _metadata.setAllowDuplicateFragmentNames(isAllowDuplicateFragmentNames());
            Boolean validate = (Boolean)getAttribute(MetaData.VALIDATE_XML);
            _metadata.setValidateXml((validate!=null && validate.booleanValue()));
            preConfigure();
            super.doStart();
            postConfigure();

            if (isLogUrlOnStart())
                dumpUrl();
        }
        catch (Exception e)
        {
            //start up of the webapp context failed, make sure it is not started
            LOG.warn("Failed startup of context "+this, e);
            _unavailableException=e;
            setAvailable(false);
            if (isThrowUnavailableOnStartupException())
                throw e;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    @Override
    protected void doStop() throws Exception
    {
        super.doStop();
    }

    /* ------------------------------------------------------------ */
    @Override
    public void destroy()
    {
        // Prepare for configuration
        MultiException mx=new MultiException();
        for (Configuration configuration : _configurations)
        {
            try
            {
                configuration.destroy(this);
            }
            catch(Exception e)
            {
                mx.add(e);
            }
        }
        _configurations.clear();
        super.destroy();
        mx.ifExceptionThrowRuntime();
    }


    /* ------------------------------------------------------------ */
    /*
     * Dumps the current web app name and URL to the log
     */
    private void dumpUrl()
    {
        Connector[] connectors = getServer().getConnectors();
        for (int i=0;i<connectors.length;i++)
        {
            String displayName = getDisplayName();
            if (displayName == null)
                displayName = "WebApp@"+connectors.hashCode();

            LOG.info(displayName + " at http://" + connectors[i].toString() + getContextPath());
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the configurations.
     */
    @ManagedAttribute(value="configuration classes used to configure webapp", readonly=true)
    public String[] getConfigurationClasses()
    {
        loadConfigurations();
        return _configurations.toArray();
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the configurations.
     * @deprecated
     */
    public Configuration[] getConfigurations()
    {
        if (_configurations.size()==0)
            loadConfigurations();
        return _configurations.getConfigurations().toArray(new Configuration[_configurations.size()]);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the configurations.
     */
    public Configurations getWebAppConfigurations()
    {
        if (_configurations.size()==0)
            loadConfigurations();
        return _configurations;
    }

    /* ------------------------------------------------------------ */
    /**
     * The default descriptor is a web.xml format file that is applied to the context before the standard WEB-INF/web.xml
     * @return Returns the defaultsDescriptor.
     */
    @ManagedAttribute(value="default web.xml deascriptor applied before standard web.xml", readonly=true)
    public String getDefaultsDescriptor()
    {
        return _defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     * @return Returns the Override Descriptor.
     */
    public String getOverrideDescriptor()
    {
        if (_overrideDescriptors.size()!=1)
            return null;
        return _overrideDescriptors.get(0);
    }

    /* ------------------------------------------------------------ */
    /**
     * An override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     * @return Returns the Override Descriptor list
     */
    @ManagedAttribute(value="web.xml deascriptors applied after standard web.xml", readonly=true)
    public List<String> getOverrideDescriptors()
    {
        return Collections.unmodifiableList(_overrideDescriptors);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the permissions.
     */
    @Override
    public PermissionCollection getPermissions()
    {
        return _permissions;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the server classes patterns.
     * <p>
     * Server classes/packages are classes used to implement the server and are hidden
     * from the context.  If the context needs to load these classes, it must have its
     * own copy of them in WEB-INF/lib or WEB-INF/classes.
     * @param serverClasses the server classes pattern
     *
     */
    public void setServerClasspathPattern(ClasspathPattern serverClasses)
    {
        _serverClasses.clear();
        _serverClasses.add(serverClasses.getPatterns());
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the system classes patterns.
     * <p>
     * System classes/packages are classes provided by the JVM and that
     * cannot be replaced by classes of the same name from WEB-INF,
     * regardless of the value of {@link #setParentLoaderPriority(boolean)}.
     * @param systemClasses the system classes pattern
     */
    public void setSystemClasspathPattern(ClasspathPattern systemClasses)
    {
        _systemClasses.clear();
        _systemClasses.add(systemClasses.getPatterns());
    }

    /* ------------------------------------------------------------ */
    public void addServerClasspathPattern(ClasspathPattern serverClasses)
    {
        _serverClasses.add(serverClasses.getPatterns());
    }

    /* ------------------------------------------------------------ */
    public void addSystemClasspathPattern(ClasspathPattern systemClasses)
    {
        _serverClasses.add(systemClasses.getPatterns());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The ClasspathPattern used to match System (protected) classes
     */
    public ClasspathPattern getSystemClasspathPattern()
    {
        return _systemClasses;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return The ClasspathPattern used to match Server (hidden) classes
     */
    public ClasspathPattern getServerClasspathPattern()
    {
        return _serverClasses;
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute(value="classes and packages protected by context classloader", readonly=true)
    public String[] getSystemClasses()
    {
        return _systemClasses.getPatterns();
    }

    /* ------------------------------------------------------------ */
    /**
     * @see #setServerClasses(String[])
     * @return Returns the serverClasses.
     */
    @ManagedAttribute(value="classes and packages hidden by the context classloader", readonly=true)
    public String[] getServerClasses()
    {
        return _serverClasses.getPatterns();
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void addSystemClass(String clazz)
    {
        _systemClasses.add(clazz);
    }

    /* ------------------------------------------------------------ */
    @Deprecated
    public void addServerClass(String clazz)
    {
        _serverClasses.add(clazz);
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isServerClass(Class<?> clazz)
    {
        boolean result = _serverClasses.match(clazz);
        if (LOG.isDebugEnabled())
            LOG.debug("isServerClass=={} {}",result,clazz);
        return result;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isSystemClass(Class<?> clazz)
    {
        boolean result = _systemClasses.match(clazz);
        if (LOG.isDebugEnabled())
            LOG.debug("isSystemClass=={} {}",result,clazz);
        return result;
    }

    /* ------------------------------------------------------------ */
    @Override
    public boolean isServerResource(String name, URL url)
    {
        boolean result =  _serverClasses.match(name,url);
        if (LOG.isDebugEnabled())
            LOG.debug("isServerResource=={} {} {}",result,name,url);
        return result;
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public boolean isSystemResource(String name, URL url)
    {
        boolean result = _systemClasses.match(name,url);
        if (LOG.isDebugEnabled())
            LOG.debug("isSystemResource=={} {} {}",result,name,url);
        return result;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setServer(Server server)
    {
        super.setServer(server);
        if (server != null)
        {
            if (_systemClasses.isEmpty())
            {
                Object systemClasses = server.getAttribute(SERVER_SYS_CLASSES);
                if (systemClasses instanceof String[])
                    systemClasses = new ClasspathPattern((String[])systemClasses);
                if (systemClasses instanceof ClasspathPattern)
                    _systemClasses.add(((ClasspathPattern)systemClasses).getPatterns());
            }

            if (_serverClasses.isEmpty())
            {
                Object serverClasses = server.getAttribute(SERVER_SRV_CLASSES);
                if (serverClasses instanceof String[])
                    serverClasses = new ClasspathPattern((String[])serverClasses);
                if (serverClasses instanceof ClasspathPattern)
                    _systemClasses.add(((ClasspathPattern)serverClasses).getPatterns());
            }
        }
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the war as a file or URL string (Resource)
     */
    @ManagedAttribute(value="war file location", readonly=true)
    public String getWar()
    {
        if (_war==null)
            _war=getResourceBase();
        return _war;
    }

    /* ------------------------------------------------------------ */
    public Resource getWebInf() throws IOException
    {
        if (super.getBaseResource() == null)
            return null;

        // Iw there a WEB-INF directory?
        Resource web_inf= super.getBaseResource().addPath("WEB-INF/");
        if (!web_inf.exists() || !web_inf.isDirectory())
            return null;

        return web_inf;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the distributable.
     */
    @ManagedAttribute("web application distributable")
    public boolean isDistributable()
    {
        return _distributable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the extractWAR.
     */
    @ManagedAttribute(value="extract war", readonly=true)
    public boolean isExtractWAR()
    {
        return _extractWAR;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the webdir is copied (to allow hot replacement of jars on windows)
     */
    @ManagedAttribute(value="webdir copied on deploy (allows hot replacement on windows)", readonly=true)
    public boolean isCopyWebDir()
    {
        return _copyDir;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the web-inf lib and classes directories are copied (to allow hot replacement of jars on windows)
     */
    public boolean isCopyWebInf()
    {
        return _copyWebInf;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the classloader should delegate first to the parent
     * classloader (standard java behaviour) or false if the classloader
     * should first try to load from WEB-INF/lib or WEB-INF/classes (servlet
     * spec recommendation). Default is false or can be set by the system 
     * property org.eclipse.jetty.server.webapp.parentLoaderPriority
     */
    @Override
    @ManagedAttribute(value="parent classloader given priority", readonly=true)
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    protected void loadConfigurations()
    {
        //if the configuration instances have been set explicitly, use them
        if (_configurations.size()>0)
            return;
        
        _configurations.add(Configurations.getServerDefault(getServer()).toArray());
    }

    /* ------------------------------------------------------------ */
    @Override
    public String toString()
    {
        if (_war!=null)
        {
            String war=_war;
            if (war.indexOf("/webapps/")>=0)
                war=war.substring(war.indexOf("/webapps/")+8);
            return super.toString()+"{"+war+"}";
        }
        return super.toString();
    }
    
    /* ------------------------------------------------------------ */
    @Override
    public void dump(Appendable out, String indent) throws IOException
    {
        List<String> system_classes=null;
        if (_systemClasses!=null)
        {
            system_classes=new ArrayList<>(_systemClasses);
            Collections.sort(system_classes);
        }
        
        List<String> server_classes=null;
        if (_serverClasses!=null)
        {
            server_classes=new ArrayList<>(_serverClasses);
            Collections.sort(server_classes);
        }
        
        dumpBeans(out,indent,
            Collections.singletonList(new ClassLoaderDump(getClassLoader())),
            Collections.singletonList(new DumpableCollection("Systemclasses "+this,system_classes)),
            Collections.singletonList(new DumpableCollection("Serverclasses "+this,server_classes)),
            Collections.singletonList(new DumpableCollection("Configurations "+this,_configurations.getConfigurations())),
            Collections.singletonList(new DumpableCollection("Handler attributes "+this,((AttributesMap)getAttributes()).getAttributeEntrySet())),
            Collections.singletonList(new DumpableCollection("Context attributes "+this,((Context)getServletContext()).getAttributeEntrySet())),
            Collections.singletonList(new DumpableCollection("Initparams "+this,getInitParams().entrySet()))
            );
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configuration class names.  If setConfigurations is not called
     * these classes are used to create a configurations array.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        if (isStarted())
            throw new IllegalStateException();
        _configurations.set(configurations);
    }

    /* ------------------------------------------------------------ */
    public void setConfigurationClasses(List<String> configurations)
    {
        setConfigurationClasses(configurations.toArray(new String[configurations.size()]));
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configurations to set.
     */
    public void setConfigurations(Configuration[] configurations)
    {
        if (isStarted())
            throw new IllegalStateException();
        _configurations.set(configurations);
    }

    /* ------------------------------------------------------------ */
    public void addConfiguration(Configuration... configuration)
    {
        if (isStarted())
            throw new IllegalStateException();
        loadConfigurations();
        _configurations.add(configuration);
    }

    /* ------------------------------------------------------------ */
    public <T> T getConfiguration(Class<? extends T> configClass)
    {
        loadConfigurations();
        for (Configuration configuration : _configurations)
            if (configClass.isAssignableFrom(configuration.getClass()))
                return (T)configuration;
        return null;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The default descriptor is a web.xml format file that is applied to the context before the standard WEB-INF/web.xml
     * @param defaultsDescriptor The defaultsDescriptor to set.
     */
    public void setDefaultsDescriptor(String defaultsDescriptor)
    {
        _defaultsDescriptor = defaultsDescriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     * @param overrideDescriptor The overrideDescritpor to set.
     */
    public void setOverrideDescriptor(String overrideDescriptor)
    {
        _overrideDescriptors.clear();
        _overrideDescriptors.add(overrideDescriptor);
    }

    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     * @param overrideDescriptors The overrideDescriptors (file or URL) to set.
     */
    public void setOverrideDescriptors(List<String> overrideDescriptors)
    {
        _overrideDescriptors.clear();
        _overrideDescriptors.addAll(overrideDescriptors);
    }

    /* ------------------------------------------------------------ */
    /**
     * The override descriptor is a web.xml format file that is applied to the context after the standard WEB-INF/web.xml
     * @param overrideDescriptor The overrideDescriptor (file or URL) to add.
     */
    public void addOverrideDescriptor(String overrideDescriptor)
    {
        _overrideDescriptors.add(overrideDescriptor);
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists.
     */
    @ManagedAttribute(value="standard web.xml descriptor", readonly=true)
    public String getDescriptor()
    {
        return _descriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param descriptor the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists.
     */
    public void setDescriptor(String descriptor)
    {
        _descriptor=descriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param distributable The distributable to set.
     */
    public void setDistributable(boolean distributable)
    {
        this._distributable = distributable;
    }

    /* ------------------------------------------------------------ */
    @Override
    public void setEventListeners(EventListener[] eventListeners)
    {
        if (_sessionHandler!=null)
            _sessionHandler.clearEventListeners();

        super.setEventListeners(eventListeners);
    }

    /* ------------------------------------------------------------ */
    /** Add EventListener
     * Convenience method that calls {@link #setEventListeners(EventListener[])}
     * @param listener the listener to add
     */
    @Override
    public void addEventListener(EventListener listener)
    {
        super.addEventListener(listener);
        if ((listener instanceof HttpSessionActivationListener)
            || (listener instanceof HttpSessionAttributeListener)
            || (listener instanceof HttpSessionBindingListener)
            || (listener instanceof HttpSessionListener)
            || (listener instanceof HttpSessionIdListener))
        {
            if (_sessionHandler!=null)
                _sessionHandler.addEventListener(listener);
        }
    }
    
    @Override
    public void removeEventListener(EventListener listener)
    {
        super.removeEventListener(listener);
        if ((listener instanceof HttpSessionActivationListener)
            || (listener instanceof HttpSessionAttributeListener)
            || (listener instanceof HttpSessionBindingListener)
            || (listener instanceof HttpSessionListener)
            || (listener instanceof HttpSessionIdListener))
        {
            if (_sessionHandler!=null)
                _sessionHandler.removeEventListener(listener);
        }
        
    }


    /* ------------------------------------------------------------ */
    /**
     * @param extractWAR True if war files are extracted
     */
    public void setExtractWAR(boolean extractWAR)
    {
        _extractWAR = extractWAR;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param copy True if the webdir is copied (to allow hot replacement of jars)
     */
    public void setCopyWebDir(boolean copy)
    {
        _copyDir = copy;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param copyWebInf True if the web-inf lib and classes directories are copied (to allow hot replacement of jars on windows)
     */
    public void setCopyWebInf(boolean copyWebInf)
    {
        _copyWebInf = copyWebInf;
    }

    /* ------------------------------------------------------------ */
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

    /* ------------------------------------------------------------ */
    /**
     * @param permissions The permissions to set.
     */
    public void setPermissions(PermissionCollection permissions)
    {
        _permissions = permissions;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the context white list
     *
     * In certain circumstances you want may want to deny access of one webapp from another
     * when you may not fully trust the webapp.  Setting this white list will enable a
     * check when a servlet called {@link org.eclipse.jetty.servlet.ServletContextHandler.Context#getContext(String)}, validating that the uriInPath
     * for the given webapp has been declaratively allows access to the context.
     * 
     * @param contextWhiteList the whitelist of contexts for {@link org.eclipse.jetty.servlet.ServletContextHandler.Context#getContext(String)} 
     */
    public void setContextWhiteList(String... contextWhiteList)
    {
        _contextWhiteList = contextWhiteList;
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the server classes patterns.
     * <p>
     * Server classes/packages are classes used to implement the server and are hidden
     * from the context.  If the context needs to load these classes, it must have its
     * own copy of them in WEB-INF/lib or WEB-INF/classes.
     * A {@link ClasspathPattern} is used to match the server classes.
     * @deprecated use {@link #setServerClasspathPattern(ClasspathPattern)} or {@link Configuration}s
     * @param serverClasses The serverClasses to set.
     */
    public void setServerClasses(String... serverClasses)
    {
        setServerClasspathPattern(new ClasspathPattern(serverClasses));
    }

    /* ------------------------------------------------------------ */
    /**
     * Set the system classes patterns.
     * <p>
     * System classes/packages are classes provided by the JVM and that
     * cannot be replaced by classes of the same name from WEB-INF,
     * regardless of the value of {@link #setParentLoaderPriority(boolean)}.
     * A {@link ClasspathPattern} is used to match the system classes.
     * @deprecated use {@link #setSystemClasspathPattern(ClasspathPattern)} or {@link Configuration}s
     * @param systemClasses The systemClasses to set.
     */
    public void setSystemClasses(String... systemClasses)
    {
        setSystemClasspathPattern(new ClasspathPattern(systemClasses));
    }


    /* ------------------------------------------------------------ */
    /** Set temporary directory for context.
     * The javax.servlet.context.tempdir attribute is also set.
     * @param dir Writable temporary directory.
     */
    public void setTempDirectory(File dir)
    {
        if (isStarted())
            throw new IllegalStateException("Started");

        if (dir!=null)
        {
            try{dir=new File(dir.getCanonicalPath());}
            catch (IOException e){LOG.warn(Log.EXCEPTION,e);}
        }

        _tmpDir=dir;
        setAttribute(TEMPDIR,_tmpDir);            
    }

    /* ------------------------------------------------------------ */
    @ManagedAttribute(value="temporary directory location", readonly=true)
    public File getTempDirectory ()
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
    
    
    /* ------------------------------------------------------------ */
    /**
     * @param war The war to set as a file name or URL
     */
    public void setWar(String war)
    {
        _war = war;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     */
    @Override
    @ManagedAttribute(value="extra classpath for context classloader", readonly=true)
    public String getExtraClasspath()
    {
        return _extraClasspath;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param extraClasspath Comma or semicolon separated path of filenames or URLs
     * pointing to directories or jar files. Directories should end
     * with '/'.
     */
    public void setExtraClasspath(String extraClasspath)
    {
        _extraClasspath=extraClasspath;
    }

    /* ------------------------------------------------------------ */
    public boolean isLogUrlOnStart()
    {
        return _logUrlOnStart;
    }

    /* ------------------------------------------------------------ */
    /**
     * Sets whether or not the web app name and URL is logged on startup
     *
     * @param logOnStart whether or not the log message is created
     */
    public void setLogUrlOnStart(boolean logOnStart)
    {
        this._logUrlOnStart = logOnStart;
    }

    /* ------------------------------------------------------------ */
    public boolean isAllowDuplicateFragmentNames()
    {
        return _allowDuplicateFragmentNames;
    }

    /* ------------------------------------------------------------ */
    public void setAllowDuplicateFragmentNames(boolean allowDuplicateFragmentNames)
    {
        _allowDuplicateFragmentNames = allowDuplicateFragmentNames;
    }

    /* ------------------------------------------------------------ */
    public void setThrowUnavailableOnStartupException (boolean throwIfStartupException) {
        _throwUnavailableOnStartupException = throwIfStartupException;
    }

    /* ------------------------------------------------------------ */
    public boolean isThrowUnavailableOnStartupException () {
        return _throwUnavailableOnStartupException;
    }

    /* ------------------------------------------------------------ */
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
    
    
    /* ------------------------------------------------------------ */
    @Override
    protected void stopContext() throws Exception
    {
        super.stopContext();
        try
        {
            for (int i=_configurations.size();i-->0;)
                _configurations.get(i).deconfigure(this);

            if (_metadata != null)
                _metadata.clear();
            _metadata=new MetaData();

        }
        finally
        {
            if (_ownClassLoader)
            {
                ClassLoader loader = getClassLoader();
                if (loader != null && loader instanceof URLClassLoader)
                    ((URLClassLoader)loader).close(); 
                setClassLoader(null);
            }

            setAvailable(true);
            _unavailableException=null;
        }
    }
    
    /* ------------------------------------------------------------ */    
    @Override
    public Set<String> setServletSecurity(Dynamic registration, ServletSecurityElement servletSecurityElement)
    {
        Set<String> unchangedURLMappings = new HashSet<String>();
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

        Collection<String> pathMappings = registration.getMappings();
        if (pathMappings != null)
        {
            ConstraintSecurityHandler.createConstraint(registration.getName(), servletSecurityElement);

            for (String pathSpec:pathMappings)
            {
                Origin origin = getMetaData().getOrigin("constraint.url."+pathSpec);
               
                switch (origin)
                {
                    case NotSet:
                    {
                        //No mapping for this url already established
                        List<ConstraintMapping> mappings = ConstraintSecurityHandler.createConstraintsWithMappingsForPath(registration.getName(), pathSpec, servletSecurityElement);
                        for (ConstraintMapping m:mappings)
                            ((ConstraintAware)getSecurityHandler()).addConstraintMapping(m);
                        ((ConstraintAware)getSecurityHandler()).checkPathsWithUncoveredHttpMethods();
                        getMetaData().setOriginAPI("constraint.url."+pathSpec);
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
                }
            }
        }
        
        return unchangedURLMappings;
    }



    /* ------------------------------------------------------------ */
    public class Context extends ServletContextHandler.Context
    {
       
        /* ------------------------------------------------------------ */
        @Override
        public void checkListener(Class<? extends EventListener> listener) throws IllegalStateException
        {
            try
            {
                super.checkListener(listener);
            }
            catch (IllegalArgumentException e)
            {
                //not one of the standard servlet listeners, check our extended session listener types
                boolean ok = false;
                for (Class<?> l:SessionHandler.SESSION_LISTENER_TYPES)
                {
                    if (l.isAssignableFrom(listener))
                    {
                        ok = true;
                        break;
                    }
                }
                if (!ok)
                    throw new IllegalArgumentException("Inappropriate listener type "+listener.getName());
            }
        }

        /* ------------------------------------------------------------ */
        @Override
        public URL getResource(String path) throws MalformedURLException
        {
            Resource resource=WebAppContext.this.getResource(path);
            if (resource==null || !resource.exists())
                return null;

            // Should we go to the original war?
            if (resource.isDirectory() && resource instanceof ResourceCollection && !WebAppContext.this.isExtractWAR())
            {
                Resource[] resources = ((ResourceCollection)resource).getResources();
                for (int i=resources.length;i-->0;)
                {
                    if (resources[i].getName().startsWith("jar:file"))
                        return resources[i].getURI().toURL();
                }
            }

            return resource.getURI().toURL();
        }

        /* ------------------------------------------------------------ */
        @Override
        public ServletContext getContext(String uripath)
        {
            ServletContext servletContext = super.getContext(uripath);

            if ( servletContext != null && _contextWhiteList != null )
            {
                for ( String context : _contextWhiteList )
                {
                    if ( context.equals(uripath) )
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
    }

    /* ------------------------------------------------------------ */
    public MetaData getMetaData()
    {
        return _metadata;
    }

    /* ------------------------------------------------------------ */
    public static void addServerClasses(Server server,String... pattern )
    {
        if (pattern == null || pattern.length == 0)
            return;
        
        // look for a Server attribute with the list of Server classes
        // to apply to every web application. If not present, use our defaults.        
        Object o = server.getAttribute(SERVER_SRV_CLASSES);
        if (o instanceof ClasspathPattern)
        {
            ((ClasspathPattern)o).add(pattern);
            return;
        }
        
        String[] server_classes;
        if (o instanceof String[])
            server_classes = (String[])o;
        else
            server_classes = __dftServerClasses.getPatterns();
        int l = server_classes.length;
        server_classes = Arrays.copyOf(server_classes,l+pattern.length);
        System.arraycopy(pattern,0,server_classes,l,pattern.length);
        server.setAttribute(SERVER_SRV_CLASSES,server_classes);
    }

    /* ------------------------------------------------------------ */
    public static void addSystemClasses(Server server,String... pattern )
    {
        if (pattern == null || pattern.length == 0)
            return;
        
        // look for a Server attribute with the list of System classes
        // to apply to every web application. If not present, use our defaults.
        Object o = server.getAttribute(SERVER_SYS_CLASSES);
        if (o instanceof ClasspathPattern)
        {
            ((ClasspathPattern)o).add(pattern);
            return;
        }
        
        String[] system_classes;
        if (o instanceof String[])
            system_classes = (String[])o;
        else
            system_classes = __dftSystemClasses.getPatterns();
        int l = system_classes.length;
        system_classes = Arrays.copyOf(system_classes,l+pattern.length);
        System.arraycopy(pattern,0,system_classes,l,pattern.length);
        server.setAttribute(SERVER_SYS_CLASSES,system_classes);
    }

}
