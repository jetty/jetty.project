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

package org.eclipse.jetty.ee9.demos;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Queue;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Abstract Servlet implementation class AsyncRESTServlet.
 * Enquires ebay REST service for auctions by key word.
 * May be configured with init parameters: <dl>
 * <dt>appid</dt><dd>The eBay application ID to use</dd>
 * </dl>
 * Each request examines the following request parameters:<dl>
 * <dt>items</dt><dd>The keyword to search for</dd>
 * </dl>
 */
public class AbstractRestServlet extends HttpServlet
{
    protected static final String __DEFAULT_APPID = "Webtide81-adf4-4f0a-ad58-d91e41bbe85";
    protected static final String STYLE =
        "<style type='text/css'>" +
            "  img.thumb:hover {height:50px}" +
            "  img.thumb {vertical-align:text-top}" +
            "  span.red {color: #ff0000}" +
            "  span.green {color: #00ff00}" +
            "  iframe {border: 0px}" +
            "</style>";

    protected static final String ITEMS_PARAM = "items";
    protected static final String APPID_PARAM = "appid";

    protected String _appid;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException
    {
        if (servletConfig.getInitParameter(APPID_PARAM) == null)
            _appid = __DEFAULT_APPID;
        else
            _appid = servletConfig.getInitParameter(APPID_PARAM);
    }

    // TODO: consider using StringUtil.sanitizeFileSystemName instead of this?
    // might introduce jetty-util dependency though
    public static String sanitize(String str)
    {
        if (str == null)
            return null;

        char[] chars = str.toCharArray();
        int len = chars.length;
        for (int i = 0; i < len; i++)
        {
            char c = chars[i];
            if ((c <= 0x1F) || // control characters
                (c == '<') || (c == '&'))
            {
                chars[i] = '?';
            }
        }
        return String.valueOf(chars);
    }

    protected String restURL(String item)
    {
        try
        {
            return ("https://open.api.ebay.com/shopping?MaxEntries=3&appid=" + _appid +
                "&version=573&siteid=0&callname=FindItems&responseencoding=JSON&QueryKeywords=" +
                URLEncoder.encode(item, "UTF-8"));
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    protected String generateThumbs(Queue<Map<String, Object>> results)
    {
        StringBuilder thumbs = new StringBuilder();
        for (Map<String, Object> m : results)
        {
            if (!m.containsKey("GalleryURL"))
                continue;

            thumbs.append("<a href=\"").append(m.get("ViewItemURLForNaturalSearch")).append("\">");
            thumbs.append("<img class='thumb' border='1px' height='25px' src='")
                .append(m.get("GalleryURL")).append("'")
                .append(" title='").append(m.get("Title")).append("'")
                .append("/>");
            thumbs.append("</a>&nbsp;");
        }
        return thumbs.toString();
    }

    protected String ms(long nano)
    {
        BigDecimal dec = new BigDecimal(nano);
        return dec.divide(new BigDecimal(1000000L)).setScale(1, RoundingMode.UP).toString();
    }

    protected int width(long nano)
    {
        int w = (int)((nano + 999999L) / 5000000L);
        if (w == 0)
            w = 2;
        return w;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }
}
