//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.common.test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.websocket.common.WebSocketFrame;

/**
 * Interface for BlockheadClient.
 */
public interface IBlockheadClient extends AutoCloseable
{
    public void addExtensions(String xtension);

    public void addHeader(String header);

    public boolean awaitDisconnect(long timeout, TimeUnit unit) throws InterruptedException;

    @Override
    public void close();

    public void close(int statusCode, String message);

    public void connect() throws IOException;

    public void disconnect();

    public void expectServerDisconnect();

    public HttpResponse expectUpgradeResponse() throws IOException;

    public InetSocketAddress getLocalSocketAddress();

    public String getProtocols();

    public InetSocketAddress getRemoteSocketAddress();

    public LinkedBlockingQueue<WebSocketFrame> getFrameQueue();

    public HttpResponse readResponseHeader() throws IOException;

    public void sendStandardRequest() throws IOException;

    public void setConnectionValue(String connectionValue);

    public void setProtocols(String protocols);

    public void setTimeout(int duration, TimeUnit unit);

    public void write(WebSocketFrame frame) throws IOException;

    public void writeRaw(ByteBuffer buf) throws IOException;

    public void writeRaw(ByteBuffer buf, int numBytes) throws IOException;

    public void writeRaw(String str) throws IOException;

    public void writeRawSlowly(ByteBuffer buf, int segmentSize) throws IOException;
}
