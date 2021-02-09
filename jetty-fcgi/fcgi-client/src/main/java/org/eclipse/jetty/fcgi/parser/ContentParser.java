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
