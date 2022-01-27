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

package org.eclipse.jetty.websocket.core.client.internal;

import org.eclipse.jetty.client.HttpClient;

public interface HttpClientProvider
{
    static HttpClient get()
    {
        HttpClientProvider xmlProvider = new XmlHttpClientProvider();
        HttpClient client = xmlProvider.newHttpClient();
        if (client != null)
            return client;

        return HttpClientProvider.newDefaultHttpClient();
    }

    private static HttpClient newDefaultHttpClient()
    {
        return new HttpClient();
    }

    default HttpClient newHttpClient()
    {
        return newDefaultHttpClient();
    }
}
