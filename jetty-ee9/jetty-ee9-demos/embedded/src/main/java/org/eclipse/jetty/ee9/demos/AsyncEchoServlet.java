//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee9.demos;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class AsyncEchoServlet extends HttpServlet
{
    private static final long serialVersionUID = 1L;

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        AsyncContext asyncContext = request.startAsync(request, response);
        asyncContext.setTimeout(0);
        Echoer echoer = new Echoer(asyncContext);
        request.getInputStream().setReadListener(echoer);
        response.getOutputStream().setWriteListener(echoer);
    }

    private class Echoer implements ReadListener, WriteListener
    {
        private final byte[] buffer = new byte[4096];
        private final AsyncContext asyncContext;
        private final ServletInputStream input;
        private final ServletOutputStream output;
        private final AtomicBoolean complete = new AtomicBoolean(false);

        private Echoer(AsyncContext asyncContext) throws IOException
        {
            this.asyncContext = asyncContext;
            this.input = asyncContext.getRequest().getInputStream();
            this.output = asyncContext.getResponse().getOutputStream();
        }

        @Override
        public void onDataAvailable() throws IOException
        {
            handleAsyncIO();
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            handleAsyncIO();
        }

        @Override
        public void onWritePossible() throws IOException
        {
            handleAsyncIO();
        }

        private void handleAsyncIO() throws IOException
        {
            // This method is called:
            //   1) after first registering a WriteListener (ready for first write)
            //   2) after first registering a ReadListener iff write is ready
            //   3) when a previous write completes after an output.isReady() returns false
            //   4) from an input callback 

            // We should try to read, only if we are able to write!
            while (true)
            {
                if (!output.isReady())
                    // Don't even try to read anything until it is possible to write something,
                    // when onWritePossible will be called
                    break;

                if (!input.isReady())
                    // Nothing available to read, so wait for another call to onDataAvailable
                    break;

                int read = input.read(buffer);
                if (read < 0)
                {
                    if (complete.compareAndSet(false, true))
                        asyncContext.complete();
                    break;
                }
                else if (read > 0)
                {
                    output.write(buffer, 0, read);
                }
            }
        }

        @Override
        public void onError(Throwable failure)
        {
            new Throwable("onError", failure).printStackTrace();
            asyncContext.complete();
        }
    }
}
