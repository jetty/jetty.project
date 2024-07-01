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

package org.eclipse.jetty.compression;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.thread.Invocable;

public class DynamicCompressionResponse extends Response.Wrapper  implements Callback, Invocable
{
    protected final Callback callback;
    protected final CompressionConfig config;

    public DynamicCompressionResponse(Request request, Response wrapped, Callback callback, CompressionConfig config)
    {
        super(request, wrapped);
        this.callback = callback;
        this.config = config;
    }
}
