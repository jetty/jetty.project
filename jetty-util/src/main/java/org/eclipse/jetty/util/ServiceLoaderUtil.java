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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

public class ServiceLoaderUtil
{
    private static final Logger LOG = Log.getLogger(ServiceLoaderUtil.class);
    private static final int MAX_ERRORS = 100;

    /**
     * Uses the {@link ServiceLoader} to assemble the service providers into a list.
     * If loading a service type throws {@link ServiceConfigurationError},
     * it warns and continues iterating through the service loader.
     * @param service The interface or abstract class representing the service.
     * @param <T> The class of the service type.
     * @return a list of the loaded service providers.
     * @throws ServiceConfigurationError If the number of errors exceeds {@link #MAX_ERRORS}
     */
    public static <T> List<T> load(Class<T> service)
    {
        List<T> list = new ArrayList<>();
        Iterator<T> iterator = ServiceLoader.load(service).iterator();

        int errors = 0;
        while (true)
        {
            try
            {
                if (!iterator.hasNext())
                    break;
                list.add(iterator.next());
            }
            catch (ServiceConfigurationError e)
            {
                LOG.warn(e);
                if (++errors >= MAX_ERRORS)
                    throw e;
            }
        }

        return list;
    }
}
