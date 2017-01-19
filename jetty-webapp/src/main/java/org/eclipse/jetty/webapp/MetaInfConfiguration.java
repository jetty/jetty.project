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
import java.net.JarURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.PatternMatcher;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.EmptyResource;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;

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
    public static final String CACHED_CONTAINER_RESOURCES = "org.eclipse.jetty.resources.cache";
    public static final String METAINF_TLDS = "org.eclipse.jetty.tlds";
    public static final String METAINF_FRAGMENTS = FragmentConfiguration.FRAGMENT_RESOURCES;
    public static final String METAINF_RESOURCES = "org.eclipse.jetty.resources";
    public static final String CONTAINER_JAR_PATTERN = "org.eclipse.jetty.server.webapp.ContainerIncludeJarPattern";
    public static final String WEBINF_JAR_PATTERN = "org.eclipse.jetty.server.webapp.WebInfIncludeJarPattern";

    /**
     * If set, to a list of URLs, these resources are added to the context
     * resource base as a resource collection.
     */
    public static final String RESOURCE_DIRS = "org.eclipse.jetty.resources";
    
    /* ------------------------------------------------------------------------------- */
    public MetaInfConfiguration()
    {
        beforeThis(WebXmlConfiguration.class);
    }

    
    /* ------------------------------------------------------------------------------- */
    protected  List<URI> getAllContainerJars(final WebAppContext context) throws URISyntaxException
    {
        List<URI> uris = new ArrayList<>();
        if (context.getClassLoader() != null)
        {
            ClassLoader loader = context.getClassLoader().getParent();
            while (loader != null)
            {
                if (loader instanceof URLClassLoader)
                {
                    URL[] urls = ((URLClassLoader)loader).getURLs();
                    if (urls != null)
                        for(URL url:urls)
                            uris.add(new URI(url.toString().replaceAll(" ","%20")));
                }
                loader = loader.getParent();
            }
        }
        return uris;
    }
    
    /* ------------------------------------------------------------------------------- */
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {        
        // discover matching container jars
        if (context.getClassLoader() != null)
        {
            List<URI> uris = getAllContainerJars(context);

            new PatternMatcher ()
            {
                public void matched(URI uri) throws Exception
                {
                    context.getMetaData().addContainerResource(Resource.newResource(uri));
                }
            }.match((String)context.getAttribute(CONTAINER_JAR_PATTERN), 
                    uris.toArray(new URI[uris.size()]), 
                    false);
        }
        

        //Discover matching WEB-INF/lib jars
        List<Resource> jars = findJars(context);
        if (jars!=null)
        {
            List<URI> uris = jars.stream().map(Resource::getURI).collect(Collectors.toList());
            
            new PatternMatcher ()
            {
                @Override
                public void matched(URI uri) throws Exception
                {
                    context.getMetaData().addWebInfJar(Resource.newResource(uri));
                }
            }.match((String)context.getAttribute(WEBINF_JAR_PATTERN), 
                    uris.toArray(new URI[uris.size()]), 
                    true);
        }        
       
        //No pattern to appy to classes, just add to metadata
        context.getMetaData().setWebInfClassesDirs(findClassDirs(context));

        scanJars(context);
      
    }
    
    protected void scanJars (WebAppContext context) throws Exception
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
    

    @Override
    public void configure(WebAppContext context) throws Exception
    {

        // Look for extra resource
        @SuppressWarnings("unchecked")
        Set<Resource> resources = (Set<Resource>)context.getAttribute(RESOURCE_DIRS);
        if (resources!=null && !resources.isEmpty())
        {
            Resource[] collection=new Resource[resources.size()+1];
            int i=0;
            collection[i++]=context.getBaseResource();
            for (Resource resource : resources)
                collection[i++]=resource;
            context.setBaseResource(new ResourceCollection(collection));
        }
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
    

    protected List<Resource> findClassDirs (WebAppContext context)
    throws Exception
    {
        if (context == null)
            return null;
        
        List<Resource> classDirs = new ArrayList<Resource>();

        Resource webInfClasses = findWebInfClassesDir(context);
        if (webInfClasses != null)
            classDirs.add(webInfClasses);
        List<Resource> extraClassDirs = findExtraClasspathDirs(context);
        if (extraClassDirs != null)
            classDirs.addAll(extraClassDirs);
        
        return classDirs;
    }
    
    
    /**
     * Look for jars that should be treated as if they are in WEB-INF/lib
     * 
     * @param context the context to find the jars in
     * @return the list of jar resources found within context
     * @throws Exception if unable to find the jars
     */
    protected List<Resource> findJars (WebAppContext context)
    throws Exception
    {
        List<Resource> jarResources = new ArrayList<Resource>();
        List<Resource> webInfLibJars = findWebInfLibJars(context);
        if (webInfLibJars != null)
            jarResources.addAll(webInfLibJars);
        List<Resource> extraClasspathJars = findExtraClasspathJars(context);
        if (extraClasspathJars != null)
            jarResources.addAll(extraClasspathJars);
        return jarResources;
    }
    
    /**
     * Look for jars in <code>WEB-INF/lib</code>
     *  
     * @param context the context to find the lib jars in
     * @return the list of jars as {@link Resource}
     * @throws Exception if unable to scan for lib jars
     */
    protected List<Resource> findWebInfLibJars(WebAppContext context)
    throws Exception
    {
        Resource web_inf = context.getWebInf();
        if (web_inf==null || !web_inf.exists())
            return null;

        List<Resource> jarResources = new ArrayList<Resource>();
        Resource web_inf_lib = web_inf.addPath("/lib");
        if (web_inf_lib.exists() && web_inf_lib.isDirectory())
        {
            String[] files=web_inf_lib.list();
            for (int f=0;files!=null && f<files.length;f++)
            {
                try
                {
                    Resource file = web_inf_lib.addPath(files[f]);
                    String fnlc = file.getName().toLowerCase(Locale.ENGLISH);
                    int dot = fnlc.lastIndexOf('.');
                    String extension = (dot < 0 ? null : fnlc.substring(dot));
                    if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
                    {
                        jarResources.add(file);
                    }
                }
                catch (Exception ex)
                {
                    LOG.warn(Log.EXCEPTION,ex);
                }
            }
        }
        return jarResources;
    }
    
    
    
    /**
     * Get jars from WebAppContext.getExtraClasspath as resources
     * 
     * @param context the context to find extra classpath jars in
     * @return the list of Resources with the extra classpath, or null if not found
     * @throws Exception if unable to find the extra classpath jars
     */
    protected List<Resource>  findExtraClasspathJars(WebAppContext context)
    throws Exception
    { 
        if (context == null || context.getExtraClasspath() == null)
            return null;
        
        List<Resource> jarResources = new ArrayList<Resource>();
        StringTokenizer tokenizer = new StringTokenizer(context.getExtraClasspath(), ",;");
        while (tokenizer.hasMoreTokens())
        {
            Resource resource = context.newResource(tokenizer.nextToken().trim());
            String fnlc = resource.getName().toLowerCase(Locale.ENGLISH);
            int dot = fnlc.lastIndexOf('.');
            String extension = (dot < 0 ? null : fnlc.substring(dot));
            if (extension != null && (extension.equals(".jar") || extension.equals(".zip")))
            {
                jarResources.add(resource);
            }
        }
        
        return jarResources;
    }
    
    /**
     * Get <code>WEB-INF/classes</code> dir
     * 
     * @param context the context to look for the <code>WEB-INF/classes</code> directory
     * @return the Resource for the <code>WEB-INF/classes</code> directory
     * @throws Exception if unable to find the <code>WEB-INF/classes</code> directory
     */
    protected Resource findWebInfClassesDir (WebAppContext context)
    throws Exception
    {
        if (context == null)
            return null;
        
        Resource web_inf = context.getWebInf();

        // Find WEB-INF/classes
        if (web_inf != null && web_inf.isDirectory())
        {
            // Look for classes directory
            Resource classes= web_inf.addPath("classes/");
            if (classes.exists())
                return classes;
        }
        return null;
    }
    
    
    /**
     * Get class dirs from WebAppContext.getExtraClasspath as resources
     * 
     * @param context the context to look for extra classpaths in
     * @return the list of Resources to the extra classpath 
     * @throws Exception if unable to find the extra classpaths
     */
    protected List<Resource>  findExtraClasspathDirs(WebAppContext context)
    throws Exception
    { 
        if (context == null || context.getExtraClasspath() == null)
            return null;
        
        List<Resource> dirResources = new ArrayList<Resource>();
        StringTokenizer tokenizer = new StringTokenizer(context.getExtraClasspath(), ",;");
        while (tokenizer.hasMoreTokens())
        {
            Resource resource = context.newResource(tokenizer.nextToken().trim());
            if (resource.exists() && resource.isDirectory())
                dirResources.add(resource);
        }
        
        return dirResources;
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
