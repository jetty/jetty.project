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

package org.eclipse.jetty.client;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * <p>A {@link Request.Content} for strings.</p>
 * <p>It is possible to specify, at the constructor, an encoding used to convert
 * the string into bytes, by default UTF-8.</p>
 */
public class StringRequestContent extends BytesRequestContent
{
    public StringRequestContent(String content)
    {
        this("text/plain;charset=UTF-8", content);
    }

    public StringRequestContent(String content, Charset encoding)
    {
        this("text/plain;charset=" + encoding.name(), content, encoding);
    }

    public StringRequestContent(String contentType, String content)
    {
        this(contentType, content, StandardCharsets.UTF_8);
    }

    public StringRequestContent(String contentType, String content, Charset encoding)
    {
        super(contentType, content.getBytes(encoding));
    }
}
