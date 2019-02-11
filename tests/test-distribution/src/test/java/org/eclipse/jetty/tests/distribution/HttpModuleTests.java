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

import org.junit.jupiter.api.Test;

import java.io.File;

public class HttpModuleTests
{
    @Test
    public void http_module() throws Exception
    {

        try(DistributionTester distributionTester = DistributionTester.Builder.newInstance() //
            .jettyVersion(System.getProperty("jetty_version")) //
            .mavenLocalRepository(System.getProperty("mavenRepoPath")) //
            .waitStartTime(30) //
            .build())
        {
            distributionTester.start("--create-startd", "--approve-all-licenses", "--add-to-start=resources,server,http,webapp,deploy,jsp,jmx,jmx-remote,servlet,servlets");
            distributionTester.stop();

            File war = distributionTester.resolveArtifact("org.eclipse.jetty.tests:test-simple-webapp:war:" + System.getProperty("jetty_version"));
            distributionTester.installWarFile(war, "test");
            distributionTester.start()  //
                        .assertLogsContains("Started @") //
                        .assertUrlStatus("/test/index.jsp", 200) //
                        .assertUrlContains("/test/index.jsp", "Hello");
        }

    }
}
