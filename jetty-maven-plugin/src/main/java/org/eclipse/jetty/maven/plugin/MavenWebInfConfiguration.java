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

package org.eclipse.jetty.maven.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;
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

    /**
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    public void configure(WebAppContext context) throws Exception
    {
        JettyWebAppContext jwac = (JettyWebAppContext)context;

        //put the classes dir and all dependencies into the classpath
        if (jwac.getClassPathFiles() != null)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Setting up classpath ...");
            WebAppClassLoader classLoader = (WebAppClassLoader)context.getClassLoader();
            Set<String> paths = jwac.getClassPathFiles().stream()
                .map(Resource::toString)
                .collect(Collectors.toSet());
            for (String classpath : paths)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug(" - " + classpath);
                classLoader.addClassPath(classpath);
            }
        }

        super.configure(context);

        // knock out environmental maven and plexus classes from webAppContext
        String[] existingServerClasses = context.getServerClasses();
        String[] newServerClasses = new String[4 + (existingServerClasses == null ? 0 : existingServerClasses.length)];
        newServerClasses[0] = "org.apache.maven.";
        newServerClasses[1] = "org.codehaus.plexus.";
        newServerClasses[2] = "javax.enterprise.";
        newServerClasses[3] = "javax.decorator.";
        System.arraycopy(existingServerClasses, 0, newServerClasses, 4, existingServerClasses.length);
        if (LOG.isDebugEnabled())
        {
            LOG.debug("Server classes:");
            for (int i = 0; i < newServerClasses.length; i++)
            {
                LOG.debug(newServerClasses[i]);
            }
        }
        context.setServerClasses(newServerClasses);
    }

    /**
     * Get the jars to examine from the files from which we have
     * synthesized the classpath. Note that the classpath is not
     * set at this point, so we cannot get them from the classpath.
     *
     * @param context the web app context
     * @return the list of jars found
     */
    @Override
    protected List<Resource> findJars(WebAppContext context)
        throws Exception
    {
        List<Resource> list = new ArrayList<>();
        JettyWebAppContext jwac = (JettyWebAppContext)context;
        List<Resource> resources = jwac.getWebInfLib();
        if (resources != null)
        {
            resources.forEach(resource ->
            {
                if (resource instanceof JarResource || resource.isDirectory())
                {
                    try
                    {
                        LOG.debug(" add  resource to resources to examine {}", resource);
                        list.add(resource);
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            });
        }

        List<Resource> superList = super.findJars(context);
        if (superList != null)
            list.addAll(superList);
        return list;
    }

    /**
     * Add in the classes dirs from test/classes and target/classes
     *
     * @see org.eclipse.jetty.webapp.WebInfConfiguration#findClassDirs(org.eclipse.jetty.webapp.WebAppContext)
     */
    @Override
    protected List<Resource> findClassDirs(WebAppContext context) throws Exception
    {
        List<Resource> list = new ArrayList<>();

        JettyWebAppContext jwac = (JettyWebAppContext)context;
        List<Resource> resources = jwac.getWebInfClasses();
        if (resources != null)
        {
            resources.forEach(res ->
            {
                if (res.exists() && res.isDirectory())
                {
                    try
                    {
                        list.add(res);
                    }
                    catch (Exception e)
                    {
                        LOG.warn("Bad url ", e);
                    }
                }
            });
        }

        List<Resource> classesDirs = super.findClassDirs(context);
        if (classesDirs != null)
            list.addAll(classesDirs);
        return list;
    }
}
