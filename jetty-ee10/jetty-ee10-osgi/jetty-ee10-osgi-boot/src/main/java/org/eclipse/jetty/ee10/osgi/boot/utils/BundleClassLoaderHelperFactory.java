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

package org.eclipse.jetty.ee10.osgi.boot.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BundleClassLoaderHelperFactory
 *
 * Get a class loader helper adapted for the particular osgi environment.
 */
public class BundleClassLoaderHelperFactory
{
    private static final Logger LOG = LoggerFactory.getLogger(BundleClassLoaderHelperFactory.class);

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
            LOG.trace("IGNORED", t);
        }

        return helper;
    }
}
