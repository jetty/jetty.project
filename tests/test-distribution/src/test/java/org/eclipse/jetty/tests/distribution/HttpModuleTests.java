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

package org.eclipse.jetty.tests.distribution;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.ContentResponse;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpModuleTests
{
    @Test
    public void http_module() throws Exception
    {
        // System.getProperty( "jetty_home" )
        DistributionTester distributionTester = DistributionTester.Builder.newInstance() //
                .jettyVersion(System.getProperty("jetty_version")) //
                .mavenLocalRepository(System.getProperty("mavenRepoPath")) //
                .waitStartTime(30) //
                .build(); //
        try
        {
            distributionTester.start("--create-startd", "--approve-all-licenses", "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,jmx-remote,servlet,servlets");
            distributionTester.stop();

            Path jettyBase = distributionTester.getJettyBase();

            File war = distributionTester.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + System.getProperty("jetty_version"));
            distributionTester.installWarFile(war, "test");
            distributionTester.start();
            distributionTester.assertLogsContains("Started @");
            distributionTester.assertUrlStatus("/test/index.jsp", 200);
            distributionTester.assertUrlContains("/test/index.jsp", "Hello");
        } finally {
            distributionTester.stop();
            distributionTester.cleanup();
        }

    }
}
