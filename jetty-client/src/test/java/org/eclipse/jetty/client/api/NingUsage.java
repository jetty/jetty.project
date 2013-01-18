//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;

import org.junit.Ignore;
import org.junit.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.BodyDeferringAsyncHandler;
import com.ning.http.client.Cookie;
import com.ning.http.client.Realm;
import com.ning.http.client.Request;
import com.ning.http.client.Response;

@Ignore
public class NingUsage
{
    @Test
    public void testFileUpload() throws Exception
    {
        AsyncHttpClient client = new AsyncHttpClient();
        Request request = client.prepareGet("http://localhost:8080/foo").setBody(new FileInputStream("")).build();
        client.executeRequest(request);
    }

    @Test
    public void testAuthentication() throws Exception
    {
        AsyncHttpClient client = new AsyncHttpClient();
        Response response = client.prepareGet("http://localhost:8080/foo")
                // Not sure what a builder buys me in this case...
                .setRealm(new Realm.RealmBuilder().build()).execute().get();
    }

    @Test
    public void testCookies() throws Exception
    {
        AsyncHttpClient client = new AsyncHttpClient();
        // Cookie class too complex
        client.prepareGet("").addCookie(new Cookie("domain", "name", "value", "path", 3600, false)).execute();
    }

    @Test
    public void testResponseStream() throws Exception
    {
        AsyncHttpClient client = new AsyncHttpClient();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BodyDeferringAsyncHandler handler = new BodyDeferringAsyncHandler(output);
        client.prepareGet("").execute(handler);
        // What I would really like is an InputStream, not an OutputStream
        // so I can read the response content

        Response response = handler.getResponse(); // No timeout

        // Not sure how I can read the body ONLY if status == 200 ?
    }
}
