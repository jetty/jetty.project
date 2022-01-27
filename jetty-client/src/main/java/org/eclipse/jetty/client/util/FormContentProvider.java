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
import org.eclipse.jetty.util.Fields;

/**
 * A {@link ContentProvider} for form uploads with the
 * "application/x-www-form-urlencoded" content type.
 *
 * @deprecated use {@link FormRequestContent} instead.
 */
@Deprecated
public class FormContentProvider extends StringContentProvider
{
    public FormContentProvider(Fields fields)
    {
        this(fields, StandardCharsets.UTF_8);
    }

    public FormContentProvider(Fields fields, Charset charset)
    {
        super("application/x-www-form-urlencoded", convert(fields, charset), charset);
    }

    public static String convert(Fields fields)
    {
        return convert(fields, StandardCharsets.UTF_8);
    }

    public static String convert(Fields fields, Charset charset)
    {
        return FormRequestContent.convert(fields, charset);
    }
}
