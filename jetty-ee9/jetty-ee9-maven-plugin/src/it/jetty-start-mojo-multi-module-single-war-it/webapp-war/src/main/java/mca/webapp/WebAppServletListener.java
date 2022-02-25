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

package mca.webapp;

import java.net.URL;

import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;

import static java.lang.String.format;

public class WebAppServletListener implements ServletContextListener
{

    @Override
    public void contextInitialized(ServletContextEvent servletContextEvent)
    {
        print("1", "jakarta.servlet.ServletContextListener");
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
