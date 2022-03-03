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

package org.eclipse.jetty.ee10.ant;

import java.io.File;
import java.util.List;

import org.eclipse.jetty.ee10.webapp.Configuration;
import org.eclipse.jetty.ee10.webapp.WebAppClassLoader;
import org.eclipse.jetty.ee10.webapp.WebAppContext;
import org.eclipse.jetty.ee10.webapp.WebInfConfiguration;
import org.eclipse.jetty.ee10.webapp.WebXmlConfiguration;

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
