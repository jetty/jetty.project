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

package org.eclipse.jetty.client.util;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

import org.eclipse.jetty.client.api.ContentProvider;
import org.eclipse.jetty.util.Fields;

/**
 * A {@link ContentProvider} for form uploads with the
 * "application/x-www-form-urlencoded" content type.
 */
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
        try
        {
            return URLEncoder.encode(value, charset.name());
        }
        catch (UnsupportedEncodingException x)
        {
            throw new UnsupportedCharsetException(charset.name());
        }
    }
}
