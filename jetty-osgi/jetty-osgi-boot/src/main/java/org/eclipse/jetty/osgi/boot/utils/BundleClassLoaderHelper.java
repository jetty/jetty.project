// ========================================================================
// Copyright (c) 2009 Intalio, Inc.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.osgi.boot.utils;

import org.osgi.framework.Bundle;

/**
 * Is there a clean OSGi way to go from the Bundle object to the classloader of the Bundle ?
 * You can certainly take a class inside the bundle and get the bundle's classloader
 * that way. Getting the classloader directly from the bundle would be nice.
 * <p>
 * We could use fragments that are specific to each OSGi implementation.
 * Using introspection here to keep packaging simple and avoid the multiplication of the jars.
 * </p>
 * <p>
 * The default implementation relies on introspection and supports equinox-3.5 and felix-2.0.0
 * </p>
 */
public interface BundleClassLoaderHelper
{
    
    /** The name of the custom implementation for this interface in a fragment. */
    public static final String CLASS_NAME = "org.eclipse.jetty.osgi.boot.utils.BundleClassLoaderHelperImpl";

    /**
     * @return The classloader of a given bundle. Assuming the bundle is started.
     */
    public ClassLoader getBundleClassLoader(Bundle bundle);
	
}
