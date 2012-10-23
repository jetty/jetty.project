//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client;

import java.nio.ByteBuffer;

import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;

public class DoubleResponseListener implements Response.Listener
{
    private final ResponseNotifier responseNotifier;
    private final Response.Listener listener1;
    private final Response.Listener listener2;

    public DoubleResponseListener(ResponseNotifier responseNotifier, Response.Listener listener1, Response.Listener listener2)
    {
        this.responseNotifier = responseNotifier;
        this.listener1 = listener1;
        this.listener2 = listener2;
    }

    @Override
    public void onBegin(Response response)
    {
        responseNotifier.notifyBegin(listener1, response);
        responseNotifier.notifyBegin(listener2, response);
    }

    @Override
    public void onHeaders(Response response)
    {
        responseNotifier.notifyHeaders(listener1, response);
        responseNotifier.notifyHeaders(listener2, response);
    }

    @Override
    public void onContent(Response response, ByteBuffer content)
    {
        responseNotifier.notifyContent(listener1, response, content);
        responseNotifier.notifyContent(listener2, response, content);
    }

    @Override
    public void onSuccess(Response response)
    {
        responseNotifier.notifySuccess(listener1, response);
        responseNotifier.notifySuccess(listener2, response);
    }

    @Override
    public void onFailure(Response response, Throwable failure)
    {
        responseNotifier.notifyFailure(listener1, response, failure);
        responseNotifier.notifyFailure(listener2, response, failure);
    }

    @Override
    public void onComplete(Result result)
    {
        responseNotifier.notifyComplete(listener1, result);
        responseNotifier.notifyComplete(listener2, result);
    }
}
