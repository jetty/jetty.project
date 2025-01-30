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

package org.eclipse.jetty.osgi;

import java.nio.file.Path;
import java.util.Properties;

import org.eclipse.jetty.osgi.util.Util;
import org.osgi.framework.Bundle;

/**
 * Metadata useful for a deployment that will result in a {@link org.eclipse.jetty.server.handler.ContextHandler}
 */
public class BundleMetadata
{
    private final Bundle bundle;
    private final Path bundlePath;
    private final String contextPath;
    private final Properties properties = new Properties();
    private final String pathToResourceBase;

    public BundleMetadata(Bundle bundle) throws Exception
    {
        this(bundle, null);
    }

    public BundleMetadata(Bundle bundle, String pathToResourceBase) throws Exception
    {
        this.bundle = bundle;
        this.bundlePath = Util.getBundlePath(bundle);
        this.contextPath = Util.getContextPath(bundle);
        this.pathToResourceBase = pathToResourceBase;
    }

    public Bundle getBundle()
    {
        return bundle;
    }

    public String getContextPath()
    {
        return contextPath;
    }

    public String getID()
    {
        return bundle.getSymbolicName();
    }

    public Path getPath()
    {
        return bundlePath;
    }

    public String getPathToResourceBase()
    {
        return pathToResourceBase;
    }

    public Properties getProperties()
    {
        return properties;
    }
}
