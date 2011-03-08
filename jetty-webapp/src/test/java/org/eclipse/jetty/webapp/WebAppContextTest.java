// ========================================================================
// Copyright (c) 2010 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses.
// ========================================================================
package org.eclipse.jetty.webapp;

import java.util.Arrays;

import org.eclipse.jetty.server.Server;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WebAppContextTest
{
    @Test
    public void testConfigurationClassesFromDefault ()
    {
        Server server = new Server();
        //test if no classnames set, its the defaults
        WebAppContext wac = new WebAppContext();
        assertNull(wac.getConfigurations());
        String[] classNames = wac.getConfigurationClasses();
        assertNotNull(classNames);

        //test if no classname set, and none from server its the defaults
        wac.setServer(server);
        assertTrue(Arrays.equals(classNames, wac.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationClassesExplicit ()
    {
        String[] classNames = {"x.y.z"};

        Server server = new Server();
        server.setAttribute(WebAppContext.SERVER_CONFIG, classNames);

        //test an explicitly set classnames list overrides that from the server
        WebAppContext wac = new WebAppContext();
        String[] myClassNames = {"a.b.c", "d.e.f"};
        wac.setConfigurationClasses(myClassNames);
        wac.setServer(server);
        String[] names = wac.getConfigurationClasses();
        assertTrue(Arrays.equals(myClassNames, names));


        //test if no explicit classnames, they come from the server
        WebAppContext wac2 = new WebAppContext();
        wac2.setServer(server);
        assertTrue(Arrays.equals(classNames, wac2.getConfigurationClasses()));
    }

    @Test
    public void testConfigurationInstances ()
    {
        Configuration[] configs = {new WebInfConfiguration()};
        WebAppContext wac = new WebAppContext();
        wac.setConfigurations(configs);
        assertTrue(Arrays.equals(configs, wac.getConfigurations()));

        //test that explicit config instances override any from server
        String[] classNames = {"x.y.z"};
        Server server = new Server();
        server.setAttribute(WebAppContext.SERVER_CONFIG, classNames);
        wac.setServer(server);
        assertTrue(Arrays.equals(configs,wac.getConfigurations()));
    }
}
