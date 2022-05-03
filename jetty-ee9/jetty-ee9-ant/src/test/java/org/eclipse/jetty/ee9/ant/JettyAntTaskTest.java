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

package org.eclipse.jetty.ee9.ant;

import java.net.HttpURLConnection;
import java.net.URI;

import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class JettyAntTaskTest
{

    @Test
    public void testConnectorTask() throws Exception
    {
        AntBuild build = new AntBuild(MavenTestingUtils.getTestResourceFile("connector-test.xml").getAbsolutePath());

        build.start();

        URI uri = new URI("http://" + build.getJettyHost() + ":" + build.getJettyPort());

        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();

        connection.connect();

        assertThat("response code is 404", connection.getResponseCode(), is(404));

        build.stop();
    }

    @Test
    public void testWebApp() throws Exception
    {
        AntBuild build = new AntBuild(MavenTestingUtils.getTestResourceFile("webapp-test.xml").getAbsolutePath());

        build.start();

        URI uri = new URI("http://" + build.getJettyHost() + ":" + build.getJettyPort() + "/");

        HttpURLConnection connection = (HttpURLConnection)uri.toURL().openConnection();

        connection.connect();

        assertThat("response code is 200", connection.getResponseCode(), is(200));

        System.err.println("Stop build!");
        build.stop();
    }
}
