//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.fcgi.parser;

import java.nio.ByteBuffer;

public abstract class ContentParser
{
    private final HeaderParser headerParser;

    protected ContentParser(HeaderParser headerParser)
    {
        this.headerParser = headerParser;
    }

    public abstract Result parse(ByteBuffer buffer);

    public void noContent()
    {
        throw new IllegalStateException();
    }

    protected int getRequest()
    {
        return headerParser.getRequest();
    }

    protected int getContentLength()
    {
        return headerParser.getContentLength();
    }

    public enum Result
    {
        PENDING, ASYNC, COMPLETE
    }
}
