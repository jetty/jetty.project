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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;

import org.eclipse.jetty.server.Request;

/**
 * <p>Abstract rule that matches against request headers.</p>
 */
public abstract class HeaderRule extends Rule
{
    private String _headerName;
    private String _headerValue;

    public String getHeaderName()
    {
        return _headerName;
    }

    public void setHeaderName(String header)
    {
        _headerName = header;
    }

    public String getHeaderValue()
    {
        return _headerValue;
    }

    /**
     * @param headerValue the header value to match against.
     * If {@code null}, then the presence of the header is enough to match
     */
    public void setHeaderValue(String headerValue)
    {
        _headerValue = headerValue;
    }

    @Override
    public Request.WrapperProcessor matchAndApply(Request.WrapperProcessor input) throws IOException
    {
        String value = input.getHeaders().get(getHeaderName());
        if (value == null)
            return null;
        String headerValue = getHeaderValue();
        if (headerValue == null || headerValue.equals(value))
            return apply(input, value);
        return null;
    }

    /**
     * <p>Invoked after the header matched the {@code Request} headers to apply the rule's logic.</p>
     *
     * @param input the input {@code Request} and {@code Processor}
     * @param value the header value
     * @return the possibly wrapped {@code Request} and {@code Processor}
     * @throws IOException if applying the rule failed
     */
    protected abstract Request.WrapperProcessor apply(Request.WrapperProcessor input, String value) throws IOException;

    @Override
    public String toString()
    {
        return "%s[header:%s=%s]".formatted(super.toString(), getHeaderName(), getHeaderValue());
    }
}
