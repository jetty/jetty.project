//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.osgi.boot.internal.webapp;

import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * LibExtClassLoaderHelper
 * <p>
 * Helper to create a URL class-loader with the jars inside
 * <code>${jetty.home}/lib/ext</code> and <code>${jetty.home}/resources</code>. In an ideal world, every
 * library is an OSGi bundle that does loads nicely. To support standard jars or
 * bundles that cannot be loaded in the current OSGi environment, we support
 * inserting the jars in the usual jetty/lib/ext folders in the proper classpath
 * for the webapps.
 * <p>
 * The drawback is that those jars will not be available in the OSGi
 * classloader.
 * <p>
 * Alternatives to placing jars in lib/ext:
 * <ol>
 * <li>Bundle the jars in an osgi bundle. Have the webapp(s) that need these jars
 * depend on that bundle.</li>
 * <li>Bundle those jars in an osgi bundle-fragment that targets the
 * jetty-bootstrap bundle</li>
 * <li>Use equinox Buddy-Policy: register a buddy of the jetty bootstrapper
 * bundle. (Note: it will work only on equinox)</li>
 * </ol>
 */
public class LibExtClassLoaderHelper
{

    /**
     * IFilesInJettyHomeResourcesProcessor
     *
     * Interface for callback impls
     */
    public interface IFilesInJettyHomeResourcesProcessor
    {
        void processFilesInResourcesFolder(File jettyHome, Map<String, File> filesInResourcesFolder);
    }

    public static final Set<IFilesInJettyHomeResourcesProcessor> registeredFilesInJettyHomeResourcesProcessors = new HashSet<>();

    /**
     * @param jettyHome the jetty home
     * @param parentClassLoader the parent classloader
     * @return a url classloader with the jars of resources, lib/ext and the
     * jars passed in the other argument. The parent classloader usually
     * is the JettyBootStrapper (an osgi classloader.
     * @throws MalformedURLException if the jetty home reference is invalid
     */
    public static ClassLoader createLibEtcClassLoader(File jettyHome, ClassLoader parentClassLoader) throws MalformedURLException
    {
        if (jettyHome == null)
        {
            return parentClassLoader;
        }
        ArrayList<URL> urls = new ArrayList<>();
        File jettyResources = new File(jettyHome, "resources");
        if (jettyResources.exists())
        {
            // make sure it contains something else than README:
            Map<String, File> jettyResFiles = new HashMap<>();
            for (File f : jettyResources.listFiles())
            {
                jettyResFiles.put(f.getName(), f);
                if (f.getName().toLowerCase(Locale.ENGLISH).startsWith("readme"))
                {
                    continue;
                }
                else
                {
                    if (urls.isEmpty())
                    {
                        urls.add(jettyResources.toURI().toURL());
                    }
                }
            }
            processFilesInResourcesFolder(jettyHome, jettyResFiles);
        }
        File libExt = new File(jettyHome, "lib/ext");
        if (libExt.exists())
        {
            for (File f : libExt.listFiles())
            {
                if (f.getName().endsWith(".jar"))
                {
                    // cheap to tolerate folders so let's do it.
                    URL url = f.toURI().toURL();
                    if (f.isFile())
                    {
                        // is this necessary anyways?
                        url = new URL("jar:" + url.toString() + "!/");
                    }
                    urls.add(url);
                }
            }
        }

        return new URLClassLoader(urls.toArray(new URL[urls.size()]), parentClassLoader);
    }

    /**
     * @param jarsContainerOrJars the jars via file references
     * @param otherJarsOrFolder more jars via url references
     * @param parentClassLoader the parent classloader
     * @return a url classloader with the jars of resources, lib/ext and the
     * jars passed in the other argument. The parent classloader usually
     * is the JettyBootStrapper (an osgi classloader). If there was no
     * extra jars to insert, then just return the parentClassLoader.
     * @throws MalformedURLException if there is a bad jar file reference
     */
    public static ClassLoader createLibExtClassLoader(List<File> jarsContainerOrJars, List<URL> otherJarsOrFolder, ClassLoader parentClassLoader)
        throws MalformedURLException
    {
        if (jarsContainerOrJars == null && otherJarsOrFolder == null)
        {
            return parentClassLoader;
        }
        List<URL> urls = new ArrayList<>();
        if (otherJarsOrFolder != null)
        {
            urls.addAll(otherJarsOrFolder);
        }
        if (jarsContainerOrJars != null)
        {
            for (File libExt : jarsContainerOrJars)
            {
                if (libExt.isDirectory())
                {
                    for (File f : libExt.listFiles())
                    {
                        if (f.getName().endsWith(".jar"))
                        {
                            // cheap to tolerate folders so let's do it.
                            URL url = f.toURI().toURL();
                            if (f.isFile())
                            {
                                // is this necessary anyways?
                                url = new URL("jar:" + url.toString() + "!/");
                            }
                            urls.add(url);
                        }
                    }
                }
            }
        }
        return new URLClassLoader(urls.toArray(new URL[urls.size()]), parentClassLoader);
    }

    /**
     * When we find files typically used for central logging configuration we do
     * what it takes in this method to do what the user expects. Without
     * depending too much directly on a particular logging framework.
     * <p>
     * We can afford to do some implementation specific code for a logging
     * framework only in a fragment.
     * <p>
     * Trying to configure log4j and logback in here.
     * <p>
     * We recommend that slf4j jars are all placed in the osgi framework. And a
     * single implementation if possible packaged as an osgi bundle is there.
     *
     * @param jettyHome the jetty home reference
     * @param childrenFiles the map of child files
     */
    protected static void processFilesInResourcesFolder(File jettyHome, Map<String, File> childrenFiles)
    {
        for (IFilesInJettyHomeResourcesProcessor processor : registeredFilesInJettyHomeResourcesProcessors)
        {
            processor.processFilesInResourcesFolder(jettyHome, childrenFiles);
        }
    }
}
