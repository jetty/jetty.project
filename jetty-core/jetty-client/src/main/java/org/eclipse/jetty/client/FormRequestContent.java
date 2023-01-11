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

import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.eclipse.jetty.util.Fields;

/**
 * <p>A {@link Request.Content} for form uploads with the
 * "application/x-www-form-urlencoded" content type.</p>
 */
public class FormRequestContent extends StringRequestContent
{
    public FormRequestContent(Fields fields)
    {
        this(fields, StandardCharsets.UTF_8);
    }

    public FormRequestContent(Fields fields, Charset charset)
    {
        super("application/x-www-form-urlencoded", convert(fields, charset), charset);
    }

    public static String convert(Fields fields)
    {
        return convert(fields, StandardCharsets.UTF_8);
    }

    public static String convert(Fields fields, Charset charset)
    {
        // Assume 32 chars between name and value.
        StringBuilder builder = new StringBuilder(fields.getSize() * 32);
        for (Fields.Field field : fields)
        {
            for (String value : field.getValues())
            {
                if (builder.length() > 0)
                    builder.append("&");
                builder.append(encode(field.getName(), charset)).append("=").append(encode(value, charset));
            }
        }
        return builder.toString();
    }

    private static String encode(String value, Charset charset)
    {
        return URLEncoder.encode(value, charset);
    }
}
