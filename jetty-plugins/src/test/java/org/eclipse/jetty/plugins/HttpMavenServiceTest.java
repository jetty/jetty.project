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

import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThat;

/**
 */
public class HttpMavenServiceTest
{
    private HttpMavenService _mavenService = new HttpMavenService();

    private static final String JETTY_JMX_PLUGIN_NAME = "jetty-jmx";
    private static final String MAVEN_CENTRAL_URL = "http://repo2.maven.org/maven2/";

    @Before
    public void setUp() throws Exception
    {
        _mavenService.setLocalRepository(this.getClass().getClassLoader().getResource("maven_repo").getFile() + "/");
        _mavenService.setRepositoryUrl(MAVEN_CENTRAL_URL);
        _mavenService.setVersion("version");
        _mavenService.setSearchRemoteRepository(false);
        _mavenService.setSearchLocalRepository(true);
    }

    @Test
    public void testListAvailablePlugins()
    {
        Set<String> pluginNames = _mavenService.listAvailablePlugins();
        assertThat(pluginNames.size(), is(2));
        assertThat("plugin jetty-plugin found", pluginNames.contains("jetty-plugin"), is(true));
        assertThat("plugin jetty-anotherplugin found", pluginNames.contains("jetty-anotherplugin"), is(true));
    }

}
