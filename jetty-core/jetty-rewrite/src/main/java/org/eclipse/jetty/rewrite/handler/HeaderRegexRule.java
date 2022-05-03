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
import java.util.regex.Matcher;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.Name;

/**
 * <p>Puts or adds a response header whenever the rule matches a path regex pattern.</p>
 */
public class HeaderRegexRule extends RegexRule
{
    private String _headerName;
    private String _headerValue;
    private boolean _add;

    public HeaderRegexRule(@Name("regex") String regex, @Name("name") String name, @Name("value") String value)
    {
        super(regex);
        _headerName = name;
        _headerValue = value;
    }

    public String getHeaderName()
    {
        return _headerName;
    }

    public void setHeaderName(String name)
    {
        _headerName = name;
    }

    public String getHeaderValue()
    {
        return _headerValue;
    }

    public void setHeaderValue(String value)
    {
        _headerValue = value;
    }

    public boolean isAdd()
    {
        return _add;
    }

    /**
     * @param add true to add the response header, false to put the response header.
     */
    public void setAdd(boolean add)
    {
        _add = add;
    }

    @Override
    protected Request.WrapperProcessor apply(Request.WrapperProcessor input, Matcher matcher) throws IOException
    {
        return new Request.WrapperProcessor(input)
        {
            @Override
            public void process(Request ignored, Response response, Callback callback) throws Exception
            {
                if (isAdd())
                    response.addHeader(getHeaderName(), matcher.replaceAll(getHeaderValue()));
                else
                    response.setHeader(getHeaderName(), matcher.replaceAll(getHeaderValue()));
                super.process(ignored, response, callback);
            }
        };
    }

    @Override
    public String toString()
    {
        return "%s[header:%s=%s]".formatted(super.toString(), getHeaderName(), getHeaderValue());
    }
}
