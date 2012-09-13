//
//  ========================================================================
//  Copyright (c) 1995-2012 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.client.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.Future;

import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.client.util.StreamingResponseListener;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.util.Fields;

/**
 * <p>{@link Request} represents a HTTP request, and offers a fluent interface to customize
 * various attributes such as the path, the headers, the content, etc.</p>
 * <p>You can create {@link Request} objects via {@link HttpClient#newRequest(String)} and
 * you can send them using either {@link #send()} for a blocking semantic, or
 * {@link #send(Response.Listener)} for an asynchronous semantic.</p>
 */
public interface Request
{
    /**
     * @return the conversation id
     */
    long id();

    /**
     * @return the scheme of this request, such as "http" or "https"
     */
    String scheme();

    /**
     * @param scheme the scheme of this request, such as "http" or "https"
     * @return this request object
     */
    Request scheme(String scheme);

    /**
     * @return the host of this request, such as "127.0.0.1" or "google.com"
     */
    String host();

    /**
     * @return the port of this request such as 80 or 443
     */
    int port();

    /**
     * @return the method of this request, such as GET or POST
     */
    HttpMethod method();

    /**
     * @param method the method of this request, such as GET or POST
     * @return this request object
     */
    Request method(HttpMethod method);

    /**
     * @return the path of this request, such as "/"
     */
    String path();

    /**
     * @param path the path of this request, such as "/"
     * @return this request object
     */
    Request path(String path);

    /**
     * @return the full URI of this request such as "http://host:port/path"
     */
    String uri();

    /**
     * @return the HTTP version of this request, such as "HTTP/1.1"
     */
    HttpVersion version();

    /**
     * @param version the HTTP version of this request, such as "HTTP/1.1"
     * @return this request object
     */
    Request version(HttpVersion version);

    /**
     * @return the query parameters of this request
     */
    Fields params();

    /**
     * @param name the name of the query parameter
     * @param value the value of the query parameter
     * @return this request object
     */
    Request param(String name, String value);

    /**
     * @return the headers of this request
     */
    HttpFields headers();

    /**
     * @param name the name of the header
     * @param value the value of the header
     * @return this request object
     */
    Request header(String name, String value);

    /**
     * @return the content provider of this request
     */
    ContentProvider content();

    /**
     * @param content the content provider of this request
     * @return this request object
     */
    Request content(ContentProvider content);

    /**
     * Shortcut method to specify a file as a content for this request, with the default content type of
     * "application/octect-stream".
     *
     * @param file the file to upload
     * @return this request object
     * @throws IOException if the file does not exist or cannot be read
     */
    Request file(Path file) throws IOException;

    /**
     * Shortcut method to specify a file as a content for this request, with the given content type.
     *
     * @param file the file to upload
     * @param contentType the content type of the file
     * @return this request object
     * @throws IOException if the file does not exist or cannot be read
     */
    Request file(Path file, String contentType) throws IOException;

//    Request decoder(ContentDecoder decoder);

    /**
     * @return the user agent for this request
     */
    String agent();

    /**
     * @param agent the user agent for this request
     * @return this request object
     */
    Request agent(String agent);

    /**
     * @return the idle timeout for this request
     */
    long idleTimeout();

    /**
     * @param timeout the idle timeout for this request
     * @return this request object
     */
    Request idleTimeout(long timeout);

    // TODO
    Request followRedirects(boolean follow);

    /**
     * @return the listener for request events
     */
    Listener listener();

    /**
     * @param listener the listener for request events
     * @return this request object
     */
    Request listener(Listener listener);

    /**
     * Sends this request and returns a {@link Future} that can be used to wait for the
     * request and the response to be completed (either with a success or a failure).
     * <p />
     * This method should be used when a simple blocking semantic is needed, and when it is known
     * that the response content can be buffered without exceeding memory constraints.
     * For example, this method is not appropriate to download big files from a server; consider using
     * {@link #send(Response.Listener)} instead, passing your own {@link Response.Listener} or a utility
     * listener such as {@link StreamingResponseListener}.
     * <p />
     * The future will return when {@link Response.Listener#onComplete(Result)} is invoked.
     *
     * @return a {@link Future} to wait on for request and response completion
     * @see Response.Listener#onComplete(Result)
     */
    Future<ContentResponse> send();

    /**
     * Sends this request and asynchronously notifies the given listener for response events.
     * <p />
     * This method should be used when the application needs to be notified of the various response events
     * as they happen, or when the application needs to efficiently manage the response content.
     *
     * @param listener the listener that receives response events
     */
    void send(Response.Listener listener);

    /**
     * Listener for request events
     */
    public interface Listener
    {
        /**
         * Callback method invoked when the request is queued, waiting to be sent
         *
         * @param request the request being queued
         */
        public void onQueued(Request request);

        /**
         * Callback method invoked when the request begins being processed in order to be sent.
         * This is the last opportunity to modify the request.
         *
         * @param request the request that begins being processed
         */
        public void onBegin(Request request);

        /**
         * Callback method invoked when the request headers (and perhaps small content) have been sent.
         * The request is now committed, and in transit to the server, and further modifications to the
         * request may have no effect.
         * @param request the request that has been committed
         */
        public void onHeaders(Request request);

        /**
         * Callback method invoked when the request has been successfully sent.
         *
         * @param request the request sent
         */
        public void onSuccess(Request request);

        /**
         * Callback method invoked when the request has failed to be sent
         * @param request the request that failed
         * @param failure the failure
         */
        public void onFailure(Request request, Throwable failure);

        /**
         * An empty implementation of {@link Listener}
         */
        public static class Empty implements Listener
        {
            @Override
            public void onQueued(Request request)
            {
            }

            @Override
            public void onBegin(Request request)
            {
            }

            @Override
            public void onHeaders(Request request)
            {
            }

            @Override
            public void onSuccess(Request request)
            {
            }

            @Override
            public void onFailure(Request request, Throwable failure)
            {
            }
        }
    }
}
