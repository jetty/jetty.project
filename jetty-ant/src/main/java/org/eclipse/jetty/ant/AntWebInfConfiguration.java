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
import java.util.List;

import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;
import org.eclipse.jetty.webapp.WebXmlConfiguration;

public class AntWebInfConfiguration extends WebInfConfiguration
{

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }

    /**
     * Adds classpath files into web application classloader, and
     * sets web.xml and base directory for the configured web application.
     *
     * @see WebXmlConfiguration#configure(WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        if (context instanceof AntWebAppContext)
        {
            List<File> classPathFiles = ((AntWebAppContext)context).getClassPathFiles();
            if (classPathFiles != null)
            {
                for (File cpFile : classPathFiles)
                {
                    if (cpFile.exists())
                    {
                        ((WebAppClassLoader)context.getClassLoader()).addClassPath(cpFile.getCanonicalPath());
                    }
                }
            }
        }
        super.configure(context);
    }
}
