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

package org.eclipse.jetty.ee11.webapp;

import org.eclipse.jetty.util.Loader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>This configuration configures the {@link WebAppContext} protected/hidden classes
 * so that web applications are able to see the {@code org.eclipse.jetty.util} package
 * and sub-packages.</p>
 * <p>This class is defined in the webapp module because it implements the
 * {@link Configuration} interface, which is unknown to the util module.</p>
 */
public class UtilConfiguration extends AbstractConfiguration
{
    private static final Logger LOG = LoggerFactory.getLogger(UtilConfiguration.class);

    public UtilConfiguration()
    {
        super(new Builder()
            .addDependents(WebXmlConfiguration.class, MetaInfConfiguration.class, WebInfConfiguration.class)
            .protectAndExpose("org.eclipse.jetty.util."));
    }

    @Override
    public boolean isAvailable()
    {
        try
        {
            return Loader.loadClass("org.eclipse.jetty.util.Callback") != null;
        }
        catch (Throwable e)
        {
            LOG.trace("IGNORED", e);
            return false;
        }
    }
}
