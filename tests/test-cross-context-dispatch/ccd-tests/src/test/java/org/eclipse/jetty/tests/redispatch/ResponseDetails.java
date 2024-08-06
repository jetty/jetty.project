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

package org.eclipse.jetty.tests.redispatch;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Supplier;

import org.eclipse.jetty.client.ContentResponse;

class ResponseDetails implements Supplier<String>
{
    private final ContentResponse response;

    public ResponseDetails(ContentResponse response)
    {
        this.response = response;
    }

    @Override
    public String get()
    {
        try (StringWriter str = new StringWriter();
             PrintWriter out = new PrintWriter(str))
        {
            out.println(response.toString());
            out.println(response.getHeaders().toString());
            out.println(response.getContentAsString());
            out.flush();
            return str.toString();
        }
        catch (IOException e)
        {
            throw new RuntimeException("Unable to produce Response details", e);
        }
    }
}
