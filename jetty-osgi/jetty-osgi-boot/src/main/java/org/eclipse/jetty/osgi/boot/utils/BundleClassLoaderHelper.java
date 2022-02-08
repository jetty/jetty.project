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

package org.eclipse.jetty.osgi.boot.utils;

import org.eclipse.jetty.osgi.boot.utils.internal.DefaultBundleClassLoaderHelper;
import org.osgi.framework.Bundle;

/**
 * BundleClassLoaderHelper
 * <p>
 * Is there a clean OSGi way to go from the Bundle object to the classloader of
 * the Bundle ? You can certainly take a class inside the bundle and get the
 * bundle's classloader that way. Getting the classloader directly from the
 * bundle would be nice.
 * <p>
 * We could use fragments that are specific to each OSGi implementation. Using
 * introspection here to keep packaging simple and avoid the multiplication of
 * the jars.
 * <p>
 * The default implementation relies on introspection and supports equinox-3.5
 * and felix-2.0.0
 */
public interface BundleClassLoaderHelper
{

    /**
     * The name of the custom implementation for this interface in a fragment.
     */
    public static final String CLASS_NAME = "org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelperImpl";

    /**
     * The default instance supports felix and equinox
     */
    public static BundleClassLoaderHelper DEFAULT = new DefaultBundleClassLoaderHelper();

    /**
     * @param bundle the bundle
     * @return The classloader of a given bundle. Assuming the bundle is
     * started.
     */
    public ClassLoader getBundleClassLoader(Bundle bundle);
}
