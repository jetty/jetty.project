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

package org.eclipse.jetty.osgi.boot.utils;

import java.net.URL;

import org.eclipse.jetty.deploy.DeploymentManager;


/**
 * WebappRegistrationCustomizer
 * 
 * Convert bundles that contain tlds into URL locations for consumption by jasper.
 */
public interface WebappRegistrationCustomizer
{
    /**
     * we could do something a lot more pluggable with a custom header in the
     * manifest or some customer declarative services let's keep it simple for
     * now. hopefully the rest of the world won't need to customize this.
     */
    public static final String CLASS_NAME = "org.eclipse.jetty.osgi.boot.jasper.WebappRegistrationCustomizerImpl";


    /**
     * Find bundles that contain tlds and convert into URL references to their location.
     * 
     * @param manager
     * @param fileLocator
     * @return array of URLs representing locations of tld containing bundles
     * @throws Exception
     */
    URL[] getJarsWithTlds(DeploymentManager manager, BundleFileLocatorHelper fileLocator) throws Exception;

}
