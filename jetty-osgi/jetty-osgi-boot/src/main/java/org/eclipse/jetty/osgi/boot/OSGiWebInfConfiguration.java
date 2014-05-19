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

package org.eclipse.jetty.osgi.boot;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.eclipse.jetty.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;



/**
 * OSGiWebInfConfiguration
 *
 * Handle adding resources found in bundle fragments, and add them into the 
 */
public class OSGiWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(WebInfConfiguration.class);
    
    
    public static final String CONTAINER_BUNDLE_PATTERN = "org.eclipse.jetty.server.webapp.containerIncludeBundlePattern";
    
    /* ------------------------------------------------------------ */
    /** 
     * Check to see if there have been any bundle symbolic names added of bundles that should be
     * regarded as being on the container classpath, and scanned for fragments, tlds etc etc.
     * This can be defined in:
     * <ol>
     *  <li>SystemProperty SYS_PROP_TLD_BUNDLES</li>
     *  <li>DeployerManager.setContextAttribute CONTAINER_BUNDLE_PATTERN</li>
     *  </ol>
     *  
     *  We also allow individual bundles to specify particular bundles that might include TLDs via the Require-Tlds
     *  MANIFEST.MF header. 
     *  
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#preConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        super.preConfigure(context);
        
        //Check to see if there have been any bundle symbolic names added of bundles that should be
        //regarded as being on the container classpath, and scanned for fragments, tlds etc etc.
        //This can be defined in:
        // 1. SystemProperty SYS_PROP_TLD_BUNDLES
        // 2. DeployerManager.setContextAttribute CONTAINER_BUNDLE_PATTERN
        String tmp = (String)context.getAttribute(CONTAINER_BUNDLE_PATTERN);
        Pattern pattern = (tmp==null?null:Pattern.compile(tmp));
        List<String> names = new ArrayList<String>();
        tmp = System.getProperty("org.eclipse.jetty.osgi.tldbundles");
        if (tmp != null)
        {
            StringTokenizer tokenizer = new StringTokenizer(tmp, ", \n\r\t", false);
            while (tokenizer.hasMoreTokens())
                names.add(tokenizer.nextToken());
        }

        HashSet<Resource> matchingResources = new HashSet<Resource>();
        if ( !names.isEmpty() || pattern != null)
        {
            Bundle[] bundles = FrameworkUtil.getBundle(OSGiWebInfConfiguration.class).getBundleContext().getBundles();
           
            for (Bundle bundle : bundles)
            {
                if (pattern != null)
                {
                    // if bundle symbolic name matches the pattern
                    if (pattern.matcher(bundle.getSymbolicName()).matches())
                    {
                        //get the file location of the jar and put it into the list of container jars that will be scanned for stuff (including tlds)
                        matchingResources.addAll(getBundleAsResource(bundle));
                    }
                }               
                if (names != null)
                {
                    //if there is an explicit bundle name, then check if it matches
                    if (names.contains(bundle.getSymbolicName()))
                        matchingResources.addAll(getBundleAsResource(bundle));
                }
            }
        }
        
        for (Resource r:matchingResources)
        {
            context.getMetaData().addContainerResource(r);
        }
    }
    
    
    /* ------------------------------------------------------------ */
    /** 
     * Consider the fragment bundles associated with the bundle of the webapp being deployed.
     * 
     * 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#findJars(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    protected List<Resource> findJars (WebAppContext context) 
    throws Exception
    {
        List<Resource> mergedResources = new ArrayList<Resource>();
        //get jars from WEB-INF/lib if there are any
        List<Resource> webInfJars = super.findJars(context);
        if (webInfJars != null)
            mergedResources.addAll(webInfJars);
        
        //add fragment jars as if in WEB-INF/lib of the associated webapp
        Bundle[] fragments = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles((Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE));
        for (Bundle frag : fragments)
        {
            File fragFile = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(frag);
            mergedResources.add(Resource.newResource(fragFile.toURI()));  
        }
        
        return mergedResources;
    }
    
    /* ------------------------------------------------------------ */
    /** 
     * Allow fragments to supply some resources that are added to the baseResource of the webapp.
     * 
     * The resources can be either prepended or appended to the baseResource.
     * 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        TreeMap<String, Resource> patchResourcesPath = new TreeMap<String, Resource>();
        TreeMap<String, Resource> appendedResourcesPath = new TreeMap<String, Resource>();
             
        Bundle bundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        if (bundle != null)
        {
            //TODO anything we need to do to improve PackageAdminServiceTracker?
            Bundle[] fragments = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles(bundle);
            if (fragments != null && fragments.length != 0)
            {
                // sorted extra resource base found in the fragments.
                // the resources are either overriding the resourcebase found in the
                // web-bundle
                // or appended.
                // amongst each resource we sort them according to the alphabetical
                // order
                // of the name of the internal folder and the symbolic name of the
                // fragment.
                // this is useful to make sure that the lookup path of those
                // resource base defined by fragments is always the same.
                // This natural order could be abused to define the order in which
                // the base resources are
                // looked up.
                for (Bundle frag : fragments)
                {
                    String fragFolder = (String) frag.getHeaders().get(OSGiWebappConstants.JETTY_WAR_FRAGMENT_FOLDER_PATH);
                    String patchFragFolder = (String) frag.getHeaders().get(OSGiWebappConstants.JETTY_WAR_PATCH_FRAGMENT_FOLDER_PATH);
                    if (fragFolder != null)
                    {
                        URL fragUrl = frag.getEntry(fragFolder);
                        if (fragUrl == null) { throw new IllegalArgumentException("Unable to locate " + fragFolder
                                                                                  + " inside "
                                                                                  + " the fragment '"
                                                                                  + frag.getSymbolicName()
                                                                                  + "'"); }
                        fragUrl = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(fragUrl);
                        String key = fragFolder.startsWith("/") ? fragFolder.substring(1) : fragFolder;
                        appendedResourcesPath.put(key + ";" + frag.getSymbolicName(), Resource.newResource(fragUrl));
                    }
                    if (patchFragFolder != null)
                    {
                        URL patchFragUrl = frag.getEntry(patchFragFolder);
                        if (patchFragUrl == null)
                        { 
                            throw new IllegalArgumentException("Unable to locate " + patchFragUrl
                                                               + " inside fragment '"+frag.getSymbolicName()+ "'"); 
                        }
                        patchFragUrl = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(patchFragUrl);
                        String key = patchFragFolder.startsWith("/") ? patchFragFolder.substring(1) : patchFragFolder;
                        patchResourcesPath.put(key + ";" + frag.getSymbolicName(), Resource.newResource(patchFragUrl));
                    }
                }
                if (!appendedResourcesPath.isEmpty())
                    context.setAttribute(WebInfConfiguration.RESOURCE_DIRS, new HashSet<Resource>(appendedResourcesPath.values()));
            }
        }
        
        super.configure(context);

        // place the patch resources at the beginning of the contexts's resource base
        if (!patchResourcesPath.isEmpty())
        {
            Resource[] resources = new Resource[1+patchResourcesPath.size()];
            ResourceCollection mergedResources = new ResourceCollection (patchResourcesPath.values().toArray(new Resource[patchResourcesPath.size()]));
            System.arraycopy(patchResourcesPath.values().toArray(new Resource[patchResourcesPath.size()]), 0, resources, 0, patchResourcesPath.size());
            resources[resources.length-1] = context.getBaseResource();
            context.setBaseResource(new ResourceCollection(resources));
        }
    }

    
    /* ------------------------------------------------------------ */
    /**
    * Resolves the bundle. Usually that would be a single URL per bundle. But we do some more work if there are jars
    * embedded in the bundle.
    */
    private  List<Resource> getBundleAsResource(Bundle bundle)
    throws Exception
    {
        List<Resource> resources = new ArrayList<Resource>();

        File file = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        if (file.isDirectory())
        {
            for (File f : file.listFiles())
            {
                if (f.getName().endsWith(".jar") && f.isFile())
                {
                    resources.add(Resource.newResource(f));
                }
                else if (f.isDirectory() && f.getName().equals("lib"))
                {
                    for (File f2 : file.listFiles())
                    {
                        if (f2.getName().endsWith(".jar") && f2.isFile())
                        {
                            resources.add(Resource.newResource(f));
                        }
                    }
                }
            }
            resources.add(Resource.newResource(file)); //TODO really???
        }
        else
        {
            resources.add(Resource.newResource(file));
        }
        
        return resources;
    }
}
