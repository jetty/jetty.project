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

package org.eclipse.jetty.client.api;

public class Result
{
    private final Request request;
    private final Throwable requestFailure;
    private final Response response;
    private final Throwable responseFailure;

    public Result(Request request, Response response)
    {
        this(request, null, response, null);
    }

    public Result(Request request, Response response, Throwable responseFailure)
    {
        this(request, null, response, responseFailure);
    }

    public Result(Request request, Throwable requestFailure, Response response)
    {
        this(request, requestFailure, response, null);
    }

    private Result(Request request, Throwable requestFailure, Response response, Throwable responseFailure)
    {
        this.request = request;
        this.requestFailure = requestFailure;
        this.response = response;
        this.responseFailure = responseFailure;
    }

    public Request getRequest()
    {
        return request;
    }

    public Response getResponse()
    {
        return response;
    }

    public boolean isFailed()
    {
        return getFailure() != null;
    }

    public Throwable getFailure()
    {
        return responseFailure != null ? responseFailure : requestFailure;
    }
}
