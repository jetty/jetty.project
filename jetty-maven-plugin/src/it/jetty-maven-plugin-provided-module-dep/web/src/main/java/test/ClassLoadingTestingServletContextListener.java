//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
        }
        catch (java.lang.Exception exception)
        {
            exception.printStackTrace();
        }
        //System.out.println("Class " + api.getClass().getName() + " is available and loaded by classloader " + api.getClass().getClassLoader().toString() + ". Expected ClassNotFoundException.");
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce)
    {
    }
} 
