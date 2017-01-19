//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.net.URI;

import org.eclipse.jetty.client.api.Authentication;
import org.eclipse.jetty.client.api.AuthenticationStore;
import org.eclipse.jetty.client.api.Request;
import org.eclipse.jetty.client.util.BasicAuthentication;
import org.eclipse.jetty.client.util.DigestAuthentication;
import org.junit.Assert;
import org.junit.Test;

public class HttpAuthenticationStoreTest
{
    @Test
    public void testFindAuthenticationWithDefaultHTTPPort() throws Exception
    {
        AuthenticationStore store = new HttpAuthenticationStore();

        URI uri1 = URI.create("http://host:80");
        URI uri2 = URI.create("http://host");
        String realm = "realm";
        store.addAuthentication(new BasicAuthentication(uri1, realm, "user", "password"));

        Authentication result = store.findAuthentication("Basic", uri2, realm);
        Assert.assertNotNull(result);

        store.clearAuthentications();

        // Flip the URIs.
        uri1 = URI.create("https://server/");
        uri2 = URI.create("https://server:443/path");
        store.addAuthentication(new DigestAuthentication(uri1, realm, "user", "password"));
        result = store.findAuthentication("Digest", uri2, realm);
        Assert.assertNotNull(result);
    }

    @Test
    public void testFindAuthenticationResultWithDefaultHTTPPort() throws Exception
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
        Assert.assertNotNull(result);

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
        Assert.assertNotNull(result);
    }
}
