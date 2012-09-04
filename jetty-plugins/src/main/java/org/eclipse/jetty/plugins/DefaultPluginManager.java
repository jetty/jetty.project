//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.plugins;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.eclipse.jetty.plugins.model.Plugin;

public class DefaultPluginManager implements PluginManager
{
    private String _jettyHome;
    private MavenService _mavenService;

    private static List<String> excludes = Arrays.asList("META-INF");

    public DefaultPluginManager(MavenService mavenService, String jettyHome)
    {
        this._mavenService = mavenService;
        this._jettyHome = jettyHome;
    }

    public Set<String> listAvailablePlugins()
    {
        return _mavenService.listAvailablePlugins();
    }


    public void installPlugin(String pluginName)
    {
        Plugin plugin = _mavenService.getPlugin(pluginName);
        installPlugin(plugin);
    }

    private void installPlugin(Plugin plugin)
    {
        try
        {
            ZipFile pluginJar = new ZipFile(plugin.getPluginJar());
            extractJar(pluginJar);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e);
        }
    }

    private void extractJar(ZipFile file)
    {
        Enumeration<? extends ZipEntry> entries = file.entries();
        while (entries.hasMoreElements())
        {
            extractFileFromJar(file, entries.nextElement());
        }
    }

    private void extractFileFromJar(ZipFile zipFile, ZipEntry zipEntry)
    {
        for (String exclude : excludes)
            if (zipEntry.getName().startsWith(exclude))
                return;

        System.out.println("Extracting: " + zipEntry.getName());
        File f = new File(_jettyHome + File.separator + zipEntry.getName());
        if (zipEntry.isDirectory())
        {
            // if its a directory, create it
            f.mkdir(); // TODO: check the result: what if the directory cannot be created ?
            return;
        }


        try (InputStream is = zipFile.getInputStream(zipEntry);
             FileOutputStream fos = new FileOutputStream(f))
        {
            while (is.available() > 0)
            {
                fos.write(is.read());
            }
        }
        catch (IOException e)
        {
            throw new IllegalStateException("Could not extract plugin zip", e);
        }
    }
}
