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

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpHeaderValue;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.Index;

/**
 * Special handling for MSIE (Microsoft Internet Explorer).
 * <ul>
 *     <li>Disable keep alive for SSL from IE5 or IE6 on Windows 2000</li>
 *     <li>Disable encodings for IE&lt;=6</li>
 * </ul>
 */
public class MsieRule extends Rule
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
    private static final HttpField CONNECTION_CLOSE = new HttpField(HttpHeader.CONNECTION, HttpHeaderValue.CLOSE);
    private static final HttpField VARY_USER_AGENT = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.USER_AGENT.asString());

    public MsieRule()
    {
        _handling = false;
        _terminating = false;
    }

    @Override
    public String matchAndApply(String target, HttpServletRequest request, HttpServletResponse response) throws IOException
    {
        Request baseRequest = Request.getBaseRequest(request);
        if (baseRequest == null)
            return null;

        HttpFields.Mutable reqFields = HttpFields.build(baseRequest.getHttpFields());
        HttpFields.Mutable resFields = baseRequest.getResponse().getHttpFields();
        String userAgent = reqFields.get(HttpHeader.USER_AGENT);
        boolean acceptEncodings = reqFields.contains(HttpHeader.ACCEPT_ENCODING);
        if (acceptEncodings)
            resFields.ensureField(VARY_USER_AGENT);

        int msie = userAgent.indexOf("MSIE");
        if (msie >= 0)
        {
            int version = (userAgent.length() - msie > 5) ? userAgent.charAt(msie + 5) : IEv5;

            if (version <= IEv6)
            {
                // Don't gzip responses for IE<=6
                if (acceptEncodings)
                    reqFields.remove(HttpHeader.ACCEPT_ENCODING);

                // IE<=6 can't do persistent SSL
                if (request.isSecure())
                {
                    boolean badOs = false;
                    if (version == IEv6)
                    {
                        int windows = userAgent.indexOf("Windows", msie + 5);
                        if (windows > 0)
                        {
                            int end = userAgent.indexOf(')', windows + 8);
                            badOs = (end < 0 || __IE6_BadOS.get(userAgent, windows + 8, end - windows - 8) != null);
                        }
                    }

                    if (version <= IEv5 || badOs)
                    {
                        reqFields.remove(HttpHeader.KEEP_ALIVE);
                        reqFields.ensureField(CONNECTION_CLOSE);
                        resFields.ensureField(CONNECTION_CLOSE);
                        response.setHeader(HttpHeader.CONNECTION.asString(), HttpHeaderValue.CLOSE.asString());
                    }
                }
                baseRequest.setHttpFields(reqFields);
                return target;
            }
        }
        return null;
    }
}
