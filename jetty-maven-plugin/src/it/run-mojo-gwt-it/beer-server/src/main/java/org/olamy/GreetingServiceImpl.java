//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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
