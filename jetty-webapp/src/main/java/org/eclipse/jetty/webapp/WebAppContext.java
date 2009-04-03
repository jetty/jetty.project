// ========================================================================
// Copyright (c) 2004-2009 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.webapp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.security.PermissionCollection;
import java.util.EventListener;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSessionActivationListener;
import javax.servlet.http.HttpSessionAttributeListener;
import javax.servlet.http.HttpSessionBindingListener;
import javax.servlet.http.HttpSessionListener;

import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.HandlerContainer;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.server.handler.ErrorHandler;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.servlet.ErrorPageErrorHandler;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHandler;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.LazyList;
import org.eclipse.jetty.util.Loader;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

/* ------------------------------------------------------------ */
/** Web Application Context Handler.
 * The WebAppContext handler is an extension of ContextHandler that
 * coordinates the construction and configuration of nested handlers:
 * {@link org.eclipse.jetty.security.ConstraintSecurityHandler}, {@link org.eclipse.jetty.server.session.SessionHandler}
 * and {@link org.eclipse.jetty.servlet.ServletHandler}.
 * The handlers are configured by pluggable configuration classes, with
 * the default being  {@link org.eclipse.jetty.server.server.webapp.WebXmlConfiguration} and 
 * {@link org.eclipse.jetty.server.server.webapp.JettyWebXmlConfiguration}.
 *      
 * @org.apache.xbean.XBean description="Creates a servlet web application at a given context from a resource base"
 * 
 * 
 *
 */
public class WebAppContext extends ServletContextHandler
{   
    public static final String TEMPDIR = "javax.servlet.context.tempdir";
    public final static String WEB_DEFAULTS_XML="org/eclipse/jetty/webapp/webdefault.xml";
    public final static String ERROR_PAGE="org.eclipse.jetty.server.error_page";
    
    private static String[] __dftConfigurationClasses =  
    { 
        "org.eclipse.jetty.webapp.WebInfConfiguration", 
        "org.eclipse.jetty.webapp.WebXmlConfiguration", 
        "org.eclipse.jetty.webapp.JettyWebXmlConfiguration",
        "org.eclipse.jetty.webapp.TagLibConfiguration" 
    } ;
    private String[] _configurationClasses=__dftConfigurationClasses;
    private Configuration[] _configurations;
    private String _defaultsDescriptor=WEB_DEFAULTS_XML;
    private String _descriptor=null;
    private String _overrideDescriptor=null;
    private boolean _distributable=false;
    private boolean _extractWAR=true;
    private boolean _copyDir=false;
    private boolean _logUrlOnStart =false;
    private boolean _parentLoaderPriority= Boolean.getBoolean("org.eclipse.jetty.server.webapp.parentLoaderPriority");
    private PermissionCollection _permissions;
    private String[] _systemClasses = {
            "java.",
            "javax.servlet.",
            "javax.xml.",
            "org.xml.",
            "org.w3c.", 
            "org.apache.commons.logging.", 
            "org.apache.log4j.",
            "org.eclipse.jetty.servlet.",      // webapp cannot change default servlets
            "org.eclipse.jetty.continuation.", // webapp cannot change continuation classes
            "org.eclipse.jetty.naming."        // webapp cannot change naming classes
            };
    private String[] _serverClasses = {
            "-org.eclipse.jetty.jndi.",       // don't hide naming classes
            "-org.eclipse.jetty.continuation.", // don't hide continuation classes
            "-org.eclipse.jetty.plus.jaas.",    // don't hide jaas modules
            "org.eclipse.jetty.",               // hide rest of jetty classes
            "org.slf4j."                        // hide slf4j
            }; 
    private File _tmpDir;
    private boolean _isExistingTmpDir;
    private String _war;
    private String _extraClasspath;
    private Throwable _unavailableException;
    
    private transient Map _resourceAliases;
    private transient boolean _ownClassLoader=false;
    private transient boolean _unavailable;

