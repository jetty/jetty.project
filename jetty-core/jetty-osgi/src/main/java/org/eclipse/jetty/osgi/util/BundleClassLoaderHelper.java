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

package org.eclipse.jetty.osgi.util;

import org.osgi.framework.Bundle;

/**
 * BundleClassLoaderHelper
 * <p>
 * Provides a ClassLoader for an OSGi Bundle.
 * <p>
 * The default implementation uses a specification-compliant approach that
 * delegates to the standard OSGi Bundle API methods (Bundle.loadClass,
 * Bundle.getResource, Bundle.getResources) instead of using reflection
 * to access container-specific internal classloaders.
 * <p>
 * Custom implementations can be provided via OSGi fragments by implementing
 * a class with the name specified by {@link #CLASS_NAME}.
 */
public interface BundleClassLoaderHelper
{

    /**
     * The name of the custom implementation for this interface in a fragment.
     */
    public static final String CLASS_NAME = "org.eclipse.jetty.osgi.util.BundleClassLoaderHelperImpl";

    /**
     * The default instance uses specification-compliant OSGi APIs
     */
    public static BundleClassLoaderHelper DEFAULT = new DefaultBundleClassLoaderHelper();

    /**
     * Returns a ClassLoader for the given bundle that can be used to load
     * classes and resources from the bundle.
     *
     * @param bundle the bundle
     * @return a ClassLoader for the bundle
     */
    public ClassLoader getBundleClassLoader(Bundle bundle);
}
