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

import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.util.Index;

/**
 * MSIE (Microsoft Internet Explorer) SSL Rule.
 * Disable keep alive for SSL from IE5 or IE6 on Windows 2000.
 * @deprecated use MsieRule
 */
@Deprecated
public class MsieSslRule extends Rule
{
    private static final int IEv5 = '5';
    private static final int IEv6 = '6';
    private static final Index<Boolean> __IE6_BadOS = new Index.Builder<Boolean>()
        .caseSensitive(false)
        .with("NT 5.01", Boolean.TRUE)
        .with("NT 5.0", Boolean.TRUE)
        .with("NT 4.0", Boolean.TRUE)
        .with("98", Boolean.TRUE)
        .with("98; Win 9x 4.90", Boolean.TRUE)
        .with("95", Boolean.TRUE)
        .with("CE", Boolean.TRUE)
        .build();

    public MsieSslRule()
    {
        _handling = false;
        _terminating = false;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        if (request.isSecure())
        {
            String userAgent = request.getHeader(HttpHeader.USER_AGENT.asString());

            if (userAgent != null)
            {
                int msie = userAgent.indexOf("MSIE");
                if (msie > 0 && userAgent.length() - msie > 5)
                {
                    // Get Internet Explorer Version
                    int ieVersion = userAgent.charAt(msie + 5);

                    if (ieVersion <= IEv5)
                    {
                        response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                        return target;
                    }

                    if (ieVersion == IEv6)
                    {
                        int windows = userAgent.indexOf("Windows", msie + 5);
                        if (windows > 0)
                        {
                            int end = userAgent.indexOf(')', windows + 8);
                            if (end < 0 || __IE6_BadOS.get(userAgent, windows + 8, end - windows - 8) != null)
                            {
                                response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                                return target;
                            }
                        }
                    }
                }
            }
        }
        return null;
    }
}
