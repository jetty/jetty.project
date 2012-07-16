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

import java.io.InputStream;
import java.nio.ByteBuffer;

public interface Response
{
    int getStatus();

    Headers getHeaders();

    Request getRequest();

    ContentProvider content();

    InputStream contentAsStream();

    void abort();

    public interface Listener
    {
        public boolean onBegin(Response response, String version, int code, String message);

        public boolean onHeader(Response response, String name, String value);

        public boolean onHeaders(Response response);

        public boolean onContent(Response response, ByteBuffer content);

        public boolean onTrailer(Response response, String name, String value);

        public void onComplete(Response response);

        public void onException(Response response, Exception exception);

        public void onEnd(Response response);

        public static class Adapter implements Listener
        {
            @Override
            public boolean onBegin(Response response, String version, int code, String message)
            {
                return false;
            }

            @Override
            public boolean onHeader(Response response, String name, String value)
            {
                return false;
            }

            @Override
            public boolean onHeaders(Response response)
            {
                return false;
            }

            @Override
            public boolean onContent(Response response, ByteBuffer content)
            {
                return false;
            }

            @Override
            public boolean onTrailer(Response response, String name, String value)
            {
                return false;
            }

            @Override
            public void onComplete(Response response)
            {
            }

            @Override
            public void onException(Response response, Exception exception)
            {
            }

            @Override
            public void onEnd(Response response)
            {
            }
        }
    }
}
