//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.regex.Matcher;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Issues a 301 Moved Permanently Redirects to location if pattern matches
 */
public class MovedPermanentlyRegexRule extends RedirectRegexRule
{
    @Override
    protected String apply(String target, HttpServletRequest request, HttpServletResponse response, Matcher matcher) throws IOException
    {
        target = _replacement;
        for (int g = 1; g <= matcher.groupCount(); g++)
        {
            String group = matcher.group(g);
            target = target.replaceAll("\\$" + g,group);
        }

        response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
        response.setHeader("Location",response.encodeRedirectURL(target));
        return target;
    }
}