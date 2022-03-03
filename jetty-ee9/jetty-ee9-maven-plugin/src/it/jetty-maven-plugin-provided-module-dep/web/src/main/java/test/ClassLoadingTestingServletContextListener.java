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

package test;

import java.net.URL;
import java.net.URLClassLoader;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;


@WebListener
public class ClassLoadingTestingServletContextListener
    implements ServletContextListener
{

    @Override
    public void contextInitialized(ServletContextEvent sce)
    {
        try
        {
            Api api = new Api();
            System.err.println("Class " + api.getClass().getName() + " is available and loaded by classloader " + api.getClass().getClassLoader().toString() + ". Expected CNFE.");
            ClassLoader cl = api.getClass().getClassLoader();
            while (cl != null)
            {
                if (cl instanceof URLClassLoader)
                {
                    URLClassLoader ucl = (URLClassLoader)cl;
                    System.err.println("-----");
                    printURLs(ucl);
                    System.err.println("-----");
                }
                cl = cl.getParent();
            }
        }
        catch (java.lang.Exception exception)
        {
            exception.printStackTrace();
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
    }
    
    private void printURLs(URLClassLoader l)
    {
        if (l == null)
            return;
        
        for (URL u: l.getURLs())
        {
            System.err.println(u);
        }
    }
} 
