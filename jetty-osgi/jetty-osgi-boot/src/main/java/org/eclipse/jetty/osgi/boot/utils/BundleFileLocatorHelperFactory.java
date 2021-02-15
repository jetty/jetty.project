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
 * BundleFileLocatorHelperFactory
 *
 * Obtain a helper for locating files based on the bundle.
 */
public class BundleFileLocatorHelperFactory
{
    private static final Logger LOG = Log.getLogger(BundleFileLocatorHelperFactory.class);

    private static BundleFileLocatorHelperFactory _instance = new BundleFileLocatorHelperFactory();

    private BundleFileLocatorHelperFactory()
    {
    }

    public static BundleFileLocatorHelperFactory getFactory()
    {
        return _instance;
    }

    public BundleFileLocatorHelper getHelper()
    {
        BundleFileLocatorHelper helper = BundleFileLocatorHelper.DEFAULT;
        try
        {
            //see if a fragment has supplied an alternative
            helper = (BundleFileLocatorHelper)Class.forName(BundleFileLocatorHelper.CLASS_NAME)
                .getDeclaredConstructor().newInstance();
        }
        catch (Throwable t)
        {
            LOG.ignore(t);
        }
        return helper;
    }
}
