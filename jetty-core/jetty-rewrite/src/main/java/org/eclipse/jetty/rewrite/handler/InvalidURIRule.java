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

import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Rule that protects against invalid unicode characters in URLs,
 * returning a configurable status code with body message.</p>
 * <p>The logic is as follows:</p>
 * <ul>
 * <li>if a decoded URI character is an iso control character, apply the rule</li>
 * <li>if no {@link Character.UnicodeBlock} is found for a decoded URI character, apply the rule</li>
 * <li>if a decoded URI character is in UnicodeBlock.SPECIALS, apply the rule</li>
 * </ul>
 */
public class InvalidURIRule extends Rule
{
    private static final Logger LOG = LoggerFactory.getLogger(InvalidURIRule.class);

    private int _code = HttpStatus.BAD_REQUEST_400;
    private String _message = "Illegal URI";

    @Override
    public boolean isTerminating()
    {
        return true;
    }

    public int getCode()
    {
        return _code;
    }

    /**
     * @param code the response code
     */
    public void setCode(int code)
    {
        _code = code;
    }

    public String getMessage()
    {
        return _message;
    }

    /**
     * <p>Sets the message for the response body (if the response code may have a body).</p>
     *
     * @param message the response message
     */
    public void setMessage(String message)
    {
        _message = message;
    }

    @Override
    public Processor matchAndApply(Processor input) throws IOException
    {
        String path = input.getHttpURI().getDecodedPath();

        int i = 0;
        while (i < path.length())
        {
            int codepoint = path.codePointAt(i);
            if (!isValidChar(codepoint))
                return apply(input);
            i += Character.charCount(codepoint);
        }

        return null;
    }

    private Processor apply(Processor input)
    {
        return new Processor(input)
        {
            @Override
            public boolean process(Response response, Callback callback)
            {
                String message = getMessage();
                if (StringUtil.isBlank(message))
                {
                    response.setStatus(getCode());
                    callback.succeeded();
                }
                else
                {
                    Response.writeError(this, response, callback, getCode(), message);
                }
                return true;
            }
        };
    }

    protected boolean isValidChar(int codepoint)
    {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codepoint);

        if (LOG.isDebugEnabled())
            LOG.debug("{} {} {} {}", Character.charCount(codepoint), codepoint, block, Character.isISOControl(codepoint));

        return (!Character.isISOControl(codepoint)) && block != null && !Character.UnicodeBlock.SPECIALS.equals(block);
    }

    @Override
    public String toString()
    {
        return super.toString() + "[" + _code + ":" + _message + "]";
    }
}
