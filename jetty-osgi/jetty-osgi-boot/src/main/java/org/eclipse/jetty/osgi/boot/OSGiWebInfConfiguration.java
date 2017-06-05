//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.osgi.boot;


import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebInfConfiguration;



/**
 * OSGiWebInfConfiguration
 *
 * Handle adding resources found in bundle fragments, and add them into the 
 */
public class OSGiWebInfConfiguration extends WebInfConfiguration
{        
    @Deprecated
    public static final String SYS_PROP_TLD_BUNDLES = OSGiMetaInfConfiguration.SYS_PROP_TLD_BUNDLES;
    @Deprecated
    public static final String CONTAINER_BUNDLE_PATTERN = OSGiMetaInfConfiguration.CONTAINER_BUNDLE_PATTERN;
    @Deprecated
    public static final String FRAGMENT_AND_REQUIRED_BUNDLES = OSGiMetaInfConfiguration.FRAGMENT_AND_REQUIRED_BUNDLES;
    @Deprecated
    public static final String FRAGMENT_AND_REQUIRED_RESOURCES = OSGiMetaInfConfiguration.FRAGMENT_AND_REQUIRED_RESOURCES;

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }
}
