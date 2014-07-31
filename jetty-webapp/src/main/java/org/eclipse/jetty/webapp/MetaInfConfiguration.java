//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


import java.net.URI;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.util.resource.Resource;

/**
 * MetaInfConfiguration
 *
 * Scan META-INF of jars to find:
 * <ul>
 * <li>tlds
 * <li>web-fragment.xml
 * <li>resources
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
        scanJars(context, context.getMetaData().getContainerResources(), useContainerCache);
        scanJars(context, context.getMetaData().getWebInfJars(), false);
    }

    /**
     * Look into the jars to discover info in META-INF. If useCaches == true, then we will
     * cache the info discovered indexed by the jar in which it was discovered: this speeds
     * up subsequent context deployments.
     * 
     * @param context
     * @param jars
     * @param useCaches
     * @throws Exception
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
     * @param context
     * @param target
     * @param cache
     * @throws Exception
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
                resourcesDir = Resource.newResource("jar:"+uri+"!/META-INF/resources");
            }
            if (!resourcesDir.exists() || !resourcesDir.isDirectory())
                resourcesDir = EmptyResource.INSTANCE;

            if (cache != null)
            {               
                Resource old  = cache.putIfAbsent(target, resourcesDir);
                if (old != null)
                    resourcesDir = old;
                else
                    if (LOG.isDebugEnabled()) LOG.debug(target+" META-INF/resources cache updated");
            }

            if (resourcesDir == EmptyResource.INSTANCE)
                return;
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
     * @param context
     * @param jar
     * @param cache
     * @throws Exception
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
                webFrag = Resource.newResource("jar:"+uri+"!/META-INF/web-fragment.xml");
            }
            if (!webFrag.exists() || webFrag.isDirectory())
                webFrag = EmptyResource.INSTANCE;
            
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
     * @param context
     * @param jar
     * @param cache
     * @throws Exception
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
            Resource metaInfDir = null;
            if (jar.isDirectory())
            {
                //TODO ??????
                metaInfDir = jar.addPath("/META-INF/");
            }
            else
            {
                URI uri = jar.getURI();
                metaInfDir = Resource.newResource("jar:"+uri+"!/META-INF/");
            }

            //find any *.tld files inside META-INF or subdirs
            tlds = new HashSet<URL>();      
            Collection<Resource> resources = metaInfDir.getAllResources();
            for (Resource t:resources)
            {
                String name = t.toString();
                if (name.endsWith(".tld"))
                {
                    if (LOG.isDebugEnabled()) LOG.debug(t+" tld discovered");
                    tlds.add(t.getURL());
                }
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

        Collection<URL> tld_resources=(Collection<URL>)context.getAttribute(METAINF_TLDS);
        if (tld_resources == null)
        {
            tld_resources = new HashSet<URL>();
            context.setAttribute(METAINF_TLDS, tld_resources);
        }
        tld_resources.addAll(tlds);  
        if (LOG.isDebugEnabled()) LOG.debug("tlds added to context");
    }
    
   
    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(METAINF_FRAGMENTS, null); 
        context.setAttribute(METAINF_RESOURCES, null);
        context.setAttribute(METAINF_TLDS, null);
    }
}
