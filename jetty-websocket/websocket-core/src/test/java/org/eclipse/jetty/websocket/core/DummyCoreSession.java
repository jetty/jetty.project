//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.core;

import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.util.AttributesMap;
import org.eclipse.jetty.util.Callback;

import java.net.SocketAddress;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

public class DummyCoreSession implements FrameHandler.CoreSession
{
    @Override
    public String getNegotiatedSubProtocol()
    {
        return null;
    }

    @Override
    public List<ExtensionConfig> getNegotiatedExtensions()
    {
        return null;
    }

    @Override
    public Map<String, List<String>> getParameterMap()
    {
        return null;
    }

    @Override
    public String getProtocolVersion()
    {
        return null;
    }

    @Override
    public URI getRequestURI()
    {
        return null;
    }

    @Override
    public boolean isSecure()
    {
        return false;
    }

    @Override
    public void abort()
    {

    }

    @Override
    public Behavior getBehavior()
    {
        return null;
    }

    @Override
    public ByteBufferPool getByteBufferPool()
    {
        return null;
    }

    @Override
    public SocketAddress getLocalAddress()
    {
        return null;
    }

    @Override
    public SocketAddress getRemoteAddress()
    {
        return null;
    }

    @Override
    public boolean isOpen()
    {
        return false;
    }

    @Override
    public Duration getIdleTimeout()
    {
        return Duration.ZERO;
    }

    @Override
    public void setIdleTimeout(Duration timeout)
    {

    }

    @Override
    public void flush(Callback callback)
    {

    }

    @Override
    public void close(Callback callback)
    {

    }

    @Override
    public void close(int statusCode, String reason, Callback callback)
    {

    }

    @Override
    public void demand(long n)
    {

    }

    @Override
    public boolean isAutoFragment()
    {
        return false;
    }

    @Override
    public void setAutoFragment(boolean autoFragment)
    {

    }

    @Override
    public long getMaxFrameSize()
    {
        return 0;
    }

    @Override
    public void setMaxFrameSize(long maxFrameSize)
    {

    }

    @Override
    public int getOutputBufferSize()
    {
        return 0;
    }

    @Override
    public void setOutputBufferSize(int outputBufferSize)
    {

    }

    @Override
    public int getInputBufferSize()
    {
        return 0;
    }

    @Override
    public void setInputBufferSize(int inputBufferSize)
    {

    }

    @Override
    public void sendFrame(Frame frame, Callback callback, boolean batch)
    {

    }
}
