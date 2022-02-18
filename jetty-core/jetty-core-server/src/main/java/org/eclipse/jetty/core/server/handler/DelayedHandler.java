//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.core.server.handler;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.eclipse.jetty.core.server.Handler;
import org.eclipse.jetty.core.server.Request;
import org.eclipse.jetty.core.server.Response;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

public abstract class DelayedHandler extends Handler.Wrapper
{
    protected abstract Response accept(Request request);

    protected abstract void schedule(Request request, Runnable handle);

    @Override
    public void handle(Request request) throws Exception
    {
        // Defer to extended class to accept the request
        Response response = accept(request);

        // If not accepted, then handle normally
        if (response == null)
            super.handle(request);
        else
        {
            // If accepted then wrap with a DelayedRequest and schedule it for later execution
            DelayedRequest delayedRequest = new DelayedRequest(request, response);
            schedule(delayedRequest, delayedRequest);
        }
    }

    private class DelayedRequest extends Request.Wrapper implements Runnable, Invocable
    {
        final AtomicBoolean _accepted = new AtomicBoolean();
        final Response _response;

        public DelayedRequest(Request request, Response response)
        {
            super(request, response);
            _response = response;
        }

        @Override
        public Response accept()
        {
            if (_accepted.compareAndSet(false, true))
                return _response;
            return null;
        }

        @Override
        public Response getResponse()
        {
            return _accepted.get() ? _response : null;
        }

        @Override
        public boolean isAccepted()
        {
            return _accepted.get();
        }

        @Override
        public void run()
        {
            try
            {
                // run is called when the delayed request is to be handled
                getHandler().handle(this);

                // If the wrapped request was not accepted by the delayed handling, then
                if (!isAccepted())
                {
                    // Look for a default handler to pass the request to.
                    // TODO make this either configurable and/or more flexible
                    List<DefaultHandler> defaultHandlers = getServer().getChildHandlersByClass(DefaultHandler.class);
                    if (defaultHandlers.size() == 1)
                        defaultHandlers.get(0).handle(this);

                    // If still not accepted, just 404 it
                    if (!isAccepted())
                        _response.writeError(HttpStatus.NOT_FOUND_404, _response.getCallback());
                }
            }
            catch (Exception e)
            {
                _response.getCallback().failed(e);
            }
        }
    }

    public static class OnContent extends DelayedHandler
    {
        @Override
        protected Response accept(Request request)
        {
            // Accept the request if there is content
            if (request.getContentLength() > 0 || request.getHeaders().contains(HttpHeader.CONTENT_TYPE))
                return request.accept();
            return null;
        }

        @Override
        protected void schedule(Request request, Runnable handle)
        {
            request.demandContent(handle);
        }
    }

    public static class QualityOfService extends DelayedHandler
    {
        private final int _maxPermits;
        private final Queue<Request> _queue = new ArrayDeque<>();
        private int _permits;

        public QualityOfService(int permits)
        {
            _maxPermits = permits;
        }

        @Override
        protected Response accept(Request request)
        {
            // Always accept the request, if not already accepted
            Response response = request.accept();
            if (response == null)
                return null;
            // wrap the response so that completion can be intercepted
            return new QoSResponse(request, response);
        }

        @Override
        protected void schedule(Request request, Runnable handle)
        {
            boolean accepted;
            synchronized (this)
            {
                accepted = _permits < _maxPermits;
                if (accepted)
                    _permits++;
                else
                    _queue.add(request);
            }
            if (accepted)
                handle.run();
        }

        private class QoSResponse extends Response.Wrapper implements Callback
        {
            public QoSResponse(Request request, Response response)
            {
                super(request, response);
            }

            @Override
            public void succeeded()
            {
                release();
                getWrapped().getCallback().succeeded();
            }

            @Override
            public void failed(Throwable x)
            {
                release();
                getWrapped().getCallback().failed(x);
            }

            @Override
            public Callback getCallback()
            {
                return this;
            }

            private void release()
            {
                Request request;
                synchronized (QualityOfService.this)
                {
                    request = _queue.poll();
                    if (request == null)
                        _permits--;
                }
                if (request instanceof DelayedRequest delayed)
                    request.execute(delayed);
            }
        }
    }
}
