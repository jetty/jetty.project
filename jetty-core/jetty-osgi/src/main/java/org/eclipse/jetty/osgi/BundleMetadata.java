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
import java.util.Dictionary;
import java.util.Enumeration;

import org.eclipse.jetty.osgi.util.Util;
import org.eclipse.jetty.server.Deployable;
import org.eclipse.jetty.util.Attributes;
import org.osgi.framework.Bundle;

/**
 * Metadata useful for a deployment that will result in a {@link org.eclipse.jetty.server.handler.ContextHandler}
 */
public class BundleMetadata
{
    private final Bundle bundle;
    private final Path bundlePath;
    private final String contextPath;
    private final Attributes attributes = new Attributes.Mapped();
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

        //copy selected bundle headers into the properties
        Dictionary<String, String> headers = bundle.getHeaders();
        Enumeration<String> keys = headers.keys();
        while (keys.hasMoreElements())
        {
            String key = keys.nextElement();
            String val = headers.get(key);
            if (Deployable.DEFAULTS_DESCRIPTOR.equalsIgnoreCase(key) || OSGiWebappConstants.JETTY_DEFAULT_WEB_XML_PATH.equalsIgnoreCase(key))
            {
                getAttributes().setAttribute(Deployable.DEFAULTS_DESCRIPTOR, val);
            }
            else if (OSGiWebappConstants.JETTY_WEB_XML_PATH.equalsIgnoreCase(key))
            {
                getAttributes().setAttribute(key, val);
            }
            else if (OSGiWebappConstants.JETTY_CONTEXT_FILE_PATH.equalsIgnoreCase(key))
            {
                getAttributes().setAttribute(key, val);
            }
        }
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

    public Attributes getAttributes()
    {
        return attributes;
    }
}
