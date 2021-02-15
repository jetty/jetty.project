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

package org.eclipse.jetty.osgi.boot.utils;

import java.io.File;
import java.net.URL;
import java.util.Enumeration;

import org.eclipse.jetty.osgi.boot.utils.internal.DefaultFileLocatorHelper;
import org.osgi.framework.Bundle;

/**
 * BundleFileLocatorHelper
 * <p>
 * From a bundle to its location on the filesystem. Assumes the bundle is not a
 * jar.
 */
public interface BundleFileLocatorHelper
{

    /**
     * The name of the custom implementation for this interface in a fragment.
     */
    String CLASS_NAME = "org.eclipse.jetty.osgi.boot.utils.FileLocatorHelperImpl";

    /**
     * The default instance supports felix and equinox
     */
    BundleFileLocatorHelper DEFAULT = new DefaultFileLocatorHelper();

    /**
     * Works with equinox, felix, nuxeo and probably more. Not exactly in the
     * spirit of OSGi but quite necessary to support self-contained webapps and
     * other situations.
     * <p>
     * Currently only works with bundles that are not jar.
     *
     * @param bundle The bundle
     * @return Its installation location as a file.
     * @throws Exception if unable to get the install location
     */
    File getBundleInstallLocation(Bundle bundle) throws Exception;

    /**
     * Locate a file inside a bundle.
     *
     * @param bundle the bundle
     * @param path the path
     * @return file the file object
     * @throws Exception if unable to get the file
     */
    File getFileInBundle(Bundle bundle, String path) throws Exception;

    /**
     * If the bundle is a jar, returns the jar. If the bundle is a folder, look
     * inside it and search for jars that it returns.
     * <p>
     * Good enough for our purpose (TldLocationsCache when it scans for tld
     * files inside jars alone. In fact we only support the second situation for
     * development purpose where the bundle was imported in pde and the classes
     * kept in a jar.
     *
     * @param bundle the bundle
     * @return The jar(s) file that is either the bundle itself, either the jars
     * embedded inside it.
     * @throws Exception if unable to locate the jars
     */
    File[] locateJarsInsideBundle(Bundle bundle) throws Exception;

    /**
     * Helper method equivalent to Bundle#getEntry(String entryPath) except that
     * it searches for entries in the fragments by using the findEntries method.
     *
     * @param bundle the bundle
     * @param entryPath the entry path
     * @return null or all the entries found for that path.
     */
    Enumeration<URL> findEntries(Bundle bundle, String entryPath);

    /**
     * Only useful for equinox: on felix we get the <code>file://</code> or <code>jar://</code> url
     * already. Other OSGi implementations have not been tested
     * <p>
     * Get a URL to the bundle entry that uses a common protocol (i.e. <code>file:</code>
     * <code>jar:</code> or <code>http:</code> etc.).
     *
     * @param url the url
     * @return a URL to the bundle entry that uses a common protocol
     * @throws Exception if unable to get the local url
     */
    URL getLocalURL(URL url) throws Exception;

    /**
     * Only useful for equinox: on felix we get the <code>file://</code> url already. Other
     * OSGi implementations have not been tested
     * <p>
     * Get a URL to the content of the bundle entry that uses the <code>file:</code>
     * protocol. The content of the bundle entry may be downloaded or extracted
     * to the local file system in order to create a file: URL.
     *
     * @param url the url
     * @return a URL to the content of the bundle entry that uses the file:
     * protocol
     * @throws Exception if unable to get the file url
     */
    URL getFileURL(URL url) throws Exception;
}
