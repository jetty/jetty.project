//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import org.eclipse.jetty.server.Server;

/**
 * Helper to create a URL class-loader with the jars inside
 * ${jetty.home}/lib/ext and ${jetty.home}/resources. In an ideal world, every
 * library is an OSGi bundle that does loads nicely. To support standard jars or
 * bundles that cannot be loaded in the current OSGi environment, we support
 * inserting the jars in the usual jetty/lib/ext folders in the proper classpath
 * for the webapps.
 * <p>
 * Also the folder resources typically contains central configuration files for
 * things like: log config and others. We enable fragments to register classes
 * that are called back and passed those resources to do what they need to do.
 * </p>
 * <p>
 * For example the test-jndi webapplication depends on derby, derbytools,
 * atomikos none of them are osgi bundles. we can either re-package them or we
 * can place them in the usual lib/ext. <br/>
 * In fact jasper's jsp libraries should maybe place in lib/ext too.
 * </p>
 * <p>
 * The drawback is that those libraries will not be available in the OSGi
 * classloader. Note that we could have setup those jars as embedded jars of the
 * current bundle. However, we would need to know in advance what are those jars
 * which was not acceptable. Also having those jars in a URLClassLoader seem to
 * be required for some cases. For example jaspers' TldLocationsCache (replaced
 * by TldScanner for servlet-3.0). <br/>
 * Also all the dependencies of those libraries must be resolvable directly from
 * the JettyBootstrapActivator bundle as it is set as the parent classloader. For
 * example: if atomikos is placed in lib/ext it will work if and only if
 * JettyBootstrapActivator import the necessary packages from javax.naming*,
 * javax.transaction*, javax.mail* etc Most of the common cases of javax are
 * added as optional import packages into jetty bootstrapper plugin. When there
 * are not covered: please make a request or create a fragment or register a
 * bundle with a buddy-policy onto the jetty bootstrapper..
 * </p>
 * <p>
 * Alternatives to placing jars in lib/ext
 * <ol>
 * <li>Bundle the jars in an osgi bundle. Have the webapp(s) that context
 * depends on them depend on that bundle. Things will go well for jetty.</li>
 * <li>Bundle those jars in an osgi bundle-fragment that targets the
 * jetty-bootstrap bundle</li>
 * <li>Use equinox Buddy-Policy: register a buddy of the jetty bootstrapper
 * bundle. (least favorite: it will work only on equinox)</li>
 * </ol>
 * </p>
 */
public class LibExtClassLoaderHelper
{

    /**
     * Class called back
     */
    public interface IFilesInJettyHomeResourcesProcessor
    {
        void processFilesInResourcesFolder(File jettyHome, Map<String, File> filesInResourcesFolder);
    }

    public static Set<IFilesInJettyHomeResourcesProcessor> registeredFilesInJettyHomeResourcesProcessors = new HashSet<IFilesInJettyHomeResourcesProcessor>();

    /**
     * @param server
     * @return a url classloader with the jars of resources, lib/ext and the
     *         jars passed in the other argument. The parent classloader usually
     *         is the JettyBootStrapper (an osgi classloader.
     * @throws MalformedURLException
     */
    public static ClassLoader createLibEtcClassLoader(File jettyHome, Server server, ClassLoader parentClassLoader) throws MalformedURLException
    {
        if (jettyHome == null) { return parentClassLoader; }
        ArrayList<URL> urls = new ArrayList<URL>();
        File jettyResources = new File(jettyHome, "resources");
        if (jettyResources.exists())
        {
            // make sure it contains something else than README:
            Map<String, File> jettyResFiles = new HashMap<String, File>();
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
                    {// is this necessary anyways?
                        url = new URL("jar:" + url.toString() + "!/");
                    }
                    urls.add(url);
                }
            }
        }

        return new URLClassLoader(urls.toArray(new URL[urls.size()]), parentClassLoader);
    }

    /**
     * @param server
     * @return a url classloader with the jars of resources, lib/ext and the
     *         jars passed in the other argument. The parent classloader usually
     *         is the JettyBootStrapper (an osgi classloader). If there was no
     *         extra jars to insert, then just return the parentClassLoader.
     * @throws MalformedURLException
     */
    public static ClassLoader createLibExtClassLoader(List<File> jarsContainerOrJars, List<URL> otherJarsOrFolder, Server server, ClassLoader parentClassLoader) 
    throws MalformedURLException
    {
        if (jarsContainerOrJars == null && otherJarsOrFolder == null) { return parentClassLoader; }
        List<URL> urls = new ArrayList<URL>();
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
     * framework only in a fragment. <br/>
     * Trying to configure log4j and logback in here.
     * </p>
     * <p>
     * We recommend that slf4j jars are all placed in the osgi framework. And a
     * single implementation if possible packaged as an osgi bundle is there.
     * </p>
     */
    protected static void processFilesInResourcesFolder(File jettyHome, Map<String, File> childrenFiles)
    {
        for (IFilesInJettyHomeResourcesProcessor processor : registeredFilesInJettyHomeResourcesProcessors)
        {
            processor.processFilesInResourcesFolder(jettyHome, childrenFiles);
        }
    }

}
