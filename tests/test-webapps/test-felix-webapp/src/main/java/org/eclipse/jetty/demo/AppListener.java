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

package org.eclipse.jetty.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

import org.osgi.framework.Constants;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

@WebListener
public class AppListener implements ServletContextListener
{
    public void contextInitialized(ServletContextEvent sce)
    {
        Framework framework = initFelix();
        sce.getServletContext().setAttribute(Framework.class.getName(), framework);
    }

    private Framework initFelix()
    {
        Map<String, String> properties = new HashMap<>();

        try
        {
            Path cacheDir = Files.createTempDirectory("felix-cache");
            properties.put(Constants.FRAMEWORK_STORAGE, cacheDir.toAbsolutePath().toString());
            properties.put(Constants.FRAMEWORK_STORAGE_CLEAN, Constants.FRAMEWORK_STORAGE_CLEAN_ONFIRSTINIT);
            properties.put(Constants.FRAMEWORK_BUNDLE_PARENT, Constants.FRAMEWORK_BUNDLE_PARENT_FRAMEWORK);
            properties.put(Constants.FRAMEWORK_BOOTDELEGATION, "*");
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to configure Felix", e);
        }

        Framework framework = ServiceLoader.load(FrameworkFactory.class).iterator().next().newFramework(properties);

        try
        {
            System.err.println("Initializing felix");
            framework.init();
            System.err.println("Starting felix");
            framework.start();
        }
        catch (Exception e)
        {
            e.printStackTrace(System.err);
            throw new RuntimeException("Unable to start Felix", e);
        }

        return framework;
    }

    public void contextDestroyed(ServletContextEvent sce)
    {
    }
}
