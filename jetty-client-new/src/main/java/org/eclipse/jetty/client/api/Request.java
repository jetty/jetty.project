//========================================================================
//Copyright 2012-2012 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//The Eclipse Public License is available at
//http://www.eclipse.org/legal/epl-v10.html
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//You may elect to redistribute this code under either of these licenses.
//========================================================================

package org.eclipse.jetty.client.api;

import java.io.File;
import java.net.URI;
import java.util.concurrent.Future;

public interface Request
{
    Future<Response> send();

    Future<Response> send(Response.Listener listener);

    URI uri();

    void abort();

    /**
     * <p>A builder for requests</p>.
     */
    public interface Builder
    {
        Builder method(String method);

        Builder header(String name, String value);

        Builder listener(Request.Listener listener);

        Builder file(File file);

        Builder content(ContentProvider buffer);

        Builder decoder(ContentDecoder decoder);

        Builder param(String name, String value);

        Builder cookie(String key, String value);

        Builder authentication(Authentication authentication);

        Builder agent(String userAgent);

        Builder followRedirects(boolean follow);

        Request build();
    }

    public interface Listener
    {
        public void onQueue(Request request);

        public void onBegin(Request request);

        public void onHeaders(Request request);

        public void onFlush(Request request, int bytes);

        public void onComplete(Request request);

        public void onException(Request request, Exception exception);

        public void onEnd(Request request);

        public static class Adapter implements Listener
        {
            @Override
            public void onQueue(Request request)
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
            public void onComplete(Request request)
            {
            }

            @Override
            public void onException(Request request, Exception exception)
            {
            }

            @Override
            public void onEnd(Request request)
            {
            }
        }
    }
}
