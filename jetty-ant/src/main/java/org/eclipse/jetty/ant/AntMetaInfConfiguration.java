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

package org.eclipse.jetty.ant;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.List;

import org.apache.tools.ant.AntClassLoader;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;

public class AntMetaInfConfiguration extends MetaInfConfiguration
{

    @Override
    public Class<? extends Configuration> replaces()
    {
        return MetaInfConfiguration.class;
    }

    @Override
    public void findAndFilterContainerPaths(WebAppContext context) throws Exception
    {
        // TODO Auto-generated method stub
        super.findAndFilterContainerPaths(context);
    }

    @Override
    protected List<URI> getAllContainerJars(final WebAppContext context) throws URISyntaxException
    {
        List<URI> uris = new ArrayList<>();
        if (context.getClassLoader() != null)
        {
            ClassLoader loader = context.getClassLoader().getParent();
            while (loader != null)
            {
                if (loader instanceof URLClassLoader)
                {
                    URL[] urls = ((URLClassLoader)loader).getURLs();
                    if (urls != null)
                        for (URL url : urls)
                        {
                            uris.add(new URI(url.toString().replaceAll(" ", "%20")));
                        }
                }
                else if (loader instanceof AntClassLoader)
                {
                    AntClassLoader antLoader = (AntClassLoader)loader;
                    String[] paths = antLoader.getClasspath().split(new String(new char[]{File.pathSeparatorChar}));
                    if (paths != null)
                    {
                        for (String p : paths)
                        {
                            File f = new File(p);
                            uris.add(f.toURI());
                        }
                    }
                }
                loader = loader.getParent();
            }
        }
        return uris;
    }
}
