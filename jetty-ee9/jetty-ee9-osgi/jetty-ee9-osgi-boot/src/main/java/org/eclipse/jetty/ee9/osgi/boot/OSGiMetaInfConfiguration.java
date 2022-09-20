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

package org.eclipse.jetty.ee9.osgi.boot;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.eclipse.jetty.ee9.osgi.boot.utils.BundleFileLocatorHelperFactory;
import org.eclipse.jetty.ee9.osgi.boot.utils.Util;
import org.eclipse.jetty.ee9.osgi.boot.utils.internal.PackageAdminServiceTracker;
import org.eclipse.jetty.ee9.webapp.Configuration;
import org.eclipse.jetty.ee9.webapp.MetaInfConfiguration;
import org.eclipse.jetty.ee9.webapp.WebAppContext;
import org.eclipse.jetty.ee9.webapp.WebInfConfiguration;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceCollection;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGiWebInfConfiguration
 *
 * Handle adding resources found in bundle fragments, and add them into the
 */
public class OSGiMetaInfConfiguration extends MetaInfConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(WebInfConfiguration.class);

    /**
     * Comma separated list of symbolic names of bundles that contain tlds that should be considered
     * as on the container classpath
     */
    public static final String SYS_PROP_TLD_BUNDLES = "org.eclipse.jetty.ee9.osgi.tldbundles";
    /**
     * Regex of symbolic names of bundles that should be considered to be on the container classpath
     */
    public static final String CONTAINER_BUNDLE_PATTERN = "org.eclipse.jetty.server.webapp.containerIncludeBundlePattern";
    public static final String FRAGMENT_AND_REQUIRED_BUNDLES = "org.eclipse.jetty.ee9.osgi.fragmentAndRequiredBundles";
    public static final String FRAGMENT_AND_REQUIRED_RESOURCES = "org.eclipse.jetty.ee9.osgi.fragmentAndRequiredResources";

    @Override
    public Class<? extends Configuration> replaces()
    {
        return MetaInfConfiguration.class;
    }

    /**
     * Check to see if there have been any bundle symbolic names added of bundles that should be
     * regarded as being on the container classpath, and scanned for fragments, tlds etc etc.
     * This can be defined in:
     * <ol>
     * <li>SystemProperty SYS_PROP_TLD_BUNDLES</li>
     * <li>DeployerManager.setContextAttribute CONTAINER_BUNDLE_PATTERN</li>
     * </ol>
     *
     * We also allow individual bundles to specify particular bundles that might include TLDs via the Require-Tlds
     * MANIFEST.MF header.
     *
     * @see org.eclipse.jetty.ee9.webapp.WebInfConfiguration#preConfigure(org.eclipse.jetty.ee9.webapp.WebAppContext)
     */
    @Override
    public void preConfigure(final WebAppContext context) throws Exception
    {
        super.preConfigure(context);
    }

    @Override
    protected void scanJars(final WebAppContext context) throws Exception
    {
        //Check to see if there have been any bundle symbolic names added of bundles that should be
        //regarded as being on the container classpath, and scanned for fragments, tlds etc etc.
        //This can be defined in:
        // 1. SystemProperty SYS_PROP_TLD_BUNDLES
        // 2. DeployerManager.setContextAttribute CONTAINER_BUNDLE_PATTERN
        String tmp = (String)context.getAttribute(CONTAINER_BUNDLE_PATTERN);
        Pattern pattern = (tmp == null ? null : Pattern.compile(tmp));
        List<String> names = new ArrayList<String>();
        tmp = System.getProperty(SYS_PROP_TLD_BUNDLES);
        if (tmp != null)
        {
            StringTokenizer tokenizer = new StringTokenizer(tmp, ", \n\r\t", false);
            while (tokenizer.hasMoreTokens())
            {
                names.add(tokenizer.nextToken());
            }
        }
        HashSet<Resource> matchingResources = new HashSet<Resource>();
        if (!names.isEmpty() || pattern != null)
        {
            Bundle[] bundles = FrameworkUtil.getBundle(OSGiMetaInfConfiguration.class).getBundleContext().getBundles();

            for (Bundle bundle : bundles)
            {
                LOG.debug("Checking bundle {}:{}", bundle.getBundleId(), bundle.getSymbolicName());
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
        for (Resource r : matchingResources)
        {
            context.getMetaData().addContainerResource(r);
        }

        super.scanJars(context);
    }

    @Override
    public void postConfigure(WebAppContext context) throws Exception
    {
        context.setAttribute(FRAGMENT_AND_REQUIRED_BUNDLES, null);
        context.setAttribute(FRAGMENT_AND_REQUIRED_RESOURCES, null);
        super.postConfigure(context);
    }

    /**
     * Consider the fragment bundles associated with the bundle of the webapp being deployed.
     *
     * @see org.eclipse.jetty.ee9.webapp.MetaInfConfiguration#findJars(org.eclipse.jetty.ee9.webapp.WebAppContext)
     */
    @Override
    protected List<Resource> findJars(WebAppContext context)
        throws Exception
    {
        List<Resource> mergedResources = new ArrayList<Resource>();
        //get jars from WEB-INF/lib if there are any
        List<Resource> webInfJars = super.findJars(context);
        if (webInfJars != null)
            mergedResources.addAll(webInfJars);

        //add fragment jars and any Required-Bundles as if in WEB-INF/lib of the associated webapp
        Bundle[] bundles = PackageAdminServiceTracker.INSTANCE.getFragmentsAndRequiredBundles((Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE));
        if (bundles != null && bundles.length > 0)
        {
            Set<Bundle> fragsAndReqsBundles = (Set<Bundle>)context.getAttribute(FRAGMENT_AND_REQUIRED_BUNDLES);
            if (fragsAndReqsBundles == null)
            {
                fragsAndReqsBundles = new HashSet<Bundle>();
                context.setAttribute(FRAGMENT_AND_REQUIRED_BUNDLES, fragsAndReqsBundles);
            }

            Set<Resource> fragsAndReqsResources = (Set<Resource>)context.getAttribute(FRAGMENT_AND_REQUIRED_RESOURCES);
            if (fragsAndReqsResources == null)
            {
                fragsAndReqsResources = new HashSet<Resource>();
                context.setAttribute(FRAGMENT_AND_REQUIRED_RESOURCES, fragsAndReqsResources);
            }

            for (Bundle b : bundles)
            {
                //skip bundles that are not installed
                if (b.getState() == Bundle.UNINSTALLED)
                    continue;

                //add to context attribute storing associated fragments and required bundles
                fragsAndReqsBundles.add(b);
                File f = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(b);
                Resource r = Resource.newResource(f.toURI());
                //add to convenience context attribute storing fragments and required bundles as Resources
                fragsAndReqsResources.add(r);
                mergedResources.add(r);
            }
        }

        return mergedResources;
    }

    /**
     * Allow fragments to supply some resources that are added to the baseResource of the webapp.
     *
     * The resources can be either prepended or appended to the baseResource.
     *
     * @see org.eclipse.jetty.ee9.webapp.WebInfConfiguration#configure(org.eclipse.jetty.ee9.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        TreeMap<String, Resource> prependedResourcesPath = new TreeMap<String, Resource>();
        TreeMap<String, Resource> appendedResourcesPath = new TreeMap<String, Resource>();

        Bundle bundle = (Bundle)context.getAttribute(OSGiWebappConstants.JETTY_OSGI_BUNDLE);
        if (bundle != null)
        {
            Set<Bundle> fragments = (Set<Bundle>)context.getAttribute(FRAGMENT_AND_REQUIRED_BUNDLES);
            if (fragments != null && !fragments.isEmpty())
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
                    String path = Util.getManifestHeaderValue(OSGiWebappConstants.JETTY_WAR_FRAGMENT_RESOURCE_PATH, frag.getHeaders());
                    convertFragmentPathToResource(path, frag, appendedResourcesPath);
                    path = Util.getManifestHeaderValue(OSGiWebappConstants.JETTY_WAR_PREPEND_FRAGMENT_RESOURCE_PATH, frag.getHeaders());
                    convertFragmentPathToResource(path, frag, prependedResourcesPath);
                }
                if (!appendedResourcesPath.isEmpty())
                {
                    LinkedHashSet<Resource> resources = new LinkedHashSet<Resource>();
                    //Add in any existing setting of extra resource dirs
                    Set<Resource> resourceDirs = (Set<Resource>)context.getAttribute(MetaInfConfiguration.RESOURCE_DIRS);
                    if (resourceDirs != null && !resourceDirs.isEmpty())
                        resources.addAll(resourceDirs);
                    //Then append the values from JETTY_WAR_FRAGMENT_FOLDER_PATH
                    resources.addAll(appendedResourcesPath.values());

                    context.setAttribute(MetaInfConfiguration.RESOURCE_DIRS, resources);
                }
            }
        }

        super.configure(context);

        // place the prepended resources at the beginning of the contexts's resource base
        if (!prependedResourcesPath.isEmpty())
        {
            Resource[] resources = new Resource[1 + prependedResourcesPath.size()];
            System.arraycopy(prependedResourcesPath.values().toArray(new Resource[prependedResourcesPath.size()]), 0, resources, 0, prependedResourcesPath.size());
            resources[resources.length - 1] = context.getBaseResource();
            context.setBaseResource(Resource.of(resources));
        }
    }

    /**
     * Resolves the bundle. Usually that would be a single URL per bundle. But we do some more work if there are jars
     * embedded in the bundle.
     */
    private List<Resource> getBundleAsResource(Bundle bundle)
        throws Exception
    {
        List<Resource> resources = new ArrayList<Resource>();

        File file = BundleFileLocatorHelperFactory.getFactory().getHelper().getBundleInstallLocation(bundle);
        if (file.isDirectory())
        {
            for (File f : file.listFiles())
            {
                if (FileID.isJavaArchive(f.getName()) && f.isFile())
                {
                    resources.add(Resource.newResource(f));
                }
                else if (f.isDirectory() && f.getName().equals("lib"))
                {
                    for (File f2 : file.listFiles())
                    {
                        if (FileID.isJavaArchive(f2.getName()) && f2.isFile())
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

    /**
     * Convert a path inside a fragment into a Resource
     */
    private void convertFragmentPathToResource(String resourcePath, Bundle fragment, Map<String, Resource> resourceMap)
        throws Exception
    {
        if (resourcePath == null)
            return;

        URL url = fragment.getEntry(resourcePath);
        if (url == null)
        {
            throw new IllegalArgumentException("Unable to locate " + resourcePath + " inside the fragment '" + fragment.getSymbolicName() + "'");
        }
        url = BundleFileLocatorHelperFactory.getFactory().getHelper().getLocalURL(url);
        String key = resourcePath.startsWith("/") ? resourcePath.substring(1) : resourcePath;

        URI uri;
        try
        {
            uri = url.toURI();
        }
        catch (URISyntaxException e)
        {
            uri = new URI(url.toString().replaceAll(" ", "%20"));
        }
        resourceMap.put(key + ";" + fragment.getSymbolicName(), Resource.newResource(uri));
    }
}
