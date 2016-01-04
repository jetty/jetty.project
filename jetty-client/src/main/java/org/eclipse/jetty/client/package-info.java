//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

/**
 * Jetty Client : Implementation and Core Classes
 * 
 * This package provides APIs, utility classes and an implementation of an asynchronous HTTP client.
 * <p>
 * The core class is {@link org.eclipse.jetty.client.HttpClient}, which acts as a central configuration object (for example
 * for {@link org.eclipse.jetty.client.HttpClient#setIdleTimeout(long) idle timeouts}, {@link org.eclipse.jetty.client.HttpClient#setMaxConnectionsPerDestination(int)
 * max connections per destination}, etc.) and as a factory for {@link org.eclipse.jetty.client.api.Request} objects.
 * <p>
 * The HTTP protocol is based on the request/response paradigm, a unit that in this implementation is called
 * <em>exchange</em> and is represented by {@link org.eclipse.jetty.client.HttpExchange}.
 * An initial request may trigger a sequence of exchanges with one or more servers, called a <em>conversation</em>
 * and represented by {@link org.eclipse.jetty.client.HttpConversation}. A typical example of a conversation is a redirect, where
 * upon a request for a resource URI, the server replies with a redirect (for example with the 303 status code)
 * to another URI. This conversation is made of a first exchange made of the original request and its 303 response,
 * and of a second exchange made of the request for the new URI and its 200 response.
 * <p>
 * {@link org.eclipse.jetty.client.HttpClient} holds a number of {@link org.eclipse.jetty.client.api.Destination destinations}, which in turn hold a number of
 * pooled {@link org.eclipse.jetty.client.api.Connection connections}.
 * <p>
 * When a request is sent, its exchange is associated to a connection, either taken from an idle queue or created
 * anew, and when both the request and response are completed, the exchange is disassociated from the connection.
 * Conversations may span multiple connections on different destinations, and therefore are maintained at the
 * {@link org.eclipse.jetty.client.HttpClient} level.
 * <p>
 * Applications may decide to send the request and wait for the response in a blocking way, using
 * {@link org.eclipse.jetty.client.api.Request#send()}.
 * Alternatively, application may ask to be notified of response events asynchronously, using
 * {@link org.eclipse.jetty.client.api.Request#send(org.eclipse.jetty.client.api.Response.CompleteListener)}.
 */
package org.eclipse.jetty.client;

import org.eclipse.jetty.client.api.Response;


