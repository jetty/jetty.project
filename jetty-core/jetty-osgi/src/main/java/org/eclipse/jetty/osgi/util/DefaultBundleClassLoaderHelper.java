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
 * DefaultBundleClassLoaderHelper
 * <p>
 * Default implementation of the BundleClassLoaderHelper that uses only
 * standard OSGi specification API methods to provide a ClassLoader for
 * a bundle, avoiding the use of reflection or container-specific internal APIs.
 * <p>
 * This implementation returns a {@link BundleDelegatingClassLoader} that
 * delegates class and resource loading to the bundle using the standard
 * OSGi API methods (Bundle.loadClass, Bundle.getResource, Bundle.getResources).
 * When the first class is loaded, it captures the real bundle classloader
 * from the loaded class for subsequent operations.
 */
public class DefaultBundleClassLoaderHelper implements BundleClassLoaderHelper
{
    /**
     * Returns a ClassLoader for the given bundle using only specification-compliant
     * OSGi APIs.
     *
     * @param bundle the bundle
     * @return a ClassLoader that delegates to the bundle's class and resource loading capabilities
     */
    @Override
    public ClassLoader getBundleClassLoader(Bundle bundle)
    {
        return new BundleDelegatingClassLoader(bundle);
    }
}
