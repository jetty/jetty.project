//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.docs.programming.client;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.thread.Invocable;

@SuppressWarnings("unused")
public class ClientDocs
{
    public void transportInvocationType() throws Exception
    {
        // tag::transportInvocationType[]
        HttpClient httpClient = new HttpClient();

        // Configure the InvocationType for the response listeners.
        httpClient.getHttpClientTransport().setInvocationType(Invocable.InvocationType.NON_BLOCKING);

        httpClient.start();
        // end::transportInvocationType[]
    }

    public void demandInvocationType() throws Exception
    {
        // tag::demandInvocationType[]
        HttpClient httpClient = new HttpClient();
        httpClient.getHttpClientTransport().setInvocationType(Invocable.InvocationType.NON_BLOCKING); // <1>
        httpClient.start();

        httpClient.newRequest("http://domain.com/path")
            .onResponseContentSource((response, contentSource) ->
            {
                contentSource.demand(new Invocable.Task.Abstract(Invocable.InvocationType.NON_BLOCKING) // <2>
                {
                    @Override
                    public void run()
                    {
                        while (true)
                        {
                            Content.Chunk chunk = contentSource.read();

                            if (chunk == null)
                            {
                                contentSource.demand(this); // <2>
                                return;
                            }

                            if (Content.Chunk.isFailure(chunk))
                            {
                                response.abort(chunk.getFailure());
                                return;
                            }

                            // Process the Chunk in non-blocking way.
                            processNonBlocking(chunk);

                            chunk.release();

                            if (chunk.isLast())
                                return;
                        }
                    }
                });
            }).send(null);
        // end::demandInvocationType[]
    }

    private static void processNonBlocking(Content.Chunk chunk)
    {
    }
}
