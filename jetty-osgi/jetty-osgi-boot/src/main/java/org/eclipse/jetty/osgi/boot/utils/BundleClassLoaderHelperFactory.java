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

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * BundleClassLoaderHelperFactory
 *
 * Get a class loader helper adapted for the particular osgi environment.
 */
public class BundleClassLoaderHelperFactory
{
    private static final Logger LOG = Log.getLogger(BundleClassLoaderHelperFactory.class);

    private static BundleClassLoaderHelperFactory _instance = new BundleClassLoaderHelperFactory();

    public static BundleClassLoaderHelperFactory getFactory()
    {
        return _instance;
    }

    private BundleClassLoaderHelperFactory()
    {
    }

    public BundleClassLoaderHelper getHelper()
    {
        //use the default
        BundleClassLoaderHelper helper = BundleClassLoaderHelper.DEFAULT;
        try
        {
            //if a fragment has not provided their own impl
            helper = (BundleClassLoaderHelper)Class.forName(BundleClassLoaderHelper.CLASS_NAME)
                .getDeclaredConstructor().newInstance();
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }

        return helper;
    }
}
