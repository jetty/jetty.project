//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.overlays;

import java.io.IOException;
import java.security.PermissionCollection;
import java.util.Map;

import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.server.ResourceCache;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.component.ContainerLifeCycle;
import org.eclipse.jetty.util.component.Destroyable;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.ClasspathPattern;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.xml.XmlConfiguration;

/**
 * A Cloudtide template context.
 * <p>
 * This class is configured by the template.xml files and is used to control the
 * shared resource cache and classloader.
 * <p>
 * This class is an AggregateLifeCycle, so dependent beans may be added to the template and will be started, stopped and destroyed with the template.
 * The template is started after the template.xml file have been applied. It is stopped and destroyed after the last instance using the template is undeployed.
 */
public class TemplateContext extends ContainerLifeCycle implements WebAppClassLoader.Context, Destroyable
{
    private final ClassLoader _libLoader;
    
    private final Resource _baseResource;
    private final ResourceCache _resourceCache;
    private final Server _server;
    private final MimeTypes _mimeTypes;
    private final WebAppClassLoader _webappLoader;
    
    private ClasspathPattern _systemClasses;
    private ClasspathPattern _serverClasses;
    private PermissionCollection _permissions;

    private boolean _parentLoaderPriority;

    private String _extraClasspath;

    private Map<String, Object> _idMap;

    
    public ClassLoader getLibLoader()
    {
        return _libLoader;
    }

    public TemplateContext()
    {
        _server=null;
        _baseResource=null;
        _mimeTypes=new MimeTypes();
        _resourceCache=null;
        _webappLoader=null;
        _libLoader=null;
    }
    
    public TemplateContext(String key, Server server,Resource baseResource, ClassLoader libLoader) throws IOException
    {
        _server=server;
        _baseResource=baseResource;
        _mimeTypes=new MimeTypes();
        _resourceCache=new ResourceCache(null,baseResource,_mimeTypes,false,false);
        
        String[] patterns = (String[])_server.getAttribute(WebAppContext.SERVER_SRV_CLASSES);
        _serverClasses=new ClasspathPattern(patterns==null?WebAppContext.__dftServerClasses:patterns);
        patterns = (String[])_server.getAttribute(WebAppContext.SERVER_SYS_CLASSES);
        _systemClasses=new ClasspathPattern(patterns==null?WebAppContext.__dftSystemClasses:patterns);
        _libLoader=libLoader;
        

        // Is this a webapp or a normal context
        Resource classes=getBaseResource().addPath("WEB-INF/classes/");
        Resource lib=getBaseResource().addPath("WEB-INF/lib/");
        if (classes.exists() && classes.isDirectory() || lib.exists() && lib.isDirectory())
        {
            _webappLoader=new WebAppClassLoader(_libLoader,this);
            _webappLoader.setName(key);
            if (classes.exists())
                _webappLoader.addClassPath(classes);
            if (lib.exists())
                _webappLoader.addJars(lib);            
        }
        else 
            _webappLoader=null;
        
    }

    /* ------------------------------------------------------------ */
    public Resource getBaseResource()
    {
        return _baseResource;
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
    public MimeTypes getMimeTypes()
    {
        return _mimeTypes;
    }

    
    /* ------------------------------------------------------------ */
    public PermissionCollection getPermissions()
    {
        return _permissions;
    }

    /* ------------------------------------------------------------ */
    public ResourceCache getResourceCache()
    {
        return _resourceCache;
    }

    /* ------------------------------------------------------------ */
    public Server getServer()
    {
        return _server;
    }

    /* ------------------------------------------------------------ */
    WebAppClassLoader getWebappLoader()
    {
        return _webappLoader;
    }

    /* ------------------------------------------------------------ */
    public boolean isParentLoaderPriority()
    {
        return _parentLoaderPriority;
    }

    /* ------------------------------------------------------------ */
    public boolean isServerClass(String clazz)
    {
        return _serverClasses.match(clazz);
    }

    /* ------------------------------------------------------------ */
    public boolean isSystemClass(String clazz)
    {
        return _systemClasses.match(clazz);
    }

    /* ------------------------------------------------------------ */
    public Resource newResource(String urlOrPath) throws IOException
    {
        return Resource.newResource(urlOrPath);
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
        _serverClasses = new ClasspathPattern(serverClasses);
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
        _systemClasses = new ClasspathPattern(systemClasses);
    }

    /* ------------------------------------------------------------ */
    public void addSystemClass(String classname)
    {
        _systemClasses.addPattern(classname);
    }

    /* ------------------------------------------------------------ */
    public void addServerClass(String classname)
    {
        _serverClasses.addPattern(classname);
    }
    
    /* ------------------------------------------------------------ */
    public void destroy()
    {
        if (_baseResource!=null)
            _baseResource.release();
        if (_resourceCache!=null)
            _resourceCache.flushCache();
        if(_idMap!=null)
            _idMap.clear();
    }

    /* ------------------------------------------------------------ */
    public void setIdMap(Map<String, Object> idMap)
    {
        _idMap=idMap;
    }

    /* ------------------------------------------------------------ */
    public Map<String, Object> getIdMap()
    {
        return _idMap;
    }
    
    
    
}
