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

package org.olamy;

import com.google.gwt.user.server.rpc.RemoteServiceServlet;

/**
 * The server side implementation of the RPC service.
 */
@SuppressWarnings("serial")
public class GreetingServiceImpl extends RemoteServiceServlet implements
    GreetingService
{

    public GreetingResponse greetServer(String input) throws IllegalArgumentException
    {
        // Verify that the input is valid.
        if (!FieldVerifier.isValidName(input))
        {
            // If the input is not valid, throw an IllegalArgumentException back to
            // the client.
            throw new IllegalArgumentException(
                "Name must be at least 4 characters long");
        }

        GreetingResponse response = new GreetingResponse();

        response.setServerInfo(getServletContext().getServerInfo());
        response.setUserAgent(getThreadLocalRequest().getHeader("User-Agent"));

        response.setGreeting("Hello, " + input + "!");

        return response;
    }
}
