//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.ee11.servlets;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletRequest;
import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.annotation.ManagedObject;

/**
 * Denial of Service filter
 * <p>
 * This filter is useful for limiting
 * exposure to abuse from request flooding, whether malicious, or as a result of
 * a misconfigured client.
 * <p>
 * The filter keeps track of the number of requests from a connection per
 * second. If a limit is exceeded, the request is either rejected, delayed, or
 * throttled.
 * <p>
 * When a request is throttled, it is placed in a queue and will only proceed
 * when there is capacity.
 * <p>
 * The {@link #extractUserId(ServletRequest request)} function should be
 * implemented, in order to uniquely identify authenticated users.
 * <p>
 * The following init parameters control the behavior of the filter:
 * <dl>
 * <dt>maxRequestsPerSec</dt>
 * <dd>the maximum number of requests from a connection per
 * second. Requests in excess of this are first delayed,
 * then throttled.</dd>
 * <dt>delayMs</dt>
 * <dd>is the delay given to all requests over the rate limit,
 * before they are considered at all. -1 means just reject request,
 * 0 means no delay, otherwise it is the delay.</dd>
 * <dt>maxWaitMs</dt>
 * <dd>how long to blocking wait for the throttle semaphore.</dd>
 * <dt>throttledRequests</dt>
 * <dd>is the number of requests over the rate limit able to be
 * considered at once.</dd>
 * <dt>throttleMs</dt>
 * <dd>how long to async wait for semaphore.</dd>
 * <dt>maxRequestMs</dt>
 * <dd>how long to allow this request to run.</dd>
 * <dt>maxIdleTrackerMs</dt>
 * <dd>how long to keep track of request rates for a connection,
 * before deciding that the user has gone away, and discarding it</dd>
 * <dt>insertHeaders</dt>
 * <dd>if true , insert the DoSFilter headers into the response. Defaults to true.</dd>
 * <dt>remotePort</dt>
 * <dd>if true then rate is tracked by IP+port (effectively connection). Defaults to false.</dd>
 * <dt>ipWhitelist</dt>
 * <dd>a comma-separated list of IP addresses that will not be rate limited</dd>
 * <dt>managedAttr</dt>
 * <dd>if set to true, then this servlet is set as a {@link ServletContext} attribute with the
 * filter name as the attribute name.  This allows context external mechanism (eg JMX via {@link ContextHandler} managed attribute)
 * to manage the configuration of the filter.</dd>
 * <dt>tooManyCode</dt>
 * <dd>The status code to send if there are too many requests.  By default is 429 (too many requests), but 503 (Unavailable) is
 * another option</dd>
 * </dl>
 * <p>
 * This filter should be configured for {@link DispatcherType#REQUEST} and {@link DispatcherType#ASYNC} and with
 * {@code <async-supported>true</async-supported>}.
 * </p>
 */
@ManagedObject("limits exposure to abuse from request flooding, whether malicious, or as a result of a misconfigured client")
public class DoSFilter extends org.eclipse.jetty.ee.servlets.DoSFilter
{
}
