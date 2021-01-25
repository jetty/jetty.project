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

import java.net.URL;

import org.eclipse.jetty.deploy.DeploymentManager;

/**
 * TldBundleDiscoverer
 *
 * Convert bundles that contain tlds into URL locations for consumption by jasper.
 */
public interface TldBundleDiscoverer
{
    /**
     * Find bundles that contain tlds and convert into URL references to their location.
     *
     * @param manager The {@link DeploymentManager} instance to use
     * @param fileLocator the {@link BundleFileLocatorHelper} instance to use
     * @return array of URLs representing locations of tld containing bundles
     * @throws Exception In case of errors during resolving TLDs files
     */
    URL[] getUrlsForBundlesWithTlds(DeploymentManager manager, BundleFileLocatorHelper fileLocator) throws Exception;
}
