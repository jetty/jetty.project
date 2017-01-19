//
//  ========================================================================
//  Copyright (c) 1995-2017 Mort Bay Consulting Pty. Ltd.
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

/** Build a request to be pushed.
 * <p>
 * A PushBuilder is obtained by calling {@link Request#getPushBuilder()}
 * which creates an initializes the builder as follows:
 * <ul>
 * <li> Each call to getPushBuilder() will return a new instance of a 
 * PushBuilder based off the Request.  Any mutations to the
 * returned PushBuilder are not reflected on future returns.</li>
 * <li>The method is initialized to "GET"</li>
 * <li>The requests headers are added to the Builder, except for:<ul>
 *   <li>Conditional headers (eg. If-Modified-Since)
 *   <li>Range headers
 *   <li>Expect headers
 *   <li>Authorization headers
 *   <li>Referrer headers
 * </ul></li>
 * <li>If the request was Authenticated, an Authorization header will 
 * be set with a container generated token that will result in equivalent
 * Authorization for the pushed request</li>
 * <li>The query string from {@link HttpServletRequest#getQueryString()}
 * <li>The {@link HttpServletRequest#getRequestedSessionId()} value, unless at the time
 * of the call {@link HttpServletRequest#getSession(boolean)}
 * has previously been called to create a new {@link HttpSession}, in 
 * which case the new session ID will be used as the PushBuilders 
 * requested session ID. The source of the requested session id will be the 
 * same as for the request</li>
 * <li>The Referer header will be set to {@link HttpServletRequest#getRequestURL()} 
 * plus any {@link HttpServletRequest#getQueryString()} </li>
 * <li>If {@link HttpServletResponse#addCookie(Cookie)} has been called
 * on the associated response, then a corresponding Cookie header will be added
 * to the PushBuilder, unless the {@link Cookie#getMaxAge()} is &lt;=0, in which
 * case the Cookie will be removed from the builder.</li>
 * <li>If this request has has the conditional headers If-Modified-Since or
 * If-None-Match then the {@link #isConditional()} header is set to true.</li> 
 * </ul>
 * <p>A PushBuilder can be customized by chained calls to mutator methods before the 
 * {@link #push()} method is called to initiate a push request with the current state
 * of the builder.  After the call to {@link #push()}, the builder may be reused for
 * another push, however the {@link #path(String)}, {@link #etag(String)} and
 * {@link #lastModified(String)} values will have been nulled.  All other 
 * values are retained over calls to {@link #push()}. 
 */
public interface PushBuilder
{
    /** Set the method to be used for the push.  
     * Defaults to GET.
     * @param method the method to be used for the push.  
     * @return this builder.
     */
    public abstract PushBuilder method(String method);
    
    /** Set the query string to be used for the push.  
     * Defaults to the requests query string.
     * Will be appended to any query String included in a call to {@link #path(String)}.  This 
     * method should be used instead of a query in {@link #path(String)} when multiple
     * {@link #push()} calls are to be made with the same query string, or to remove a 
     * query string obtained from the associated request.
     * @param  queryString the query string to be used for the push. 
     * @return this builder.
     */
    public abstract PushBuilder queryString(String queryString);
    
    /** Set the SessionID to be used for the push.
     * The session ID will be set in the same way it was on the associated request (ie
     * as a cookie if the associated request used a cookie, or as a url parameter if
     * the associated request used a url parameter).
     * Defaults to the requested session ID or any newly assigned session id from
     * a newly created session.
     * @param sessionId the SessionID to be used for the push.
     * @return this builder.
     */
    public abstract PushBuilder sessionId(String sessionId);
    
    /** Set if the request is to be conditional.
     * If the request is conditional, any available values from {@link #etag(String)} or 
     * {@link #lastModified(String)} will be set in the appropriate headers. If the request
     * is not conditional, then etag and lastModified values are ignored.  
     * Defaults to true if the associated request was conditional.
     * @param  conditional true if the push request is conditional
     * @return this builder.
     */
    public abstract PushBuilder conditional(boolean conditional);
    
    /** Set a header to be used for the push.  
     * @param name The header name to set
     * @param value The header value to set
     * @return this builder.
     */
    public abstract PushBuilder setHeader(String name, String value);
    
    /** Add a header to be used for the push.  
     * @param name The header name to add
     * @param value The header value to add
     * @return this builder.
     */
    public abstract PushBuilder addHeader(String name, String value);
    
    /** Set the URI path to be used for the push.  
     * The path may start with "/" in which case it is treated as an
     * absolute path, otherwise it is relative to the context path of
     * the associated request.
     * There is no path default and {@link #path(String)} must be called
     * before every call to {@link #push()}
     * @param path the URI path to be used for the push, which may include a
     * query string.
     * @return this builder.
     */
    public abstract PushBuilder path(String path);
    
    /** Set the etag to be used for conditional pushes.  
     * The etag will be used only if {@link #isConditional()} is true.
     * Defaults to no etag.  The value is nulled after every call to 
     * {@link #push()}
     * @param etag the etag to be used for the push.
     * @return this builder.
     */
    public abstract PushBuilder etag(String etag);

    /** Set the last modified date to be used for conditional pushes.  
     * The last modified date will be used only if {@link #isConditional()} is true.
     * Defaults to no date.  The value is nulled after every call to 
     * {@link #push()}
     * @param lastModified the last modified date to be used for the push.
     * @return this builder.
     * */
    public abstract PushBuilder lastModified(String lastModified);


    /** Push a resource.
     * Push a resource based on the current state of the PushBuilder.  If {@link #isConditional()}
     * is true and an etag or lastModified value is provided, then an appropriate conditional header
     * will be generated. If both an etag and lastModified value are provided only an If-None-Match header
     * will be generated. If the builder has a session ID, then the pushed request
     * will include the session ID either as a Cookie or as a URI parameter as appropriate. The builders
     * query string is merged with any passed query string.
     * After initiating the push, the builder has its path, etag and lastModified fields nulled. All 
     * other fields are left as is for possible reuse in another push.
     * @throws IllegalArgumentException if the method set expects a request body (eg POST)
     */
    public abstract void push();
    
    
    
    
    
    public abstract String getMethod();
    public abstract String getQueryString();
    public abstract String getSessionId();
    public abstract boolean isConditional();
    public abstract Set<String> getHeaderNames();
    public abstract String getHeader(String name);
    public abstract String getPath();
    public abstract String getEtag();
    public abstract String getLastModified();



}