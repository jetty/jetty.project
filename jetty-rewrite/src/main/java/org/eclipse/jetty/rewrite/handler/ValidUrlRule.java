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
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.URIUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This rule can be used to protect against invalid unicode characters in a url making it into applications.
 * <p>
 * The logic is as follows.
 * <ul>
 * <li>if decoded uri character is an iso control character return code/reason</li>
 * <li>if no UnicodeBlock is found for character return code/reason</li>
 * <li>if character is in UnicodeBlock.SPECIALS return code/reason</li>
 * </ul>
 */
public class ValidUrlRule extends Rule
{
    private static final Logger LOG = LoggerFactory.getLogger(ValidUrlRule.class);

    String _code = "400";
    String _message = "Illegal Url";

    public ValidUrlRule()
    {
        _handling = true;
        _terminating = true;
    }

    /**
     * Sets the response status code.
     *
     * @param code response code
     */
    public void setCode(String code)
    {
        _code = code;
    }

    /**
     * Sets the message for the {@link org.eclipse.jetty.server.Response#sendError(int, String)} method.
     *
     * @param message the message
     */
    public void setMessage(String message)
    {
        _message = message;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        // best to decide the request uri and validate that
        // String uri = request.getRequestURI();
        String uri = URIUtil.decodePath(request.getRequestURI());

        for (int i = 0; i < uri.length(); )
        {
            int codepoint = uri.codePointAt(i);

            if (!isValidChar(uri.codePointAt(i)))
            {

                int code = Integer.parseInt(_code);

                // status code 400 and up are error codes so include a reason
                if (_message != null && !_message.isEmpty())
                    response.sendError(code, _message);
                else
                    response.setStatus(code);

                // we have matched, return target and consider it is handled
                return target;
            }
            i += Character.charCount(codepoint);
        }

        // we have not matched so return null
        return null;
    }

    protected boolean isValidChar(int codepoint)
    {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codepoint);

        LOG.debug("{} {} {} {}", Character.charCount(codepoint), codepoint, block, Character.isISOControl(codepoint));

        return (!Character.isISOControl(codepoint)) && block != null && !Character.UnicodeBlock.SPECIALS.equals(block);
    }

    @Override
    public String toString()
    {
        return super.toString() + "[" + _code + ":" + _message + "]";
    }
}
