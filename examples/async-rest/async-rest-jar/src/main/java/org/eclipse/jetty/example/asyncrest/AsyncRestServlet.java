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

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import javax.servlet.AsyncContext;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.api.Response;
import org.eclipse.jetty.client.api.Result;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.Utf8StringBuilder;
import org.eclipse.jetty.util.ajax.JSON;
import org.eclipse.jetty.util.ssl.SslContextFactory;

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

        _client = new HttpClient(new SslContextFactory.Client());

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
        Long start = System.nanoTime();

        // Do we have results yet?
        Queue<Map<String, String>> results = (Queue<Map<String, String>>)request.getAttribute(RESULTS_ATTR);

        // If no results, this must be the first dispatch, so send the REST request(s)
        if (results == null)
        {
            // define results data structures
            final Queue<Map<String, String>> resultsQueue = new ConcurrentLinkedQueue<>();
            request.setAttribute(RESULTS_ATTR, results = resultsQueue);

            // suspend the request
            // This is done before scheduling async handling to avoid race of
            // dispatch before startAsync!
            final AsyncContext async = request.startAsync();
            async.setTimeout(30000);

            // extract keywords to search for
            String[] keywords = sanitize(request.getParameter(ITEMS_PARAM)).split(",");
            final AtomicInteger outstanding = new AtomicInteger(keywords.length);

            // Send request each keyword
            for (final String item : keywords)
            {
                _client.newRequest(restURL(item)).method(HttpMethod.GET).send(
                    new AsyncRestRequest()
                    {
                        @Override
                        void onAuctionFound(Map<String, String> auction)
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
            request.setAttribute(DURATION_ATTR, System.nanoTime() - start);

            return;
        }

        // We have results!

        // Generate the response
        String thumbs = generateThumbs(results);

        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html><head>");
        out.println(STYLE);
        out.println("</head><body><small>");

        long initial = (Long)request.getAttribute(DURATION_ATTR);
        long start0 = (Long)request.getAttribute(START_ATTR);

        long now = System.nanoTime();
        long total = now - start0;
        long generate = now - start;
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

    private abstract class AsyncRestRequest extends Response.Listener.Adapter
    {
        final Utf8StringBuilder _content = new Utf8StringBuilder();

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
            Map<String, ?> query = (Map<String, ?>)JSON.parse(_content.toString());
            Object[] auctions = (Object[])query.get("Item");
            if (auctions != null)
            {
                for (Object o : auctions)
                {
                    onAuctionFound((Map<String, String>)o);
                }
            }
            onComplete();
        }

        abstract void onAuctionFound(Map<String, String> details);

        abstract void onComplete();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }
}
