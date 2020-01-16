//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server;

import java.io.IOException;
import javax.servlet.ReadListener;

public class HttpInputOverHTTP2 extends HttpInput
{
    private final byte[] _oneByteBuffer = new byte[1];
    private final HttpChannelState _channelState;

    public HttpInputOverHTTP2(HttpChannelState state)
    {
        _channelState = state;
    }

    /* HttpInput */

    @Override
    public void recycle()
    {

    }

    @Override
    public Interceptor getInterceptor()
    {
        return null;
    }

    @Override
    public void setInterceptor(Interceptor interceptor)
    {

    }

    @Override
    public void addInterceptor(Interceptor interceptor)
    {

    }

    @Override
    public void asyncReadProduce() throws IOException
    {

    }

    @Override
    public boolean addContent(Content content)
    {
        return false;
    }

    @Override
    public boolean hasContent()
    {
        return false;
    }

    @Override
    public void unblock()
    {

    }

    @Override
    public long getContentLength()
    {
        return 0;
    }

    @Override
    public boolean earlyEOF()
    {
        return false;
    }

    @Override
    public boolean eof()
    {
        return false;
    }

    @Override
    public boolean consumeAll()
    {
        return false;
    }

    @Override
    public boolean isError()
    {
        return false;
    }

    @Override
    public boolean isAsync()
    {
        return false;
    }

    @Override
    public boolean onIdleTimeout(Throwable x)
    {
        return false;
    }

    @Override
    public boolean failed(Throwable x)
    {
        return false;
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        return false;
    }

    @Override
    public boolean isReady()
    {
        return false;
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {

    }

    @Override
    public int read() throws IOException
    {
        int read = read(_oneByteBuffer, 0, 1);
        if (read == 0)
            throw new IllegalStateException("unready read=0");
        return read < 0 ? -1 : _oneByteBuffer[0] & 0xFF;
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        return 0;
    }

    @Override
    public int available() throws IOException
    {
        return 0;
    }

    /* Runnable */

    @Override
    public void run()
    {

    }
}
