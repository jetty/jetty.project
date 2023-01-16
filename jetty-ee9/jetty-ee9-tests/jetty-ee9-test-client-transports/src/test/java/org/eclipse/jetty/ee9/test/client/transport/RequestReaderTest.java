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

package org.eclipse.jetty.ee9.test.client.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.BytesRequestContent;
import org.eclipse.jetty.client.ContentResponse;
import org.eclipse.jetty.http.HttpStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class RequestReaderTest extends AbstractTest
{
    @ParameterizedTest
    @MethodSource("transports")
    public void testRecyclingWhenUsingReader(Transport transport) throws Exception
    {
        start(transport, new HttpServlet()
        {
            @Override
            protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
            {
                // Must be a Reader and not an InputStream.
                BufferedReader br = request.getReader();
                while (true)
                {
                    int b = br.read();
                    if (b == -1)
                        break;
                }
                // Paranoid check.
                assertThat(br.read(), is(-1));
            }
        });

        ContentResponse response1 = client.newRequest(newURI(transport))
            .method("POST")
            .timeout(5, TimeUnit.SECONDS)
            .body(new BytesRequestContent(new byte[512]))
            .send();
        assertThat(response1.getStatus(), is(HttpStatus.OK_200));

        // Send a 2nd request to make sure recycling works.
        ContentResponse response2 = client.newRequest(newURI(transport))
            .method("POST")
            .timeout(5, TimeUnit.SECONDS)
            .body(new BytesRequestContent(new byte[512]))
            .send();
        assertThat(response2.getStatus(), is(HttpStatus.OK_200));
    }
}
