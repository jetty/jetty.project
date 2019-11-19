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

package test;

import java.net.URL;
import java.net.URLClassLoader;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

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
    
    private void printURLs (URLClassLoader l)
    {
        if (l == null)
            return;
        
        for (URL u: l.getURLs())
        {
            System.err.println(u);
        }
    }
} 
