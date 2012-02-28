/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import java.util.concurrent.Executor;

import org.eclipse.jetty.npn.NextProtoNego;
import org.eclipse.jetty.spdy.api.server.ServerSessionFrameListener;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.junit.Before;

public class SSLSynReplyTest extends SynReplyTest
{
    @Override
    protected SPDYServerConnector newSPDYServerConnector(ServerSessionFrameListener listener)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYServerConnector(listener, sslContextFactory);
    }

    @Override
    protected SPDYClient.Factory newSPDYClientFactory(Executor threadPool)
    {
        SslContextFactory sslContextFactory = newSslContextFactory();
        return new SPDYClient.Factory(threadPool, sslContextFactory);
    }

    @Before
    public void init()
    {
        NextProtoNego.debug = true;
    }
}
