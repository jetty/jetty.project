//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

import java.util.concurrent.Future;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Fields;

public interface Request
{
    long id();

    String scheme();

    Request scheme(String scheme);

    String host();

    int port();

    HttpMethod method();

    Request method(HttpMethod method);

    String path();

    Request path(String path);

    HttpVersion version();

    Request version(HttpVersion version);

    Fields params();

    Request param(String name, String value);

    HttpFields headers();

    Request header(String name, String value);

    ContentProvider content();

    Request content(ContentProvider buffer);

    Request decoder(ContentDecoder decoder);

    Request cookie(String key, String value);

    String agent();

    Request agent(String userAgent);

    long idleTimeout();

    Request idleTimeout(long timeout);

    Request followRedirects(boolean follow);

    Listener listener();

    Request listener(Listener listener);

    Future<ContentResponse> send();

    void send(Response.Listener listener);

    public interface Listener
    {
        public void onQueued(Request request);

        public void onBegin(Request request);

        public void onHeaders(Request request);

        public void onFlush(Request request, int bytes);

        public void onSuccess(Request request);

        public void onFailure(Request request, Throwable failure);

        public static class Adapter implements Listener
        {
            @Override
            public void onQueued(Request request)
            {
            }

            @Override
            public void onBegin(Request request)
            {
            }

            @Override
            public void onHeaders(Request request)
            {
            }

            @Override
            public void onFlush(Request request, int bytes)
            {
            }

            @Override
            public void onSuccess(Request request)
            {
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
            }
        }
    }
}
