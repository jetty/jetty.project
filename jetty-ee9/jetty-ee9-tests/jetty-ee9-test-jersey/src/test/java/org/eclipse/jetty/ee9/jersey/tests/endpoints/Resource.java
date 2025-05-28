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

package org.eclipse.jetty.ee9.jersey.tests.endpoints;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;

@Path("resource")
public class Resource
{
    @PUT
    @Path("/security")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public void putSecurity(@Context HttpServletRequest httpRequest, Security security, @Suspended final AsyncResponse asyncResponse)
    {
        if (security.getPrincipal() == null)
            throw new NullPointerException("principal must no be null");
        if (security.getRoles() == null)
            throw new NullPointerException("roles must no be null");

        asyncResponse.resume("""
            {
                "response" : "ok"
            }
            """);
    }
}
