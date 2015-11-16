//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.embedded;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.servlet.AsyncContext;
import javax.servlet.ReadListener;
import javax.servlet.ServletException;
import javax.servlet.ServletInputStream;
import javax.servlet.ServletOutputStream;
import javax.servlet.WriteListener;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;


public class AsyncEchoServlet extends HttpServlet
{
    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
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
            onWritePossible();
        }

        @Override
        public void onAllDataRead() throws IOException
        {
            onWritePossible();
        }

        @Override
        public void onWritePossible() throws IOException
        {
            // This method is called:
            //   1) after first registering a WriteListener (ready for first write)
            //   2) after first registering a ReadListener iff write is ready
            //   3) when a previous write completes after an output.isReady() returns false
            //   4) from an input callback 
           
            // We should try to read, only if we are able to write!
            while (output.isReady() && input.isReady())
            {
                int read = input.read(buffer);
                if (read<0)
                {
                    if (complete.compareAndSet(false,true))
                        asyncContext.complete();
                    break;
                }
                else if (read>0)
                {
                    output.write(buffer, 0, read);
                }
            }
        }

        @Override
        public void onError(Throwable failure)
        {
            failure.printStackTrace();
            asyncContext.complete();
        }
    }
}
