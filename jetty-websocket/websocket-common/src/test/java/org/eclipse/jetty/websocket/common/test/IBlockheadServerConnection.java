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

package org.eclipse.jetty.websocket.common.test;

import java.io.IOException;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.websocket.api.extensions.Frame;
import org.eclipse.jetty.websocket.common.Parser;

public interface IBlockheadServerConnection
{
    public void close() throws IOException;

    public void close(int statusCode) throws IOException;

    public void write(Frame frame) throws IOException;

    public List<String> upgrade() throws IOException;

    public void disconnect();

    public IncomingFramesCapture readFrames(int expectedCount, int timeoutDuration, TimeUnit timeoutUnit) throws IOException, TimeoutException;
    public void write(ByteBuffer buf) throws IOException;
    public List<String> readRequestLines() throws IOException;
    public String parseWebSocketKey(List<String> requestLines);
    public void respond(String rawstr) throws IOException;
    public String readRequest() throws IOException;
    public List<String> regexFind(List<String> lines, String pattern);
    public void echoMessage(int expectedFrames, int timeoutDuration, TimeUnit timeoutUnit) throws IOException, TimeoutException;
    public void setSoTimeout(int ms) throws SocketException;
    public ByteBufferPool getBufferPool();
    public int read(ByteBuffer buf) throws IOException;
    public Parser getParser();
    public IncomingFramesCapture getIncomingFrames();
    public void flush() throws IOException;
    public void write(int b) throws IOException;
    public void startEcho();
    public void stopEcho();
    
    /**
     * Add an extra header for the upgrade response (from the server). No extra work is done to ensure the key and value are sane for http.
     * @param rawkey the raw key
     * @param rawvalue the raw value
     */
    public void addResponseHeader(String rawkey, String rawvalue);
}