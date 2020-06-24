//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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
