//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.osgi;

import java.util.List;

import org.osgi.framework.Bundle;

/**
 * A ServerClasspathContributor provides a list of bundles that should be considered to be on
 * the jetty server classpath.
 */
public interface ServerClasspathContributor
{
    /**
     * Get bundles that should be on the Server classpath,
     * and should be scanned for annotations/tlds/resources etc
     * 
     * @return list of Bundles to be scanned and put on server classpath
     */
    List<Bundle> getScannableBundles();

    /**
     * Marker interface for bundle activators that want to contribute to the jetty server classpath.
     */
    interface Source
    {
        public void registerServerClasspathContributors(Registry registry);

        public void unregisterServerClasspathContributors(Registry registry);
    }

    /**
     * Add and remove bundles from the jetty server classpath.
     */
    interface Registry
    {
        public void registerServerClasspathContributor(ServerClasspathContributor contributor);

        public void unregisterServerClasspathContributor(ServerClasspathContributor contributor);
    }
}
