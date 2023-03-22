//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.demos;

import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class OneServletContextWithSessionTest extends AbstractEmbeddedTest
{
    private static final String TEXT_CONTENT = "Do the right thing. It will gratify some people and astonish the rest. - Mark Twain";
    public WorkDir workDir;
    private Server server;

    @BeforeEach
    public void startServer() throws Exception
    {
        Path baseDir = workDir.getEmptyPathDir();

        Path textFile = baseDir.resolve("simple.txt");
        try (BufferedWriter writer = Files.newBufferedWriter(textFile, UTF_8))
        {
            writer.write(TEXT_CONTENT);
        }

        server = OneServletContextWithSession.createServer(0, ResourceFactory.root().newResource(baseDir));
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetHello() throws Exception
    {
        URI uri = server.getURI().resolve("/");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);
        String setCookieValue = response.getHeaders().get(HttpHeader.SET_COOKIE);
        assertThat("Set-Cookie value", setCookieValue, containsString("JSESSIONID="));

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response Content", responseBody,
            allOf(
                containsString("session.getId() = "),
                containsString("session.isNew() = true")
            )
        );
    }
}
