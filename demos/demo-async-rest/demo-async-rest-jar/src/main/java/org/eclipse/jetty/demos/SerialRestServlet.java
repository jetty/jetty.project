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

package org.eclipse.jetty.demos;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * Servlet implementation class SerialRestServlet
 */
public class SerialRestServlet extends AbstractRestServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        long start = NanoTime.now();

        String[] keywords = sanitize(request.getParameter(ITEMS_PARAM)).split(",");
        Queue<Map<String, Object>> results = new LinkedList<>();

        // make all requests serially
        for (String itemName : keywords)
        {
            URL url = new URL(restURL(itemName));

            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");

            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>)new JSON().fromJSON(new BufferedReader(new InputStreamReader(connection.getInputStream())));
            Object[] auctions = (Object[])query.get("Item");
            if (auctions != null)
            {
                for (Object o : auctions)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> auction = (Map<String, Object>)o;
                    results.add(auction);
                }
            }
        }

        // Generate the response
        final String thumbs = generateThumbs(results);

        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html><head>");
        out.println(STYLE);
        out.println("</head><body><small>");

        long total = NanoTime.since(start);

        out.print("<b>Blocking: " + sanitize(request.getParameter(ITEMS_PARAM)) + "</b><br/>");
        out.print("Total Time: " + ms(total) + "ms<br/>");
        out.print("Thread held (<span class='red'>red</span>): " + ms(total) + "ms<br/>");

        out.println("<img border='0px' src='asyncrest/red.png'   height='20px' width='" + width(total) + "px'>");

        out.println("<hr />");
        out.println(thumbs);
        out.println("</small>");
        out.println("</body></html>");
        out.close();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }
}
