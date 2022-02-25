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

package org.eclipse.jetty.demos;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.util.resource.PathResource;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

public class SplitFileServerTest extends AbstractEmbeddedTest
{
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path path0 = Paths.get("src/test/resources/dir0");
        Path path1 = Paths.get("src/test/resources/dir1");
        Resource resource0 = new PathResource(path0);
        Resource resource1 = new PathResource(path1);

        server = SplitFileServer.createServer(0, resource0, resource1);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetTest0() throws Exception
    {
        URI uri = server.getURI().resolve("/test0.txt");
        ContentResponse response = client.GET(uri);
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("test0"));
    }

    @Test
    public void testGetTest1() throws Exception
    {
        URI uri = server.getURI().resolve("/test1.txt");
        ContentResponse response = client.GET(uri);
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody, containsString("test1"));
    }

    @Test
    public void testGetTest2() throws Exception
    {
        URI uri = server.getURI().resolve("/test2.txt");
        ContentResponse response = client.GET(uri);
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.NOT_FOUND_404));
    }
}
