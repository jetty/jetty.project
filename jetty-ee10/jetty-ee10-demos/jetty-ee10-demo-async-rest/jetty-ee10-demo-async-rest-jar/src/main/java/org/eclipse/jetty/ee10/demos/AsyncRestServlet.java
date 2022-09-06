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

package org.eclipse.jetty.ee10.demos;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.NanoTime;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON;

/**
 * Servlet implementation class AsyncRESTServlet.
 * Enquires ebay REST service for auctions by key word.
 * May be configured with init parameters: <dl>
 * <dt>appid</dt><dd>The eBay application ID to use</dd>
 * </dl>
 * Each request examines the following request parameters:<dl>
 * <dt>items</dt><dd>The keyword to search for</dd>
 * </dl>
 */
public class AsyncRestServlet extends AbstractRestServlet
{
    static final String RESULTS_ATTR = "org.eclipse.jetty.demo.client";
    static final String DURATION_ATTR = "org.eclipse.jetty.demo.duration";
    static final String START_ATTR = "org.eclispe.jetty.demo.start";

    HttpClient _client;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException
    {
        super.init(servletConfig);

        _client = new HttpClient();

        try
        {
            _client.start();
        }
        catch (Exception e)
        {
            throw new ServletException(e);
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        long start = NanoTime.now();

        // Do we have results yet?
        @SuppressWarnings("unchecked")
        Queue<Map<String, Object>> results = (Queue<Map<String, Object>>)request.getAttribute(RESULTS_ATTR);

        // If no results, this must be the first dispatch, so send the REST request(s)
        if (results == null)
        {
            // define results data structures
            results = new ConcurrentLinkedQueue<>();
            request.setAttribute(RESULTS_ATTR, results);

            // suspend the request
            // This is done before scheduling async handling to avoid race of
            // dispatch before startAsync!
            final AsyncContext async = request.startAsync();
            async.setTimeout(30000);

            // extract keywords to search for
            String[] keywords = sanitize(request.getParameter(ITEMS_PARAM)).split(",");
            final AtomicInteger outstanding = new AtomicInteger(keywords.length);

            // Send request each keyword
            Queue<Map<String, Object>> resultsQueue = results;
            for (final String item : keywords)
            {
                _client.newRequest(restURL(item)).method(HttpMethod.GET).send(
                    new AsyncRestRequest()
                    {
                        @Override
                        void onAuctionFound(Map<String, Object> auction)
                        {
                            resultsQueue.add(auction);
                        }

                        @Override
                        void onComplete()
                        {
                            if (outstanding.decrementAndGet() <= 0)
                                async.dispatch();
                        }
                    });
            }

            // save timing info and return
            request.setAttribute(START_ATTR, start);
            request.setAttribute(DURATION_ATTR, NanoTime.since(start));

            return;
        }

        // We have results!

        // Generate the response
        final String thumbs = generateThumbs(results);

        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html><head>");
        out.println(STYLE);
        out.println("</head><body><small>");

        long initial = (Long)request.getAttribute(DURATION_ATTR);
        long start0 = (Long)request.getAttribute(START_ATTR);

        long now = NanoTime.now();
        long total = NanoTime.elapsed(start0, now);
        long generate = NanoTime.elapsed(start, now);
        long thread = initial + generate;

        out.print("<b>Asynchronous: " + sanitize(request.getParameter(ITEMS_PARAM)) + "</b><br/>");
        out.print("Total Time: " + ms(total) + "ms<br/>");

        out.print("Thread held (<span class='red'>red</span>): " + ms(thread) + "ms (" + ms(initial) + " initial + " + ms(generate) + " generate )<br/>");
        out.print("Async wait (<span class='green'>green</span>): " + ms(total - thread) + "ms<br/>");

        out.println("<img border='0px' src='asyncrest/red.png'   height='20px' width='" + width(initial) + "px'>" +
            "<img border='0px' src='asyncrest/green.png' height='20px' width='" + width(total - thread) + "px'>" +
            "<img border='0px' src='asyncrest/red.png'   height='20px' width='" + width(generate) + "px'>");

        out.println("<hr />");
        out.println(thumbs);
        out.println("</small>");
        out.println("</body></html>");
        out.close();
    }

    private abstract static class AsyncRestRequest extends Response.Listener.Adapter
    {
        private final Utf8StringBuilder _content = new Utf8StringBuilder();

        AsyncRestRequest()
        {
        }

        @Override
        public void onContent(Response response, ByteBuffer content)
        {
            byte[] bytes = BufferUtil.toArray(content);
            _content.append(bytes, 0, bytes.length);
        }

        @Override
        public void onComplete(Result result)
        {
            // extract auctions from the results
            @SuppressWarnings("unchecked")
            Map<String, Object> query = (Map<String, Object>)new JSON().fromJSON(_content.toString());
            Object[] auctions = (Object[])query.get("Item");
            if (auctions != null)
            {
                for (Object o : auctions)
                {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> auction = (Map<String, Object>)o;
                    onAuctionFound(auction);
                }
            }
            onComplete();
        }

        abstract void onComplete();

        abstract void onAuctionFound(Map<String, Object> details);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }
}
