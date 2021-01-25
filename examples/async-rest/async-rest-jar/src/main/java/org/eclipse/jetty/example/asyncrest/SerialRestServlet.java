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

package org.eclipse.jetty.example.asyncrest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.ajax.JSON;

/**
 * Servlet implementation class SerialRestServlet
 */
public class SerialRestServlet extends AbstractRestServlet
{
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        long start = System.nanoTime();

        String[] keywords = sanitize(request.getParameter(ITEMS_PARAM)).split(",");
        Queue<Map<String, String>> results = new LinkedList<Map<String, String>>();

        // make all requests serially
        for (String itemName : keywords)
        {
            URL url = new URL(restURL(itemName));

            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");

            Map query = (Map)JSON.parse(new BufferedReader(new InputStreamReader(connection.getInputStream())));
            Object[] auctions = (Object[])query.get("Item");
            if (auctions != null)
            {
                for (Object o : auctions)
                {
                    results.add((Map)o);
                }
            }
        }

        // Generate the response
        String thumbs = generateThumbs(results);

        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html><head>");
        out.println(STYLE);
        out.println("</head><body><small>");

        long now = System.nanoTime();
        long total = now - start;

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

    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
     * response)
     */
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }
}
