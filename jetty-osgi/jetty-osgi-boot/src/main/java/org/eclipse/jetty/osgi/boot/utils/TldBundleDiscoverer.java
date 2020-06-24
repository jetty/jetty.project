//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
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
