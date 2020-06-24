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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BundleFileLocatorHelperFactory
 *
 * Obtain a helper for locating files based on the bundle.
 */
public class BundleFileLocatorHelperFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(BundleFileLocatorHelperFactory.class);

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
            LOG.trace("IGNORED", t);
        }
        return helper;
    }
}
