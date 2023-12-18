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

package org.eclipse.jetty.ee10.test.client.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.IO;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

public class ZippedRequestContentTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transportsNoFCGI")
    public void testZippedRequestContent(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest req, HttpServletResponse resp) throws IOException
            {
                InputStream input = req.getInputStream();
                try (ZipInputStream zipInput = new ZipInputStream(input))
                {
                    ZipEntry zipEntry1 = zipInput.getNextEntry();
                    assertNotNull(zipEntry1);
                    assertEquals("first.txt", zipEntry1.getName());
                    IO.copy(zipInput, OutputStream.nullOutputStream());
                    ZipEntry zipEntry2 = zipInput.getNextEntry();
                    assertNotNull(zipEntry2);
                    assertEquals("second.txt", zipEntry2.getName());
                    IO.copy(zipInput, OutputStream.nullOutputStream());
                    assertNull(zipInput.getNextEntry());
                    IO.copy(input, OutputStream.nullOutputStream());
                }

                resp.setStatus(HttpStatus.OK_200);
            }
        });

        OutputStreamRequestContent content = new OutputStreamRequestContent();
        CompletableFuture<ContentResponse> completable = new CompletableResponseListener(
            client.newRequest(newURI(transport))
                .method(HttpMethod.POST)
                .body(content)
        ).send();

        OutputStream output = content.getOutputStream();
        try (ZipOutputStream zipOutput = new ZipOutputStream(output))
        {
            zipOutput.putNextEntry(new ZipEntry("first.txt"));
            zipOutput.write("Hello!".repeat(128).getBytes(StandardCharsets.UTF_8));
            zipOutput.closeEntry();
            zipOutput.putNextEntry(new ZipEntry("second.txt"));
            zipOutput.write("Jetty!".repeat(128).getBytes(StandardCharsets.UTF_8));
            zipOutput.closeEntry();
        }

        ContentResponse response = completable.get(15, TimeUnit.SECONDS);
        assertEquals(HttpStatus.OK_200, response.getStatus());
    }
}
