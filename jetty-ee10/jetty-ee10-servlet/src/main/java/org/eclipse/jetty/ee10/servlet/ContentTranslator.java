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

package org.eclipse.jetty.ee10.servlet;

import org.eclipse.jetty.server.Content;
import org.eclipse.jetty.util.BufferUtil;

// TODO We should not need this class.  HttpInput needs to be updated to use the
public class ContentTranslator extends HttpInput.Content
{
    private final Content _content;

    public ContentTranslator(Content content)
    {
        super(content.getByteBuffer() == null ? BufferUtil.EMPTY_BUFFER : content.getByteBuffer());
        _content = content;
    }

    @Override
    public boolean isSpecial()
    {
        return _content.isSpecial();
    }

    @Override
    public boolean isEof()
    {
        return _content.isLast();
    }

    @Override
    public Throwable getError()
    {
        if (_content instanceof Content.Error)
            return ((Content.Error)_content).getCause();
        return null;
    }

    @Override
    public String toString()
    {
        return _content.toString();
    }

    @Override
    public void succeeded()
    {
        _content.release();
    }

    @Override
    public void failed(Throwable x)
    {
        _content.release();
    }
}
