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

package org.mca.jetty.common;

import java.util.Map;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;

public class ClasspathDisplayListener implements ServletContextListener
{

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent)
    {
        String name = System.getProperty("org.mca.invocationName", "not-specified");
        System.out.println("[" + name + "[[");
        for (Map.Entry<String, String> entry : ClasspathAnalyzeHelper.analyze().entrySet())
        {
            System.out.println(entry.getKey() + " = " + entry.getValue());
        }
        System.out.println("]]" + name + "]");
    }

    @Override
    public void contextDestroyed(ServletContextEvent servletContextEvent)
    {

    }
}
