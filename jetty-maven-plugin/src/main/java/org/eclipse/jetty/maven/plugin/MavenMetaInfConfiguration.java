//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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
import java.util.Locale;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.webapp.Configuration;
import org.eclipse.jetty.webapp.MetaInfConfiguration;
import org.eclipse.jetty.webapp.WebAppContext;



/**
 * MavenWebInfConfiguration
 * 
 * WebInfConfiguration to take account of overlaid wars expressed as project dependencies and
 * potential configured via the maven-war-plugin.
 */
public class MavenMetaInfConfiguration extends MetaInfConfiguration
{
    private static final Logger LOG = Log.getLogger(MavenMetaInfConfiguration.class);

    protected static int COUNTER = 0; 

    @Override
    public Class<? extends Configuration> replaces()
    {
        return MetaInfConfiguration.class;
    }

    /**
     * Get the jars to examine from the files from which we have
     * synthesized the classpath. Note that the classpath is not
     * set at this point, so we cannot get them from the classpath.
     * @param context the web app context
     * @return the list of jars found
     */
    @Override
    protected List<Resource> findJars (WebAppContext context)
    throws Exception
    {
        List<Resource> list = new ArrayList<>();
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        List<File> files = jwac.getWebInfLib();
        if (files != null)
        {
            files.forEach( file -> {
                if (file.getName().toLowerCase(Locale.ENGLISH).endsWith(".jar")
                    || file.isDirectory())
                {
                    try
                    {
                        LOG.debug( " add  resource to resources to examine {}", file );
                        list.add(Resource.newResource(file.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            } );
        }

        List<Resource> superList = super.findJars(context);
        if (superList != null)
            list.addAll(superList);
        return list; 
    }
    
    

        
    /** 
     * Add in the classes dirs from test/classes and target/classes
     * @see org.eclipse.jetty.webapp.MetaInfConfiguration#findClassDirs(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    protected List<Resource> findClassDirs(WebAppContext context) throws Exception
    {        
        List<Resource> list = new ArrayList<>();
        
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        List<File> files = jwac.getWebInfClasses();
        if (files != null)
        {
            files.forEach( file -> {
                if (file.exists() && file.isDirectory())
                {
                    try
                    {
                        list.add(Resource.newResource(file.toURI()));
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            } );

        }
        
        List<Resource> classesDirs = super.findClassDirs(context);
        if (classesDirs != null)
            list.addAll(classesDirs);
        return list;
    }
}
