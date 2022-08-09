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

package org.eclipse.jetty.ee10.demos;

import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.client.api.ContentResponse;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class FastFileServerTest extends AbstractEmbeddedTest
{
    private static final String TEXT_CONTENT = "I am an old man and I have known a great " +
        "many troubles, but most of them never happened. - Mark Twain";
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

        //TODO fix me
        // server = FastFileServer.createServer(0, baseDir.toFile());
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    // FXME
    @Disabled
    @Test
    public void testGetSimpleText() throws Exception
    {
        URI uri = server.getURI().resolve("/simple.txt");
        ContentResponse response = client.newRequest(uri)
            .method(HttpMethod.GET)
            .send();
        assertThat("HTTP Response Status", response.getStatus(), is(HttpStatus.OK_200));

        // dumpResponseHeaders(response);

        HttpFields responseHeaders = response.getHeaders();

        assertThat("Content-Type", responseHeaders.get("Content-Type"), is("text/plain"));
        assertThat("Content-Length", responseHeaders.getLongField("Content-Length"),
            is((long)TEXT_CONTENT.length()));

        // test response content
        String responseBody = response.getContentAsString();
        assertThat("Response body", responseBody, is(TEXT_CONTENT));
    }
}
