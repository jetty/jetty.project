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

import org.eclipse.jetty.util.thread.AutoLock;

public abstract class AbstractLockedHttpInput extends AbstractHttpInput
{
    public AbstractLockedHttpInput(HttpChannelState state)
    {
        super(state);
    }

    /* HttpInput */

    @Override
    public void recycle()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.recycle();
        }
    }

    @Override
    public Interceptor getInterceptor()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.getInterceptor();
        }
    }

    @Override
    public void setInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.setInterceptor(interceptor);
        }
    }

    @Override
    public void addInterceptor(Interceptor interceptor)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.addInterceptor(interceptor);
        }
    }

    @Override
    public void asyncReadProduce()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.asyncReadProduce();
        }
    }

    @Override
    public void addContent(Content content)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.addContent(content);
        }
    }

    @Override
    public boolean hasContent()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.hasContent();
        }
    }

    @Override
    public long getContentLength()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.getContentLength();
        }
    }

    @Override
    public boolean earlyEOF()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.earlyEOF();
        }
    }

    @Override
    public boolean eof()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.eof();
        }
    }

    @Override
    public boolean consumeAll()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.consumeAll();
        }
    }

    @Override
    public boolean isError()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.isError();
        }
    }

    @Override
    public boolean isAsync()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.isAsync();
        }
    }

    @Override
    public boolean onIdleTimeout(Throwable x)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.onIdleTimeout(x);
        }
    }

    @Override
    public boolean failed(Throwable x)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.failed(x);
        }
    }

    /* ServletInputStream */

    @Override
    public boolean isFinished()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.isFinished();
        }
    }

    @Override
    public boolean isReady()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.isReady();
        }
    }

    @Override
    public void setReadListener(ReadListener readListener)
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.setReadListener(readListener);
        }
    }

    @Override
    public int read() throws IOException
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.read();
        }
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.read(b, off, len);
        }
    }

    @Override
    public int available()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            return super.available();
        }
    }

    /* Runnable */

    @Override
    public void run()
    {
        try (AutoLock lock = _contentLock.lock())
        {
            super.run();
        }
    }
}
