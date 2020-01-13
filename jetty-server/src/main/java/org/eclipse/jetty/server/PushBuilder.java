//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.server;

import java.util.Set;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

/**
 * Build a request to be pushed.
 *
 * <p>A PushBuilder is obtained by calling {@link
 * Request#getPushBuilder()} (<code>Eventually HttpServletRequest.getPushBuilder()</code>).
 * Each call to this method will
 * return a new instance of a PushBuilder based off the current {@code
 * HttpServletRequest}.  Any mutations to the returned PushBuilder are
 * not reflected on future returns.</p>
 *
 * <p>The instance is initialized as follows:</p>
 *
 * <ul>
 *
 * <li>The method is initialized to "GET"</li>
 *
 * <li>The existing headers of the current {@link HttpServletRequest}
 * are added to the builder, except for:
 *
 * <ul>
 * <li>Conditional headers (eg. If-Modified-Since)
 * <li>Range headers
 * <li>Expect headers
 * <li>Authorization headers
 * <li>Referrer headers
 * </ul>
 *
 * </li>
 *
 * <li>If the request was authenticated, an Authorization header will
 * be set with a container generated token that will result in equivalent
 * Authorization for the pushed request.</li>
 *
 * <li>The {@link HttpServletRequest#getRequestedSessionId()} value,
 * unless at the time of the call {@link
 * HttpServletRequest#getSession(boolean)} has previously been called to
 * create a new {@link HttpSession}, in which case the new session ID
 * will be used as the PushBuilder's requested session ID. The source of
 * the requested session id will be the same as for the request</li>
 *
 * <li>The Referer(sic) header will be set to {@link
 * HttpServletRequest#getRequestURL()} plus any {@link
 * HttpServletRequest#getQueryString()} </li>
 *
 * <li>If {@link HttpServletResponse#addCookie(Cookie)} has been called
 * on the associated response, then a corresponding Cookie header will be added
 * to the PushBuilder, unless the {@link Cookie#getMaxAge()} is &lt;=0, in which
 * case the Cookie will be removed from the builder.</li>
 *
 * <li>If this request has has the conditional headers If-Modified-Since
 * or If-None-Match, then the {@link #isConditional()} header is set to
 * true.</li>
 *
 * </ul>
 *
 * <p>The {@link #path} method must be called on the {@code PushBuilder}
 * instance before the call to {@link #push}.  Failure to do so must
 * cause an exception to be thrown from {@link
 * #push}, as specified in that method.</p>
 *
 * <p>A PushBuilder can be customized by chained calls to mutator
 * methods before the {@link #push()} method is called to initiate an
 * asynchronous push request with the current state of the builder.
 * After the call to {@link #push()}, the builder may be reused for
 * another push, however the implementation must make it so the {@link
 * #path(String)}, {@link #etag(String)} and {@link
 * #lastModified(String)} values are cleared before returning from
 * {@link #push}.  All other values are retained over calls to {@link
 * #push()}.
 *
 * @since 4.0
 */
public interface PushBuilder
{
    /**
     * <p>Set the method to be used for the push.</p>
     *
     * <p>Any non-empty String may be used for the method.</p>
     *
     * @param method the method to be used for the push.
     * @return this builder.
     * @throws NullPointerException if the argument is {@code null}
     * @throws IllegalArgumentException if the argument is the empty String
     */
    PushBuilder method(String method);

    /**
     * Set the query string to be used for the push.
     *
     * Will be appended to any query String included in a call to {@link
     * #path(String)}.  Any duplicate parameters must be preserved. This
     * method should be used instead of a query in {@link #path(String)}
     * when multiple {@link #push()} calls are to be made with the same
     * query string.
     *
     * @param queryString the query string to be used for the push.
     * @return this builder.
     */
    PushBuilder queryString(String queryString);

