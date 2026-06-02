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

package org.eclipse.jetty.client.transport;

import java.net.URI;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HttpRequestTest
{
    @Test
    void forwardVersionOnCopy() throws Exception
    {
        try (final var client = new HttpClient())
        {
            final var req = new HttpRequest(client, null, URI.create("http://localhost:1234"));
            assertEquals(HttpVersion.HTTP_1_1, req.getVersion());

            assertEquals(HttpVersion.HTTP_1_1, req.copy(URI.create("http://localhost:4567")).getVersion());

            req.useVersion(HttpVersion.HTTP_2);
            assertEquals(HttpVersion.HTTP_2, req.copy(URI.create("http://localhost:4567")).getVersion());
        }
    }
}
