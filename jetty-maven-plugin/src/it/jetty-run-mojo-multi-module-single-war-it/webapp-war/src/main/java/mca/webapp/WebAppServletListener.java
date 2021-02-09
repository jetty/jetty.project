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

package mca.webapp;

import java.net.URL;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

import static java.lang.String.format;

public class WebAppServletListener implements ServletContextListener
{

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent)
    {
        print("1", "javax.servlet.ServletContextListener");
        print("2", "mca.common.CommonService");
        print("3", "mca.module.ModuleApi");
        print("4", "mca.module.ModuleImpl");
        print("5", "mca.webapp.WebAppServletListener");
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent)
    {

    }

    private void print(String counter, String className)
    {
        String res = className.replaceAll("\\.", "/") + ".class";
        URL url = Thread.currentThread().getContextClassLoader().getResource(res);
        System.out.println(
            format("(%sa) >> %s loaded from %s << (%sb)",
                counter, className, url, counter)
        );
    }
}
