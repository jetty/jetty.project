//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin.classloader;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;

import org.eclipse.jetty.maven.plugin.JettyWebAppContext;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.webapp.WebAppClassLoader;

public class MavenWebappClassloader extends WebAppClassLoader
{

    private static final Logger LOG = Log.getLogger(MavenWebappClassloader.class);
    private boolean closed;

    public MavenWebappClassloader(Context context) throws IOException
    {
        super(context);
    }

    @Override
    public Enumeration<URL> getResources(String name) throws IOException
    {
        if (name == null || closed)
        {
            return null;
        }
        ArrayList<URL> resources = Collections.list(super.getResources(name));
        JettyWebAppContext context = (JettyWebAppContext)getContext();
        resources.removeIf(context::isBlacklisted);
        resources.addAll(context.getShiftedTargetResources(name));
        return Collections.enumeration(resources);
    }

    @Override
    public URL getResource(String name)
    {
        if (name == null || closed)
        {
            return null;
        }
        JettyWebAppContext context = (JettyWebAppContext)getContext();
        URL url = super.getResource(name);
        if (url == null)
        {
            Collection<URL> shiftedTargetResources = context.getShiftedTargetResources(name);
            if (!shiftedTargetResources.isEmpty())
            {
                url = shiftedTargetResources.iterator().next();
            }
        }
        else if (context.isBlacklisted(url))
        {
            return null;
        }
        return url;
    }

    @Override
    public InputStream getResourceAsStream(String name)
    {
        URL resource = getResource(name);
        InputStream inputStream = null;
        if (resource != null)
        {
            try
            {
                inputStream = resource.openStream();
            }
            catch (IOException ignored)
            {
            }
        }
        return inputStream;
    }

    @Override
    public void close() throws IOException
    {
        super.close();
        this.closed = true;
    }
}
