//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.maven.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.WebAppClassLoader;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.jetty.webapp.WebInfConfiguration;


/**
 * MavenWebInfConfiguration
 * 
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenWebInfConfiguration extends WebInfConfiguration
{
    private static final Logger LOG = Log.getLogger(MavenWebInfConfiguration.class);
    

    public MavenWebInfConfiguration()
    {
        hide("org.apache.maven.",
             "org.codehaus.plexus.");
    }

    @Override
    public Class<? extends Configuration> replaces()
    {
        return WebInfConfiguration.class;
    }

    /** 
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        
        //put the classes dir and all dependencies into the classpath
        if (jwac.getClassPathFiles() != null && context.getClassLoader() instanceof WebAppClassLoader)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Setting up classpath ...");
            WebAppClassLoader loader=(WebAppClassLoader)context.getClassLoader();
            for (File classpath:jwac.getClassPathFiles())
                loader.addClassPath(classpath.getCanonicalPath());
        }

        super.configure(context);
    }





}
