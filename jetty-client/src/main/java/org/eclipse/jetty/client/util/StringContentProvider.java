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

package org.eclipse.jetty.client.util;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.client.api.ContentProvider;

/**
 * A {@link ContentProvider} for strings.
 * <p>
 * It is possible to specify, at the constructor, an encoding used to convert
 * the string into bytes, by default UTF-8.
 *
 * @deprecated use {@link StringRequestContent} instead.
 */
@Deprecated
public class StringContentProvider extends BytesContentProvider
{
    public StringContentProvider(String content)
    {
        this(content, StandardCharsets.UTF_8);
    }

    public StringContentProvider(String content, String encoding)
    {
        this(content, Charset.forName(encoding));
    }

    public StringContentProvider(String content, Charset charset)
    {
        this("text/plain;charset=" + charset.name(), content, charset);
    }

    public StringContentProvider(String contentType, String content, Charset charset)
    {
        super(contentType, content.getBytes(charset));
    }
}
