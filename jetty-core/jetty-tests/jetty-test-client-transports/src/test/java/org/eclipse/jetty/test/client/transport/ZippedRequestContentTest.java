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

package org.eclipse.jetty.test.client.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.eclipse.jetty.client.CompletableResponseListener;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.client.OutputStreamRequestContent;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
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
        start(transport, new Handler.Abstract()
        {
            @Override
            public boolean handle(Request request, Response response, Callback callback) throws Exception
            {
                InputStream input = Content.Source.asInputStream(request);
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

                response.setStatus(HttpStatus.OK_200);
                callback.succeeded();
                return true;
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