    /**
     * Set the SessionID to be used for the push.
     * The session ID will be set in the same way it was on the associated request (ie
     * as a cookie if the associated request used a cookie, or as a url parameter if
     * the associated request used a url parameter).
     * Defaults to the requested session ID or any newly assigned session id from
     * a newly created session.
     *
     * @param sessionId the SessionID to be used for the push.
     * @return this builder.
     */
    PushBuilder sessionId(String sessionId);

    /**
     * Set if the request is to be conditional.
     * If the request is conditional, any available values from {@link #etag(String)} or
     * {@link #lastModified(String)} will be set in the appropriate headers. If the request
     * is not conditional, then etag and lastModified values are ignored.
     * Defaults to true if the associated request was conditional.
     *
     * @param conditional true if the push request is conditional
     * @return this builder.
     */
    PushBuilder conditional(boolean conditional);

    /**
     * <p>Set a header to be used for the push.  If the builder has an
     * existing header with the same name, its value is overwritten.</p>
     *
     * @param name The header name to set
     * @param value The header value to set
     * @return this builder.
     */
    PushBuilder setHeader(String name, String value);

    /**
     * <p>Add a header to be used for the push.</p>
     *
     * @param name The header name to add
     * @param value The header value to add
     * @return this builder.
     */
    PushBuilder addHeader(String name, String value);

    /**
     * <p>Remove the named header.  If the header does not exist, take
     * no action.</p>
     *
     * @param name The name of the header to remove
     * @return this builder.
     */
    PushBuilder removeHeader(String name);

    /**
     * Set the URI path to be used for the push.  The path may start
     * with "/" in which case it is treated as an absolute path,
     * otherwise it is relative to the context path of the associated
     * request.  There is no path default and <code>path(String)</code> must
     * be called before every call to {@link #push()}.  If a query
     * string is present in the argument {@code path}, its contents must
     * be merged with the contents previously passed to {@link
     * #queryString}, preserving duplicates.
     *
     * @param path the URI path to be used for the push, which may include a
     * query string.
     * @return this builder.
     */
    PushBuilder path(String path);

    /**
     * Set the etag to be used for conditional pushes.
     * The etag will be used only if {@link #isConditional()} is true.
     * Defaults to no etag.  The value is nulled after every call to
     * {@link #push()}
     *
     * @param etag the etag to be used for the push.
     * @return this builder.
     */
    PushBuilder etag(String etag);

    /**
     * Set the last modified date to be used for conditional pushes.
     * The last modified date will be used only if {@link
     * #isConditional()} is true.  Defaults to no date.  The value is
     * nulled after every call to {@link #push()}
     *
     * @param lastModified the last modified date to be used for the push.
     * @return this builder.
     */
    PushBuilder lastModified(String lastModified);

    /**
     * Push a resource given the current state of the builder,
     * returning immediately without blocking.
     *
     * <p>Push a resource based on the current state of the PushBuilder.
     * If {@link #isConditional()} is true and an etag or lastModified
     * value is provided, then an appropriate conditional header will be
     * generated. If both an etag and lastModified value are provided
     * only an If-None-Match header will be generated. If the builder
     * has a session ID, then the pushed request will include the
     * session ID either as a Cookie or as a URI parameter as
     * appropriate. The builders query string is merged with any passed
     * query string.</p>
     *
     * <p>Before returning from this method, the builder has its path,
     * etag and lastModified fields nulled. All other fields are left as
     * is for possible reuse in another push.</p>
     *
     * @throws IllegalArgumentException if the method set expects a
     * request body (eg POST)
     * @throws IllegalStateException if there was no call to {@link
     * #path} on this instance either between its instantiation or the
     * last call to {@code push()} that did not throw an
     * IllegalStateException.
     */
    void push();

    String getMethod();

    String getQueryString();

    String getSessionId();

    boolean isConditional();

    Set<String> getHeaderNames();

    String getHeader(String name);

    String getPath();

    String getEtag();

    String getLastModified();
}