    public static ContextHandler getCurrentWebAppContext()
    {
        ContextHandler.Context context=ContextHandler.getCurrentContext();
        if (context!=null)
        {
            ContextHandler handler = context.getContextHandler();
            if (handler instanceof WebAppContext)
                return (ContextHandler)handler;
        }
        return null;   
    }
    
    /* ------------------------------------------------------------ */
    public WebAppContext()
    {
        super(SESSIONS|SECURITY);
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(String webApp,String contextPath)
    {
        super(null,contextPath,SESSIONS|SECURITY);
        setContextPath(contextPath);
        setWar(webApp);
        setErrorHandler(new ErrorPageErrorHandler());
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param parent The parent HandlerContainer.
     * @param contextPath The context path
     * @param webApp The URL or filename of the webapp directory or war file.
     */
    public WebAppContext(HandlerContainer parent, String webApp, String contextPath)
    {
        super(parent,contextPath,SESSIONS|SECURITY);
        setWar(webApp);
        setErrorHandler(new ErrorPageErrorHandler());
    }

    /* ------------------------------------------------------------ */
    /**
     */
    public WebAppContext(SessionHandler sessionHandler, SecurityHandler securityHandler, ServletHandler servletHandler, ErrorHandler errorHandler)
    {
        super(null,sessionHandler,securityHandler,servletHandler,errorHandler);
        
        setErrorHandler(errorHandler!=null?errorHandler:new ErrorPageErrorHandler());
    }    

    /* ------------------------------------------------------------ */
    /**
     * @param servletContextName The servletContextName to set.
     */
    public void setDisplayName(String servletContextName)
    {
        super.setDisplayName(servletContextName);
        ClassLoader cl = getClassLoader();
        if (cl!=null && cl instanceof WebAppClassLoader)
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
//    public SecurityHandler getConstraintsSecurityHandler()
//    {
//        return (ConstraintsSecurityHandler)getSecurityHandler();
//    }

    
    /* ------------------------------------------------------------ */
    /** Set Resource Alias.
     * Resource aliases map resource uri's within a context.
     * They may optionally be used by a handler when looking for
     * a resource.  
     * @param alias 
     * @param uri 
     */
    public void setResourceAlias(String alias, String uri)
    {
        if (_resourceAliases == null)
            _resourceAliases= new HashMap(5);
        _resourceAliases.put(alias, uri);
    }

    /* ------------------------------------------------------------ */
    public Map getResourceAliases()
    {
        if (_resourceAliases == null)
            return null;
        return _resourceAliases;
    }
    
    /* ------------------------------------------------------------ */
    public void setResourceAliases(Map map)
    {
        _resourceAliases = map;
    }
    
    /* ------------------------------------------------------------ */
    public String getResourceAlias(String alias)
    {
        if (_resourceAliases == null)
            return null;
        return (String)_resourceAliases.get(alias);
    }

    /* ------------------------------------------------------------ */
    public String removeResourceAlias(String alias)
    {
        if (_resourceAliases == null)
            return null;
        return (String)_resourceAliases.remove(alias);
    }

    /* ------------------------------------------------------------ */
    /* (non-Javadoc)
     * @see org.eclipse.jetty.server.server.handler.ContextHandler#setClassLoader(java.lang.ClassLoader)
     */
    public void setClassLoader(ClassLoader classLoader)
    {
        super.setClassLoader(classLoader);
        if (classLoader!=null && classLoader instanceof WebAppClassLoader)
            ((WebAppClassLoader)classLoader).setName(getDisplayName());
    }
    
    /* ------------------------------------------------------------ */
    public Resource getResource(String uriInContext) throws MalformedURLException
    {
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
                Log.ignore(e);
                if (ioe==null)
                    ioe= e;
            }
        }

        if (ioe != null && ioe instanceof MalformedURLException)
            throw (MalformedURLException)ioe;

        return resource;
    }
    

    /* ------------------------------------------------------------ */
    /** 
     * @see org.eclipse.jetty.server.server.handler.ContextHandler#handle(java.lang.String, javax.servlet.http.HttpServletRequest, javax.servlet.http.HttpServletResponse)
     */
    public void handle(String target, HttpServletRequest request, HttpServletResponse response)
    throws IOException, ServletException
    {   
        if (_unavailable)
        {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        }
        else
            super.handle(target, request, response);
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStart()
     */
    protected void doStart() throws Exception
    {
        try
        {
            // Setup configurations 
            loadConfigurations();

            for (int i=0;i<_configurations.length;i++)
                _configurations[i].setWebAppContext(this);

            // Configure classloader
            _ownClassLoader=false;
            if (getClassLoader()==null)
            {
                WebAppClassLoader classLoader = new WebAppClassLoader(this);
                setClassLoader(classLoader);
                _ownClassLoader=true;
            }

            if (Log.isDebugEnabled()) 
            {
                ClassLoader loader = getClassLoader();
                Log.debug("Thread Context class loader is: " + loader);
                loader=loader.getParent();
                while(loader!=null)
                {
                    Log.debug("Parent class loader is: " + loader); 
                    loader=loader.getParent();
                }
            }

            for (int i=0;i<_configurations.length;i++)
                _configurations[i].configureClassLoader();

            getTempDirectory();
            if (_tmpDir!=null && !_isExistingTmpDir && !isTempWorkDirectory())
            {
                File sentinel = new File(_tmpDir, ".active");
                if(!sentinel.exists())
                    sentinel.mkdir();
            }

            super.doStart();

            if (isLogUrlOnStart()) 
                dumpUrl();
        }
        catch (Exception e)
        {
            //start up of the webapp context failed, make sure it is not started
            Log.warn("Failed startup of context "+this, e);
            _unavailableException=e;
            _unavailable = true;
        }
    }

    /* ------------------------------------------------------------ */
    /*
     * Dumps the current web app name and URL to the log
     */
    public void dumpUrl() 
    {
        Connector[] connectors = getServer().getConnectors();
        for (int i=0;i<connectors.length;i++) 
        {
            String connectorName = connectors[i].getName();
            String displayName = getDisplayName();
            if (displayName == null)
                displayName = "WebApp@"+connectors.hashCode();
           
            Log.info(displayName + " at http://" + connectorName + getContextPath());
        }
    }

    /* ------------------------------------------------------------ */
    /* 
     * @see org.eclipse.thread.AbstractLifeCycle#doStop()
     */
    protected void doStop() throws Exception
    {
        super.doStop();

        try
        {
            // Configure classloader
            for (int i=_configurations.length;i-->0;)
                _configurations[i].deconfigureWebApp();
            _configurations=null;
            
            // restore security handler
            if (_securityHandler.getHandler()==null)
            {
                _sessionHandler.setHandler(_securityHandler);
                _securityHandler.setHandler(_servletHandler);
            }
            
            // delete temp directory if we had to create it or if it isn't called work
            if (_tmpDir!=null && !_isExistingTmpDir && !isTempWorkDirectory()) //_tmpDir!=null && !"work".equals(_tmpDir.getName()))
            {
                IO.delete(_tmpDir);
                _tmpDir=null;
            }
        }
        finally
        {
            if (_ownClassLoader)
                setClassLoader(null);
            
            _unavailable = false;
            _unavailableException=null;
        }
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the configurations.
     */
    public String[] getConfigurationClasses()
    {
        return _configurationClasses;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the configurations.
     */
    public Configuration[] getConfigurations()
    {
        return _configurations;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * The default descriptor is a web.xml format file that is applied to the context before the standard WEB-INF/web.xml
     * @return Returns the defaultsDescriptor.
     */
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
        return _overrideDescriptor;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the permissions.
     */
    public PermissionCollection getPermissions()
    {
        return _permissions;
    }
    

    /* ------------------------------------------------------------ */
    /**
     * @see #setServerClasses(String[])
     * @return Returns the serverClasses.
     */
    public String[] getServerClasses()
    {
        return _serverClasses;
    }
    
    
    /* ------------------------------------------------------------ */
    /**
     * @see #setSystemClasses(String[])
     * @return Returns the systemClasses.
     */
    public String[] getSystemClasses()
    {
        return _systemClasses;
    }
    

    
    /* ------------------------------------------------------------ */
    public boolean isServerClass(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);

        String[] server_classes = getServerClasses();
        if (server_classes!=null)
        {
            for (int i=0;i<server_classes.length;i++)
            {
                boolean result=true;
                String c=server_classes[i];
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        return false;
    }

    /* ------------------------------------------------------------ */
    public boolean isSystemClass(String name)
    {
        name=name.replace('/','.');
        while(name.startsWith("."))
            name=name.substring(1);
        String[] system_classes = getSystemClasses();
        if (system_classes!=null)
        {
            for (int i=0;i<system_classes.length;i++)
            {
                boolean result=true;
                String c=system_classes[i];
                
                if (c.startsWith("-"))
                {
                    c=c.substring(1); // TODO cache
                    result=false;
                }
                
                if (c.endsWith("."))
                {
                    if (name.startsWith(c))
                        return result;
                }
                else if (name.equals(c))
                    return result;
            }
        }
        
        return false;
        
    }

    
    /* ------------------------------------------------------------ */
    /**
     * Get a temporary directory in which to unpack the war etc etc.
     * The algorithm for determining this is to check these alternatives
     * in the order shown:
     * 
     * <p>A. Try to use an explicit directory specifically for this webapp:</p>
     * <ol>
     * <li>
     * Iff an explicit directory is set for this webapp, use it. Do NOT set
     * delete on exit.
     * </li>
     * <li>
     * Iff javax.servlet.context.tempdir context attribute is set for
     * this webapp && exists && writeable, then use it. Do NOT set delete on exit.
     * </li>
     * </ol>
     * 
     * <p>B. Create a directory based on global settings. The new directory 
     * will be called "Jetty_"+host+"_"+port+"__"+context+"_"+virtualhost
     * Work out where to create this directory:
     * <ol>
     * <li>
     * Iff $(jetty.home)/work exists create the directory there. Do NOT
     * set delete on exit. Do NOT delete contents if dir already exists.
     * </li>
     * <li>
     * Iff WEB-INF/work exists create the directory there. Do NOT set
     * delete on exit. Do NOT delete contents if dir already exists.
     * </li>
     * <li>
     * Else create dir in $(java.io.tmpdir). Set delete on exit. Delete
     * contents if dir already exists.
     * </li>
     * </ol>
     * 
     * @return
     */
    public File getTempDirectory()
    {
        if (_tmpDir!=null && _tmpDir.isDirectory() && _tmpDir.canWrite())
            return _tmpDir;

        // Initialize temporary directory
        //
        // I'm afraid that this is very much black magic.
        // but if you can think of better....
        Object t = getAttribute(TEMPDIR);

        if (t!=null && (t instanceof File))
        {
            _tmpDir=(File)t;
            if (_tmpDir.isDirectory() && _tmpDir.canWrite())
                return _tmpDir;
        }

        if (t!=null && (t instanceof String))
        {
            try
            {
                _tmpDir=new File((String)t);

                if (_tmpDir.isDirectory() && _tmpDir.canWrite())
                {
                    if(Log.isDebugEnabled())Log.debug("Converted to File "+_tmpDir+" for "+this);
                    setAttribute(TEMPDIR,_tmpDir);
                    return _tmpDir;
                }
            }
            catch(Exception e)
            {
                Log.warn(Log.EXCEPTION,e);
            }
        }

        // No tempdir so look for a work directory to use as tempDir base
        File work=null;
        try
        {
            File w=new File(System.getProperty("jetty.home"),"work");
            if (w.exists() && w.canWrite() && w.isDirectory())
                work=w;
            else if (getBaseResource()!=null)
            {
                Resource web_inf = getWebInf();
                if (web_inf !=null && web_inf.exists())
                {
                    w=new File(web_inf.getFile(),"work");
                    if (w.exists() && w.canWrite() && w.isDirectory())
                        work=w;
                }
            }
        }
        catch(Exception e)
        {
            Log.ignore(e);
        }

        // No tempdir set so make one!
        try
        {
           
           String temp = getCanonicalNameForWebAppTmpDir();
            
            if (work!=null)
                _tmpDir=new File(work,temp);
            else
            {
                _tmpDir=new File(System.getProperty("java.io.tmpdir"),temp);
                
                if (_tmpDir.exists())
                {
                    if(Log.isDebugEnabled())Log.debug("Delete existing temp dir "+_tmpDir+" for "+this);
                    if (!IO.delete(_tmpDir))
                    {
                        if(Log.isDebugEnabled())Log.debug("Failed to delete temp dir "+_tmpDir);
                    }
                
                    if (_tmpDir.exists())
                    {
                        String old=_tmpDir.toString();
                        _tmpDir=File.createTempFile(temp+"_","");
                        if (_tmpDir.exists())
                            _tmpDir.delete();
                        Log.warn("Can't reuse "+old+", using "+_tmpDir);
                    }
                }
            }

            if (!_tmpDir.exists())
                _tmpDir.mkdir();
            
            //if not in a dir called "work" then we want to delete it on jvm exit
            if (!isTempWorkDirectory())
                _tmpDir.deleteOnExit();
            if(Log.isDebugEnabled())Log.debug("Created temp dir "+_tmpDir+" for "+this);
        }
        catch(Exception e)
        {
            _tmpDir=null;
            Log.ignore(e);
        }

        if (_tmpDir==null)
        {
            try{
                // that didn't work, so try something simpler (ish)
                _tmpDir=File.createTempFile("JettyContext","");
                if (_tmpDir.exists())
                    _tmpDir.delete();
                _tmpDir.mkdir();
                _tmpDir.deleteOnExit();
                if(Log.isDebugEnabled())Log.debug("Created temp dir "+_tmpDir+" for "+this);
            }
            catch(IOException e)
            {
                Log.warn("tmpdir",e); System.exit(1);
            }
        }

        setAttribute(TEMPDIR,_tmpDir);
        return _tmpDir;
    }
    
    /**
     * Check if the _tmpDir itself is called "work", or if the _tmpDir
     * is in a directory called "work".
     * @return
     */
    public boolean isTempWorkDirectory ()
    {
        if (_tmpDir == null)
            return false;
        if (_tmpDir.getName().equalsIgnoreCase("work"))
            return true;
        File t = _tmpDir.getParentFile();
        if (t == null)
            return false;
        return (t.getName().equalsIgnoreCase("work"));
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the war as a file or URL string (Resource)
     */
    public String getWar()
    {
        if (_war==null)
            _war=getResourceBase();
        return _war;
    }

    /* ------------------------------------------------------------ */
    public Resource getWebInf() throws IOException
    {
        resolveWebApp();

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
    public boolean isDistributable()
    {
        return _distributable;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return Returns the extractWAR.
     */
    public boolean isExtractWAR()
    {
        return _extractWAR;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return True if the webdir is copied (to allow hot replacement of jars)
     */
    public boolean isCopyWebDir()
    {
        return _copyDir;
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @return Returns the java2compliant.
     */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }
    
    /* ------------------------------------------------------------ */
    protected void loadConfigurations() 
    	throws Exception
    {
        if (_configurations!=null)
            return;
        if (_configurationClasses==null)
            _configurationClasses=__dftConfigurationClasses;
        
        _configurations = new Configuration[_configurationClasses.length];
        for (int i=0;i<_configurations.length;i++)
        {
            _configurations[i]=(Configuration)Loader.loadClass(this.getClass(), _configurationClasses[i]).newInstance();
        }
    }
    
    /* ------------------------------------------------------------ */
    protected boolean isProtectedTarget(String target)
    {
        while (target.startsWith("//"))
            target=URIUtil.compactPath(target);
         
        return StringUtil.startsWithIgnoreCase(target, "/web-inf") || StringUtil.startsWithIgnoreCase(target, "/meta-inf");
    }
    

    /* ------------------------------------------------------------ */
    public String toString()
    {
        return this.getClass().getName()+"@"+Integer.toHexString(hashCode())+"{"+getContextPath()+","+(_war==null?getResourceBase():_war)+"}";
    }
    
    /* ------------------------------------------------------------ */
    /** Resolve Web App directory
     * If the BaseResource has not been set, use the war resource to
     * derive a webapp resource (expanding WAR if required).
     */
    protected void resolveWebApp() throws IOException
    {
        Resource web_app = super.getBaseResource();
        if (web_app == null)
        {
            if (_war==null || _war.length()==0)
                _war=getResourceBase();
            
            // Set dir or WAR
            web_app= newResource(_war);

            // Accept aliases for WAR files
            if (web_app.getAlias() != null)
            {
                Log.debug(web_app + " anti-aliased to " + web_app.getAlias());
                web_app= newResource(web_app.getAlias());
            }

            if (Log.isDebugEnabled())
                Log.debug("Try webapp=" + web_app + ", exists=" + web_app.exists() + ", directory=" + web_app.isDirectory());

            // Is the WAR usable directly?
            if (web_app.exists() && !web_app.isDirectory() && !web_app.toString().startsWith("jar:"))
            {
                // No - then lets see if it can be turned into a jar URL.
                Resource jarWebApp= newResource("jar:" + web_app + "!/");
                if (jarWebApp.exists() && jarWebApp.isDirectory())
                {
                    web_app= jarWebApp;
                }
            }

            // If we should extract or the URL is still not usable
            if (web_app.exists()  && (
               (_copyDir && web_app.getFile()!= null && web_app.getFile().isDirectory()) 
               ||
               (_extractWAR && web_app.getFile()!= null && !web_app.getFile().isDirectory())
               ||
               (_extractWAR && web_app.getFile() == null)
               ||
               !web_app.isDirectory()
               ))
            {
                // Then extract it if necessary.
                File extractedWebAppDir= new File(getTempDirectory(), "webapp");
                
                if (web_app.getFile()!=null && web_app.getFile().isDirectory())
                {
                    // Copy directory
                    Log.info("Copy " + web_app.getFile() + " to " + extractedWebAppDir);
                    IO.copyDir(web_app.getFile(),extractedWebAppDir);
                }
                else
                {
                    if (!extractedWebAppDir.exists())
                    {
                        //it hasn't been extracted before so extract it
                        extractedWebAppDir.mkdir();
                        Log.info("Extract " + _war + " to " + extractedWebAppDir);
                        JarResource.extract(web_app, extractedWebAppDir, false);
                    }
                    else
                    {
                        //only extract if the war file is newer
                        if (web_app.lastModified() > extractedWebAppDir.lastModified())
                        {
                            extractedWebAppDir.delete();
                            extractedWebAppDir.mkdir();
                            Log.info("Extract " + _war + " to " + extractedWebAppDir);
                            JarResource.extract(web_app, extractedWebAppDir, false);
                        }
                    }
                }
                
                web_app= Resource.newResource(extractedWebAppDir.getCanonicalPath());

            }

            // Now do we have something usable?
            if (!web_app.exists() || !web_app.isDirectory())
            {
                Log.warn("Web application not found " + _war);
                throw new java.io.FileNotFoundException(_war);
            }

            if (Log.isDebugEnabled())
                Log.debug("webapp=" + web_app);

            // ResourcePath
            super.setBaseResource(web_app);
        }
    }
    

    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configuration class names.  If setConfigurations is not called
     * these classes are used to create a configurations array.
     */
    public void setConfigurationClasses(String[] configurations)
    {
        _configurationClasses = configurations==null?null:(String[])configurations.clone();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * @param configurations The configurations to set.
     */
    public void setConfigurations(Configuration[] configurations)
    {
        _configurations = configurations==null?null:(Configuration[])configurations.clone();
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
        _overrideDescriptor = overrideDescriptor;
    }

    /* ------------------------------------------------------------ */
    /**
     * @return the web.xml descriptor to use. If set to null, WEB-INF/web.xml is used if it exists.
     */
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
    public void setEventListeners(EventListener[] eventListeners)
    {
        if (_sessionHandler!=null)
            _sessionHandler.clearEventListeners();
            
        super.setEventListeners(eventListeners);
      
        for (int i=0; eventListeners!=null && i<eventListeners.length;i ++)
        {
            EventListener listener = eventListeners[i];
            
            if ((listener instanceof HttpSessionActivationListener)
                            || (listener instanceof HttpSessionAttributeListener)
                            || (listener instanceof HttpSessionBindingListener)
                            || (listener instanceof HttpSessionListener))
            {
                if (_sessionHandler!=null)
                    _sessionHandler.addEventListener(listener);
            }
            
        }
    }

    /* ------------------------------------------------------------ */
    /** Add EventListener
     * Conveniance method that calls {@link #setEventListeners(EventListener[])}
     * @param listener
     */
    public void addEventListener(EventListener listener)
    {
        setEventListeners((EventListener[])LazyList.addToArray(getEventListeners(), listener, EventListener.class));   
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
     * 
     * @param copy True if the webdir is copied (to allow hot replacement of jars)
     */
    public void setCopyWebDir(boolean copy)
    {
        _copyDir = copy;
    }

    /* ------------------------------------------------------------ */
    /**
     * @param java2compliant The java2compliant to set.
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
     * Set the server classes patterns.
     * <p>
     * Server classes/packages are classes used to implement the server and are hidden
     * from the context.  If the context needs to load these classes, it must have its
     * own copy of them in WEB-INF/lib or WEB-INF/classes. 
     * A class pattern is a string of one of the forms:<dl>
     * <dt>org.package.Classname</dt><dd>Match a specific class</dd>
     * <dt>org.package.</dt><dd>Match a specific package hierarchy</dd>
     * <dt>-org.package.Classname</dt><dd>Exclude a specific class</dd>
     * <dt>-org.package.</dt><dd>Exclude a specific package hierarchy</dd>
     * </dl>
     * @param serverClasses The serverClasses to set.
     */
    public void setServerClasses(String[] serverClasses) 
    {
        _serverClasses = serverClasses==null?null:(String[])serverClasses.clone();
    }
    
    /* ------------------------------------------------------------ */
    /**
     * Set the system classes patterns.
     * <p>
     * System classes/packages are classes provided by the JVM and that
     * cannot be replaced by classes of the same name from WEB-INF,
     * regardless of the value of {@link #setParentLoaderPriority(boolean)}.
     * A class pattern is a string of one of the forms:<dl>
     * <dt>org.package.Classname</dt><dd>Match a specific class</dd>
     * <dt>org.package.</dt><dd>Match a specific package hierarchy</dd>
     * <dt>-org.package.Classname</dt><dd>Exclude a specific class</dd>
     * <dt>-org.package.</dt><dd>Exclude a specific package hierarchy</dd>
     * </dl>
     * @param systemClasses The systemClasses to set.
     */
    public void setSystemClasses(String[] systemClasses)
    {
        _systemClasses = systemClasses==null?null:(String[])systemClasses.clone();
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
            catch (IOException e){Log.warn(Log.EXCEPTION,e);}
        }

        if (dir!=null && !dir.exists())
        {
            dir.mkdir();
            dir.deleteOnExit();
        }
        else if (dir != null)
            _isExistingTmpDir = true;

        if (dir!=null && ( !dir.exists() || !dir.isDirectory() || !dir.canWrite()))
            throw new IllegalArgumentException("Bad temp directory: "+dir);

        _tmpDir=dir;
        setAttribute(TEMPDIR,_tmpDir);
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
    protected void startContext()
        throws Exception
    {
        // Configure defaults
        for (int i=0;i<_configurations.length;i++)
            _configurations[i].configureDefaults();
        
        // Is there a WEB-INF work directory
        Resource web_inf=getWebInf();
        if (web_inf!=null)
        {
            Resource work= web_inf.addPath("work");
            if (work.exists()
                            && work.isDirectory()
                            && work.getFile() != null
                            && work.getFile().canWrite()
                            && getAttribute(TEMPDIR) == null)
                setAttribute(TEMPDIR, work.getFile());
        }
        
        // Configure webapp
        for (int i=0;i<_configurations.length;i++)
            _configurations[i].configureWebApp();

        
        super.startContext();
    }
    
    /**
     * Create a canonical name for a webapp tmp directory.
     * The form of the name is:
     *  "Jetty_"+host+"_"+port+"__"+resourceBase+"_"+context+"_"+virtualhost+base36 hashcode of whole string
     *  
     *  host and port uniquely identify the server
     *  context and virtual host uniquely identify the webapp
     * @return
     */
    private String getCanonicalNameForWebAppTmpDir ()
    {
        StringBuffer canonicalName = new StringBuffer();
        canonicalName.append("Jetty");
       
        //get the host and the port from the first connector 
        Connector[] connectors = getServer().getConnectors();
        
        
        //Get the host
        canonicalName.append("_");
        String host = (connectors==null||connectors[0]==null?"":connectors[0].getHost());
        if (host == null)
            host = "0.0.0.0";
        canonicalName.append(host.replace('.', '_'));
        
        //Get the port
        canonicalName.append("_");
        //try getting the real port being listened on
        int port = (connectors==null||connectors[0]==null?0:connectors[0].getLocalPort());
        //if not available (eg no connectors or connector not started), 
        //try getting one that was configured.
        if (port < 0)
            port = connectors[0].getPort();
        canonicalName.append(port);

       
        //Resource  base
        canonicalName.append("_");
        try
        {
            Resource resource = super.getBaseResource();
            if (resource == null)
            {
                if (_war==null || _war.length()==0)
                    resource=newResource(getResourceBase());
                
                // Set dir or WAR
                resource= newResource(_war);
            }
                
            String tmp = URIUtil.decodePath(resource.getURL().getPath());
            if (tmp.endsWith("/"))
                tmp = tmp.substring(0, tmp.length()-1);
            if (tmp.endsWith("!"))
                tmp = tmp.substring(0, tmp.length() -1);
            //get just the last part which is the filename
            int i = tmp.lastIndexOf("/");
            canonicalName.append(tmp.substring(i+1, tmp.length()));
        }
        catch (Exception e)
        {
            Log.warn("Can't generate resourceBase as part of webapp tmp dir name", e);
        }
            
        //Context name
        canonicalName.append("_");
        String contextPath = getContextPath();
        contextPath=contextPath.replace('/','_');
        contextPath=contextPath.replace('\\','_');
        canonicalName.append(contextPath);
        
        //Virtual host (if there is one)
        canonicalName.append("_");
        String[] vhosts = getVirtualHosts();
        canonicalName.append((vhosts==null||vhosts[0]==null?"":vhosts[0]));
        
        //base36 hash of the whole string for uniqueness
        String hash = Integer.toString(canonicalName.toString().hashCode(),36);
        canonicalName.append("_");
        canonicalName.append(hash);
        
        // sanitize
        for (int i=0;i<canonicalName.length();i++)
        {
            char c=canonicalName.charAt(i);
            if (!Character.isJavaIdentifierPart(c))
                canonicalName.setCharAt(i,'.');
        }        
        
        return canonicalName.toString();
    }
}
