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

import java.nio.ByteBuffer;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpVersion;

public interface Response
{
    Listener listener();

    HttpVersion version();

    int status();

    String reason();

    HttpFields headers();

    void abort();

    public interface Listener
    {
        public void onBegin(Response response);

        public void onHeaders(Response response);

        public void onContent(Response response, ByteBuffer content);

        public void onSuccess(Response response);

        public void onFailure(Response response, Throwable failure);

        public void onComplete(Result result);

        public static class Adapter implements Listener
        {
            @Override
            public void onBegin(Response response)
            {
            }

            @Override
            public void onHeaders(Response response)
            {
            }

            @Override
            public void onContent(Response response, ByteBuffer content)
            {
            }

            @Override
            public void onSuccess(Response response)
            {
            }

            @Override
            public void onFailure(Response response, Throwable failure)
            {
            }

            @Override
            public void onComplete(Result result)
            {
            }
        }
    }
}
