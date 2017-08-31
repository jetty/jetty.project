//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.client;

import java.io.IOException;
import java.net.CookieStore;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.Future;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.component.Container;
import org.eclipse.jetty.util.component.LifeCycle;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.core.extensions.WSExtensionRegistry;

public interface WebSocketClient extends LifeCycle, Container
{
    Future<Session> connect(Object websocket, URI toUri) throws IOException;

    Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request) throws IOException;

    Future<Session> connect(Object websocket, URI toUri, ClientUpgradeRequest request, UpgradeListener upgradeListener) throws IOException;

    long getAsyncWriteTimeout();

    void setAsyncWriteTimeout(long ms);

    SocketAddress getBindAddress();

    void setBindAddress(SocketAddress bindAddress);

    long getConnectTimeout();

    void setConnectTimeout(long ms);

    CookieStore getCookieStore();

    void setCookieStore(CookieStore cookieStore);

    WSExtensionRegistry getExtensionRegistry();

    HttpClient getHttpClient();

    long getMaxBinaryMessageSize();

    void setMaxBinaryMessageSize(long size);

    long getMaxIdleTimeout();

    void setMaxIdleTimeout(long ms);

    void setMaxTextMessageSize(long size);

    long getMaxTextMessageSize();

    Set<Session> getOpenSessions();

    SslContextFactory getSslContextFactory();
}
