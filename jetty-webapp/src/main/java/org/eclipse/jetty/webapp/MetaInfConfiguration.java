//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * MetaInfConfiguration
 * <p>
 *
 * Scan META-INF of jars to find:
 * <ul>
 * <li>tlds</li>
 * <li>web-fragment.xml</li>
 * <li>resources</li>
 * </ul>
 * 
 * The jars which are scanned are:
 * <ol>
 * <li>those from the container classpath whose pattern matched the WebInfConfiguration.CONTAINER_JAR_PATTERN</li>
 * <li>those from WEB-INF/lib</li>
 * </ol>
 */
public class MetaInfConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = Log.getLogger(MetaInfConfiguration.class);

    public static final String USE_CONTAINER_METAINF_CACHE = "org.eclipse.jetty.metainf.useCache";
    public static final boolean DEFAULT_USE_CONTAINER_METAINF_CACHE = true;
    public static final String CACHED_CONTAINER_TLDS = "org.eclipse.jetty.tlds.cache";
    public static final String CACHED_CONTAINER_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES+".cache";
    public static final String CACHED_CONTAINER_RESOURCES = WebInfConfiguration.RESOURCE_DIRS+".cache";
    public static final String METAINF_TLDS = "org.eclipse.jetty.tlds";
    public static final String METAINF_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES;
    public static final String METAINF_RESOURCES = WebInfConfiguration.RESOURCE_DIRS;

    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {        
        boolean useContainerCache = DEFAULT_USE_CONTAINER_METAINF_CACHE;
        Boolean attr = (Boolean)context.getServer().getAttribute(USE_CONTAINER_METAINF_CACHE);
        if (attr != null)
            useContainerCache = attr.booleanValue();
        
        if (LOG.isDebugEnabled()) LOG.debug("{} = {}", USE_CONTAINER_METAINF_CACHE, useContainerCache);
        
        //pre-emptively create empty lists for tlds, fragments and resources as context attributes
        //this signals that this class has been called. This differentiates the case where this class
        //has been called but finds no META-INF data from the case where this class was never called
        if (context.getAttribute(METAINF_TLDS) == null)
            context.setAttribute(METAINF_TLDS, new HashSet<URL>());
        if (context.getAttribute(METAINF_RESOURCES) == null)
            context.setAttribute(METAINF_RESOURCES, new HashSet<Resource>());
        if (context.getAttribute(METAINF_FRAGMENTS) == null)
            context.setAttribute(METAINF_FRAGMENTS, new HashMap<Resource, Resource>());
       
        scanJars(context, context.getMetaData().getContainerResources(), useContainerCache);
        scanJars(context, context.getMetaData().getWebInfJars(), false);
    }

    /**
     * Look into the jars to discover info in META-INF. If useCaches == true, then we will
     * cache the info discovered indexed by the jar in which it was discovered: this speeds
     * up subsequent context deployments.
     * 
     * @param context the context for the scan
     * @param jars the jars resources to scan
     * @param useCaches if true, cache the info discovered
     * @throws Exception if unable to scan the jars
     */
    public void scanJars (final WebAppContext context, Collection<Resource> jars, boolean useCaches)
    throws Exception
    {
        ConcurrentHashMap<Resource, Resource> metaInfResourceCache = null;       
        ConcurrentHashMap<Resource, Resource> metaInfFragmentCache = null;
        ConcurrentHashMap<Resource, Collection<URL>> metaInfTldCache = null;
        if (useCaches)
        {
            metaInfResourceCache = (ConcurrentHashMap<Resource, Resource>)context.getServer().getAttribute(CACHED_CONTAINER_RESOURCES);
            if (metaInfResourceCache == null)
            {
                metaInfResourceCache = new ConcurrentHashMap<Resource,Resource>();
                context.getServer().setAttribute(CACHED_CONTAINER_RESOURCES, metaInfResourceCache);
            }
            metaInfFragmentCache = (ConcurrentHashMap<Resource, Resource>)context.getServer().getAttribute(CACHED_CONTAINER_FRAGMENTS);
            if (metaInfFragmentCache == null)
            {
                metaInfFragmentCache = new ConcurrentHashMap<Resource,Resource>();
                context.getServer().setAttribute(CACHED_CONTAINER_FRAGMENTS, metaInfFragmentCache);
            }
            metaInfTldCache = (ConcurrentHashMap<Resource, Collection<URL>>)context.getServer().getAttribute(CACHED_CONTAINER_TLDS);
            if (metaInfTldCache == null)
            {
                metaInfTldCache = new ConcurrentHashMap<Resource,Collection<URL>>(); 
                context.getServer().setAttribute(CACHED_CONTAINER_TLDS, metaInfTldCache);
            }
        }
        
        //Scan jars for META-INF information
        if (jars != null)
        {
            for (Resource r : jars)
            {
                
               scanForResources(context, r, metaInfResourceCache);
               scanForFragment(context, r, metaInfFragmentCache);
               scanForTlds(context, r, metaInfTldCache);
            }
        }
    }
    
    /**
     * Scan for META-INF/resources dir in the given jar.
     * 
     * @param context the context for the scan
     * @param target the target resource to scan for
     * @param cache the resource cache
     * @throws Exception if unable to scan for resources
     */
    public void scanForResources (WebAppContext context, Resource target, ConcurrentHashMap<Resource,Resource> cache)
    throws Exception
    {
        Resource resourcesDir = null;
        if (cache != null && cache.containsKey(target))
        {
            resourcesDir = cache.get(target);  
            if (resourcesDir == EmptyResource.INSTANCE)
            {
                if (LOG.isDebugEnabled()) LOG.debug(target+" cached as containing no META-INF/resources");
                return;    
            }
            else
                if (LOG.isDebugEnabled()) LOG.debug(target+" META-INF/resources found in cache ");
        }
        else
        {
            //not using caches or not in the cache so check for the resources dir
            if (LOG.isDebugEnabled()) LOG.debug(target+" META-INF/resources checked");
            if (target.isDirectory())
            {
                //TODO think  how to handle an unpacked jar file (eg for osgi)
                resourcesDir = target.addPath("/META-INF/resources");
            }
            else
            {
                //Resource represents a packed jar
                URI uri = target.getURI();
                resourcesDir = Resource.newResource(uriJarPrefix(uri,"!/META-INF/resources"));
            }
            
            if (!resourcesDir.exists() || !resourcesDir.isDirectory())
            {
                resourcesDir.close();
                resourcesDir = EmptyResource.INSTANCE;
            }

            if (cache != null)
            {               
                Resource old  = cache.putIfAbsent(target, resourcesDir);
                if (old != null)
                    resourcesDir = old;
                else
                    if (LOG.isDebugEnabled()) LOG.debug(target+" META-INF/resources cache updated");
            }

            if (resourcesDir == EmptyResource.INSTANCE)
            {
                return;
            }
        }

        //add it to the meta inf resources for this context
        Set<Resource> dirs = (Set<Resource>)context.getAttribute(METAINF_RESOURCES);
        if (dirs == null)
        {
            dirs = new HashSet<Resource>();
            context.setAttribute(METAINF_RESOURCES, dirs);
        }
        if (LOG.isDebugEnabled()) LOG.debug(resourcesDir+" added to context");

        dirs.add(resourcesDir);
    }
    
    /**
     * Scan for META-INF/web-fragment.xml file in the given jar.
     * 
     * @param context the context for the scan
     * @param jar the jar resource to scan for fragements in
     * @param cache the resource cache
     * @throws Exception if unable to scan for fragments
     */
    public void scanForFragment (WebAppContext context, Resource jar, ConcurrentHashMap<Resource,Resource> cache)
    throws Exception
    {
        Resource webFrag = null;
        if (cache != null && cache.containsKey(jar))
        {
            webFrag = cache.get(jar);  
            if (webFrag == EmptyResource.INSTANCE)
            {
                if (LOG.isDebugEnabled()) LOG.debug(jar+" cached as containing no META-INF/web-fragment.xml");
                return;     
            }
            else
                if (LOG.isDebugEnabled()) LOG.debug(jar+" META-INF/web-fragment.xml found in cache ");
        }
        else
        {
            //not using caches or not in the cache so check for the web-fragment.xml
            if (LOG.isDebugEnabled()) LOG.debug(jar+" META-INF/web-fragment.xml checked");
            if (jar.isDirectory())
            {
                //TODO   ????
                webFrag = jar.addPath("/META-INF/web-fragment.xml");
            }
            else
            {
                URI uri = jar.getURI();
                webFrag = Resource.newResource(uriJarPrefix(uri,"!/META-INF/web-fragment.xml"));
            }
            if (!webFrag.exists() || webFrag.isDirectory())
            {
                webFrag.close();
                webFrag = EmptyResource.INSTANCE;
            }
            
            if (cache != null)
            {
                //web-fragment.xml doesn't exist: put token in cache to signal we've seen the jar               
                Resource old = cache.putIfAbsent(jar, webFrag);
                if (old != null)
                    webFrag = old;
                else
                    if (LOG.isDebugEnabled()) LOG.debug(jar+" META-INF/web-fragment.xml cache updated");
            }
            
            if (webFrag == EmptyResource.INSTANCE)
                return;
        }

        Map<Resource, Resource> fragments = (Map<Resource,Resource>)context.getAttribute(METAINF_FRAGMENTS);
        if (fragments == null)
        {
            fragments = new HashMap<Resource, Resource>();
            context.setAttribute(METAINF_FRAGMENTS, fragments);
        }
        fragments.put(jar, webFrag);   
        if (LOG.isDebugEnabled()) LOG.debug(webFrag+" added to context");
    }
    
    
    /**
     * Discover META-INF/*.tld files in the given jar
     * 
     * @param context the context for the scan
     * @param jar the jar resources to scan tlds for
     * @param cache the resource cache
     * @throws Exception if unable to scan for tlds
     */
    public void scanForTlds (WebAppContext context, Resource jar, ConcurrentHashMap<Resource, Collection<URL>> cache)
    throws Exception
    {
        Collection<URL> tlds = null;
        
        if (cache != null && cache.containsKey(jar))
        {
            Collection<URL> tmp = cache.get(jar);
            if (tmp.isEmpty())
            {
                if (LOG.isDebugEnabled()) LOG.debug(jar+" cached as containing no tlds");
                return;
            }
            else
            {
                tlds = tmp;
                if (LOG.isDebugEnabled()) LOG.debug(jar+" tlds found in cache ");
            }
        }
        else
        {
            //not using caches or not in the cache so find all tlds
            tlds = new HashSet<URL>();  
            if (jar.isDirectory())
            {
                tlds.addAll(getTlds(jar.getFile()));
            }
            else
            {
                URI uri = jar.getURI();
                tlds.addAll(getTlds(uri));
            }

            if (cache != null)
            {  
                if (LOG.isDebugEnabled()) LOG.debug(jar+" tld cache updated");
                Collection<URL> old = (Collection<URL>)cache.putIfAbsent(jar, tlds);
                if (old != null)
                    tlds = old;
            }
            
            if (tlds.isEmpty())
                return;
        }

        Collection<URL> metaInfTlds = (Collection<URL>)context.getAttribute(METAINF_TLDS);
        if (metaInfTlds == null)
        {
            metaInfTlds = new HashSet<URL>();
            context.setAttribute(METAINF_TLDS, metaInfTlds);
        }
        metaInfTlds.addAll(tlds);  
        if (LOG.isDebugEnabled()) LOG.debug("tlds added to context");
    }
    
   
    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(METAINF_RESOURCES, null);

        context.setAttribute(METAINF_FRAGMENTS, null); 
   
        context.setAttribute(METAINF_TLDS, null);
    }
    
    /**
     * Find all .tld files in all subdirs of the given dir.
     * 
     * @param dir the directory to scan
     * @return the list of tlds found
     * @throws IOException if unable to scan the directory
     */
    public Collection<URL>  getTlds (File dir) throws IOException
    {
        if (dir == null || !dir.isDirectory())
            return Collections.emptySet();
        
        HashSet<URL> tlds = new HashSet<URL>();
        
        File[] files = dir.listFiles();
        if (files != null)
        {
            for (File f:files)
            {
                if (f.isDirectory())
                    tlds.addAll(getTlds(f));
                else
                {
                    String name = f.getCanonicalPath();
                    if (name.contains("META-INF") && name.endsWith(".tld"))
                        tlds.add(f.toURI().toURL());
                }
            }
        }
        return tlds;  
    }
    
    /**
     * Find all .tld files in the given jar.
     * 
     * @param uri the uri to jar file
     * @return the collection of tlds as url references  
     * @throws IOException if unable to scan the jar file
     */
    public Collection<URL> getTlds (URI uri) throws IOException
    {
        HashSet<URL> tlds = new HashSet<URL>();

        String jarUri = uriJarPrefix(uri, "!/");
        URL url = new URL(jarUri);
        JarURLConnection jarConn = (JarURLConnection) url.openConnection();
        jarConn.setUseCaches(Resource.getDefaultUseCaches());
        JarFile jarFile = jarConn.getJarFile();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements())
        {
            JarEntry e = entries.nextElement();
            String name = e.getName();
            if (name.startsWith("META-INF") && name.endsWith(".tld"))
            {
                tlds.add(new URL(jarUri + name));
            }
        }
        if (!Resource.getDefaultUseCaches())
            jarFile.close();
        return tlds;
    }

    private String uriJarPrefix(URI uri, String suffix)
    {
        String uriString = uri.toString();
        if (uriString.startsWith("jar:")) {
            return uriString + suffix;
        } else {
            return "jar:" + uriString + suffix;
        }
    }
}
