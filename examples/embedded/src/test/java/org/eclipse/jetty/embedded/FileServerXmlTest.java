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

package org.eclipse.jetty.embedded;

import java.io.BufferedWriter;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

@ExtendWith(WorkDirExtension.class)
public class FileServerXmlTest
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

        server = FileServerXml.createServer(0, baseDir);
        server.start();
    }

    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    @Test
    public void testGetSimpleText() throws IOException
    {
        URI uri = server.getURI().resolve("/simple.txt");
        HttpURLConnection http = (HttpURLConnection)uri.toURL().openConnection();
        assertThat("HTTP Response Status", http.getResponseCode(), is(HttpURLConnection.HTTP_OK));

        // HttpUtil.dumpResponseHeaders(http);

        assertThat("Content-Type", http.getHeaderField("Content-Type"), is("text/plain"));
        assertThat("Content-Length", http.getHeaderField("Content-Length"), is(Integer.toString(TEXT_CONTENT.length())));

        // test response content
        String responseBody = HttpUtil.getResponseBody(http);
        assertThat("Response body", responseBody, is(TEXT_CONTENT));
    }
}
