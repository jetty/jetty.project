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

package org.eclipse.jetty.plugins.impl;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.eclipse.jetty.plugins.MavenService;
import org.eclipse.jetty.plugins.model.Plugin;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

/**
 * This is currently more an integration test downloading real stuff from real
 * maven repositories. Actually it's preferred to have a real unit test or at
 * least a local repository server. But since HttpClient.send(exchange) has an
 * api which is really hard to mock, I will leave that excercise for later.
 * 
 * However this tests should be disabled for the general build and ci.
 * 
 * @author tbecker
 * 
 */
public class HttpMavenServiceTest {
	private MavenService _mavenService = new HttpMavenServiceImpl();

	private static final String JETTY_JMX_PLUGIN_NAME = "jetty-jmx";
	private static final String PRIVATE_NEXUS_REPOSITORY_URL = "http://gravity-design.de:8080/nexus/content/repositories/releases/";
	private static final String MAVEN_CENTRAL_URL = "http://repo2.maven.org/maven2/";

	@Before
	public void setUp() throws Exception {
		_mavenService.setRepositoryUrl(PRIVATE_NEXUS_REPOSITORY_URL);
	}

	@Test
    @Ignore("requires online repo")
	public void testListAvailablePlugins() {
		List<String> pluginNames = _mavenService.listAvailablePlugins();
		assertThat(pluginNames.size(), is(2));
	}

	@Test
    @Ignore("requires online repo")
	public void testGetPluginJar() throws IOException {
		Plugin plugin = _mavenService.getPlugin(JETTY_JMX_PLUGIN_NAME);
		assertThat("jetty-jmx should contain a plugin-jar",
				plugin.getPluginJar(), is(notNullValue()));
	}

	@Test
    @Ignore("requires online repo")
	public void testGetConfigJar() throws IOException {
		Plugin plugin = _mavenService.getPlugin(JETTY_JMX_PLUGIN_NAME);
		File configJar = plugin.getPluginJar();
		assertThat(configJar, is(not(nullValue())));
	}

}
