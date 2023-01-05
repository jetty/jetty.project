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

package org.eclipse.jetty.ee10.maven.jettyrunner.it;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.HttpClient;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.hamcrest.MatcherAssert.assertThat;

public class IntegrationTestJettyRunner
{
    @Test
    public void testGet() throws Exception
    {
        String serverUri = findServerUri();
        HttpClient httpClient = new HttpClient();
        try
        {
            httpClient.start();
            ContentResponse response = httpClient.newRequest(serverUri).send();
            String res = response.getContentAsString();
            assertThat(res, Matchers.containsString("Hello World!"));
        }
        finally
        {
            httpClient.stop();
        }
    }

    private String findServerUri() throws Exception
    {
        long now = System.currentTimeMillis();

        while (System.currentTimeMillis() - now < MINUTES.toMillis(2))
        {
            Path portTxt = Paths.get("target", "server-uri.txt");
            if (Files.exists(portTxt))
            {
                List<String> lines = Files.readAllLines(portTxt);
                return lines.get(0);
            }
        }

        throw new Exception("cannot find started Jetty");
    }

}
