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

package org.eclipse.jetty.client;

import java.net.URI;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class HttpAuthenticationStoreTest
{
    @Test
    public void testFindAuthenticationWithDefaultHTTPPort()
    {
        AuthenticationStore store = new HttpAuthenticationStore();

        URI uri1 = URI.create("http://host:80");
        URI uri2 = URI.create("http://host");
        String realm = "realm";
        store.addAuthentication(new BasicAuthentication(uri1, realm, "user", "password"));

        Authentication result = store.findAuthentication("Basic", uri2, realm);
        assertNotNull(result);

        store.clearAuthentications();

        // Flip the URIs.
        uri1 = URI.create("https://server/");
        uri2 = URI.create("https://server:443/path");
        store.addAuthentication(new DigestAuthentication(uri1, realm, "user", "password"));
        result = store.findAuthentication("Digest", uri2, realm);
        assertNotNull(result);
    }

    @Test
    public void testFindAuthenticationResultWithDefaultHTTPPort()
    {
        AuthenticationStore store = new HttpAuthenticationStore();

        store.addAuthenticationResult(new Authentication.Result()
        {
            @Override
            public URI getURI()
            {
                return URI.create("http://host:80");
            }

            @Override
            public void apply(Request request)
            {
            }
        });

        URI uri2 = URI.create("http://host");
        Authentication.Result result = store.findAuthenticationResult(uri2);
        assertNotNull(result);

        store.clearAuthenticationResults();

        // Flip the URIs.
        store.addAuthenticationResult(new Authentication.Result()
        {
            @Override
            public URI getURI()
            {
                return URI.create("https://server/");
            }

            @Override
            public void apply(Request request)
            {
            }
        });

        uri2 = URI.create("https://server:443/path");
        result = store.findAuthenticationResult(uri2);
        assertNotNull(result);
    }
}
