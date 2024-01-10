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

package org.eclipse.jetty.server.handler;

import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;
import org.eclipse.jetty.http.HttpStatus;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.annotation.ManagedAttribute;
import org.eclipse.jetty.util.annotation.ManagedObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Implementation of the CORS protocol defined by the
 * <a href="https://fetch.spec.whatwg.org/">fetch standard</a>.</p>
 * <p>This {@link Handler} should be present in the {@link Handler} tree to prevent
 * <a href="https://owasp.org/www-community/attacks/csrf">cross site request forgery</a> attacks.</p>
 * <p>A typical case is a web page containing a script downloaded from the origin server at
 * {@code domain.com}, where the script makes requests to the cross server at {@code cross.domain.com}.
 * The cross server at {@code cross.domain.com} has the {@link CrossOriginHandler} installed and will
 * see requests such as:</p>
 * <pre>{@code
 * GET / HTTP/1.1
 * Host: cross.domain.com
 * Origin: http://domain.com
 * }</pre>
 * <p>The cross server at {@code cross.domain.com} must decide whether these cross-origin requests
 * are allowed or not, and it may easily do so by configuring the {@link CrossOriginHandler},
 * for example configuring the {@link #setAllowedOriginPatterns(Set) allowed origins} to contain only
 * the origin server with origin {@code http://domain.com}.</p>
 */
@ManagedObject
public class CrossOriginHandler extends Handler.Wrapper
{
    private static final Logger LOG = LoggerFactory.getLogger(CrossOriginHandler.class);
    private static final PreEncodedHttpField ACCESS_CONTROL_ALLOW_CREDENTIALS_TRUE = new PreEncodedHttpField(HttpHeader.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    private static final PreEncodedHttpField VARY_ORIGIN = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ORIGIN.asString());

    private boolean allowCredentials = true;
    private Set<String> allowedHeaders = Set.of("Content-Type");
    private Set<String> allowedMethods = Set.of("GET", "POST", "HEAD");
    private Set<String> allowedOrigins = Set.of("*");
    private Set<String> allowedTimingOrigins = Set.of();
    private boolean deliverPreflight = false;
    private boolean deliverNonAllowedOrigin = true;
    private boolean deliverNonAllowedOriginWebSocketUpgrade = false;
    private Set<String> exposedHeaders = Set.of();
    private Duration preflightMaxAge = Duration.ofSeconds(60);
    private boolean anyOriginAllowed;
    private final Set<Pattern> allowedOriginPatterns = new LinkedHashSet<>();
    private boolean anyTimingOriginAllowed;
    private final Set<Pattern> allowedTimingOriginPatterns = new LinkedHashSet<>();
    private PreEncodedHttpField accessControlAllowMethodsField;
    private PreEncodedHttpField accessControlAllowHeadersField;
    private PreEncodedHttpField accessControlExposeHeadersField;
    private PreEncodedHttpField accessControlMaxAge;

    /**
     * @return whether the cross server allows cross-origin requests to include credentials
     */
    @ManagedAttribute("Whether the server allows cross-origin requests to include credentials (cookies, authentication headers, etc.)")
    public boolean isAllowCredentials()
    {
        return allowCredentials;
    }

    /**
     * <p>Sets whether the cross server allows cross-origin requests to include credentials
     * such as cookies or authentication headers.</p>
     * <p>For example, when the cross server allows credentials to be included, cross-origin
     * requests will contain cookies, otherwise they will not.</p>
     * <p>The default is {@code true}.</p>
     *
     * @param allow whether the cross server allows cross-origin requests to include credentials
     */
    public void setAllowCredentials(boolean allow)
    {
        throwIfStarted();
        allowCredentials = allow;
    }

    /**
     * @return the immutable set of allowed headers in a cross-origin request
     */
    @ManagedAttribute("The set of allowed headers in a cross-origin request")
    public Set<String> getAllowedHeaders()
    {
        return allowedHeaders;
    }

    /**
     * <p>Sets the set of allowed headers in a cross-origin request.</p>
     * <p>The cross server receives a preflight request that specifies the headers
     * of the cross-origin request, and the cross server replies to the preflight
     * request with the set of allowed headers.
     * Browsers are responsible to check whether the headers of the cross-origin
     * request are allowed, and if they are not produce an error.</p>
     * <p>The headers can be either the character {@code *} to indicate any
     * header, or actual header names.</p>
     *
     * @param headers the set of allowed headers in a cross-origin request
     */
    public void setAllowedHeaders(Set<String> headers)
    {
        throwIfStarted();
        allowedHeaders = Set.copyOf(headers);
    }

    /**
     * @return the immutable set of allowed methods in a cross-origin request
     */
    @ManagedAttribute("The set of allowed methods in a cross-origin request")
    public Set<String> getAllowedMethods()
    {
        return allowedMethods;
    }

    /**
     * <p>Sets the set of allowed methods in a cross-origin request.</p>
     * <p>The cross server receives a preflight request that specifies the method
     * of the cross-origin request, and the cross server replies to the preflight
     * request with the set of allowed methods.
     * Browsers are responsible to check whether the method of the cross-origin
     * request is allowed, and if it is not produce an error.</p>
     *
     * @param methods the set of allowed methods in a cross-origin request
     */
    public void setAllowedMethods(Set<String> methods)
    {
        throwIfStarted();
        allowedMethods = Set.copyOf(methods);
    }

    /**
     * @return the immutable set of allowed origin regex strings in a cross-origin request
     */
    @ManagedAttribute("The set of allowed origin regex strings in a cross-origin request")
    public Set<String> getAllowedOriginPatterns()
    {
        return allowedOrigins;
    }

    /**
     * <p>Sets the set of allowed origin regex strings in a cross-origin request.</p>
     * <p>The cross server receives a preflight or a cross-origin request
     * specifying the {@link HttpHeader#ORIGIN}, and replies with the
     * same origin if allowed, otherwise the {@link HttpHeader#ACCESS_CONTROL_ALLOW_ORIGIN}
     * is not added to the response (and the client should fail the
     * cross-origin or preflight request).</p>
     * <p>The origins are either the character {@code *}, or regular expressions,
     * so dot characters separating domain segments must be escaped:</p>
     * <pre>{@code
     * crossOriginHandler.setAllowedOriginPatterns(Set.of("https://.*\\.domain\\.com"));
     * }</pre>
     * <p>The default value is {@code *}.</p>
     *
     * @param origins the set of allowed origin regex strings in a cross-origin request
     */
    public void setAllowedOriginPatterns(Set<String> origins)
    {
        throwIfStarted();
        allowedOrigins = Set.copyOf(origins);
    }

    /**
     * @return the immutable set of allowed timing origin regex strings in a cross-origin request
     */
    @ManagedAttribute("The set of allowed timing origin regex strings in a cross-origin request")
    public Set<String> getAllowedTimingOriginPatterns()
    {
        return allowedTimingOrigins;
    }

    /**
     * <p>Sets the set of allowed timing origin regex strings in a cross-origin request.</p>
     *
     * @param origins the set of allowed timing origin regex strings in a cross-origin request
     */
    public void setAllowedTimingOriginPatterns(Set<String> origins)
    {
        throwIfStarted();
        allowedTimingOrigins = Set.copyOf(origins);
    }

    /**
     * @return whether preflight requests are delivered to the child Handler
     */
    @ManagedAttribute("whether preflight requests are delivered to the child Handler")
    public boolean isDeliverPreflightRequests()
    {
        return deliverPreflight;
    }

    /**
     * <p>Sets whether preflight requests are delivered to the child {@link Handler}.</p>
     * <p>Default value is {@code false}.</p>
     *
     * @param deliver whether preflight requests are delivered to the child Handler
     */
    public void setDeliverPreflightRequests(boolean deliver)
    {
        throwIfStarted();
        deliverPreflight = deliver;
    }

    /**
     * @return whether requests whose origin is not allowed are delivered to the child Handler
     */
    @ManagedAttribute("whether requests whose origin is not allowed are delivered to the child Handler")
    public boolean isDeliverNonAllowedOriginRequests()
    {
        return deliverNonAllowedOrigin;
    }

    /**
     * <p>Sets whether requests whose origin is not allowed are delivered to the child Handler.</p>
     * <p>Default value is {@code true}.</p>
     *
     * @param deliverNonAllowedOrigin whether requests whose origin is not allowed are delivered to the child Handler
     */
    public void setDeliverNonAllowedOriginRequests(boolean deliverNonAllowedOrigin)
    {
        this.deliverNonAllowedOrigin = deliverNonAllowedOrigin;
    }

    /**
     * @return whether WebSocket upgrade requests whose origin is not allowed are delivered to the child Handler
     */
    @ManagedAttribute("whether WebSocket upgrade requests whose origin is not allowed are delivered to the child Handler")
    public boolean isDeliverNonAllowedOriginWebSocketUpgradeRequests()
    {
        return deliverNonAllowedOriginWebSocketUpgrade;
    }

    /**
     * <p>Sets whether WebSocket upgrade requests whose origin is not allowed are delivered to the child Handler.</p>
     * <p>Default value is {@code false}.</p>
     *
     * @param deliverNonAllowedOriginWebSocketUpgrade whether WebSocket upgrade requests whose origin is not allowed are delivered to the child Handler
     */
    public void setDeliverNonAllowedOriginWebSocketUpgradeRequests(boolean deliverNonAllowedOriginWebSocketUpgrade)
    {
        this.deliverNonAllowedOriginWebSocketUpgrade = deliverNonAllowedOriginWebSocketUpgrade;
    }

    /**
     * @return the immutable set of headers exposed in a cross-origin response
     */
    @ManagedAttribute("The set of headers exposed in a cross-origin response")
    public Set<String> getExposedHeaders()
    {
        return exposedHeaders;
    }

    /**
     * <p>Sets the set of headers exposed in a cross-origin response.</p>
     * <p>The cross server receives a cross-origin request and indicates
     * which response headers are exposed to scripts running in the browser.</p>
     *
     * @param headers the set of headers exposed in a cross-origin response
     */
    public void setExposedHeaders(Set<String> headers)
    {
        throwIfStarted();
        exposedHeaders = Set.copyOf(headers);
    }

    /**
     * @return how long the preflight results can be cached by browsers
     */
    @ManagedAttribute("How long the preflight results can be cached by browsers")
    public Duration getPreflightMaxAge()
    {
        return preflightMaxAge;
    }

    /**
     * @param duration how long the preflight results can be cached by browsers
     */
    public void setPreflightMaxAge(Duration duration)
    {
        throwIfStarted();
        preflightMaxAge = duration;
    }

    @Override
    protected void doStart() throws Exception
    {
        resolveAllowedOrigins();
        resolveAllowedTimingOrigins();
        accessControlAllowMethodsField = new PreEncodedHttpField(HttpHeader.ACCESS_CONTROL_ALLOW_METHODS, String.join(",", getAllowedMethods()));
        accessControlAllowHeadersField = new PreEncodedHttpField(HttpHeader.ACCESS_CONTROL_ALLOW_HEADERS, String.join(",", getAllowedHeaders()));
        accessControlExposeHeadersField = new PreEncodedHttpField(HttpHeader.ACCESS_CONTROL_EXPOSE_HEADERS, String.join(",", getExposedHeaders()));
        accessControlMaxAge = new PreEncodedHttpField(HttpHeader.ACCESS_CONTROL_MAX_AGE, getPreflightMaxAge().toSeconds());
        super.doStart();
    }

    @Override
    public boolean handle(Request request, Response response, Callback callback) throws Exception
    {
        // The response may change if the Origin header is present, so always add Vary.
        response.getHeaders().add(VARY_ORIGIN);

        String origins = request.getHeaders().get(HttpHeader.ORIGIN);
        if (origins == null)
            return super.handle(request, response, callback);

        if (LOG.isDebugEnabled())
            LOG.debug("handling cross-origin request {}", request);

        boolean preflight = isPreflight(request);

        if (originMatches(origins))
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cross-origin request matches allowed origins: {} {}", request, getAllowedOriginPatterns());

            if (preflight)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("preflight cross-origin request {}", request);
                handlePreflightResponse(origins, response);
                if (!isDeliverPreflightRequests())
                {
                    if (LOG.isDebugEnabled())
                        LOG.debug("preflight cross-origin request not delivered to child handler {}", request);
                    callback.succeeded();
                    return true;
                }
            }
            else
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("simple cross-origin request {}", request);
                handleSimpleResponse(origins, response);
            }

            if (timingOriginMatches(origins))
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("cross-origin request matches allowed timing origins: {} {}", request, getAllowedTimingOriginPatterns());
                response.getHeaders().put(HttpHeader.TIMING_ALLOW_ORIGIN, origins);
            }

            return super.handle(request, response, callback);
        }
        else
        {
            if (LOG.isDebugEnabled())
                LOG.debug("cross-origin request does not match allowed origins: {} {}", request, getAllowedOriginPatterns());

            if (isDeliverNonAllowedOriginRequests())
            {
                if (preflight)
                {
                    if (!isDeliverPreflightRequests())
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("preflight cross-origin request not delivered to child handler {}", request);
                        callback.succeeded();
                        return true;
                    }
                }
                else
                {
                    if (isWebSocketUpgrade(request))
                    {
                        if (!isDeliverNonAllowedOriginWebSocketUpgradeRequests())
                        {
                            Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "origin not allowed");
                            return true;
                        }
                    }
                }

                if (LOG.isDebugEnabled())
                    LOG.debug("cross-origin request delivered to child handler {}", request);

                return super.handle(request, response, callback);
            }
            else
            {
                Response.writeError(request, response, callback, HttpStatus.BAD_REQUEST_400, "origin not allowed");
                return true;
            }
        }
    }

    private boolean originMatches(String origins)
    {
        if (anyOriginAllowed)
            return true;
        if (allowedOriginPatterns.isEmpty())
            return false;
        return originMatches(origins, allowedOriginPatterns);
    }

    private boolean timingOriginMatches(String origins)
    {
        if (anyTimingOriginAllowed)
            return true;
        if (allowedTimingOriginPatterns.isEmpty())
            return false;
        return originMatches(origins, allowedTimingOriginPatterns);
    }

    private boolean originMatches(String origins, Set<Pattern> allowedOriginPatterns)
    {
        for (String origin : origins.split(" "))
        {
            origin = origin.trim();
            if (origin.isEmpty())
                continue;
            for (Pattern pattern : allowedOriginPatterns)
            {
                if (pattern.matcher(origin).matches())
                    return true;
            }
        }
        return false;
    }

    private boolean isPreflight(Request request)
    {
        return HttpMethod.OPTIONS.is(request.getMethod()) && request.getHeaders().contains(HttpHeader.ACCESS_CONTROL_REQUEST_METHOD);
    }

    private boolean isWebSocketUpgrade(Request request)
    {
        return request.getHeaders().contains(HttpHeader.UPGRADE, "websocket");
    }

    private void handlePreflightResponse(String origins, Response response)
    {
        HttpFields.Mutable headers = response.getHeaders();
        headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origins);
        if (isAllowCredentials())
            headers.put(ACCESS_CONTROL_ALLOW_CREDENTIALS_TRUE);
        Set<String> allowedMethods = getAllowedMethods();
        if (!allowedMethods.isEmpty())
            headers.put(accessControlAllowMethodsField);
        Set<String> allowedHeaders = getAllowedHeaders();
        if (!allowedHeaders.isEmpty())
            headers.put(accessControlAllowHeadersField);
        long seconds = getPreflightMaxAge().toSeconds();
        if (seconds > 0)
            headers.put(accessControlMaxAge);
    }

    private void handleSimpleResponse(String origin, Response response)
    {
        HttpFields.Mutable headers = response.getHeaders();
        headers.put(HttpHeader.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        if (isAllowCredentials())
            headers.put(ACCESS_CONTROL_ALLOW_CREDENTIALS_TRUE);
        Set<String> exposedHeaders = getExposedHeaders();
        if (!exposedHeaders.isEmpty())
            headers.put(accessControlExposeHeadersField);
    }

    private void resolveAllowedOrigins()
    {
        for (String allowedOrigin : getAllowedOriginPatterns())
        {
            allowedOrigin = allowedOrigin.trim();
            if (allowedOrigin.isEmpty())
                continue;

            if ("*".equals(allowedOrigin))
            {
                anyOriginAllowed = true;
                return;
            }

            allowedOriginPatterns.add(Pattern.compile(allowedOrigin, Pattern.CASE_INSENSITIVE));
        }
    }

    private void resolveAllowedTimingOrigins()
    {
        for (String allowedTimingOrigin : getAllowedTimingOriginPatterns())
        {
            allowedTimingOrigin = allowedTimingOrigin.trim();
            if (allowedTimingOrigin.isEmpty())
                continue;

            if ("*".equals(allowedTimingOrigin))
            {
                anyTimingOriginAllowed = true;
                return;
            }

            allowedTimingOriginPatterns.add(Pattern.compile(allowedTimingOrigin, Pattern.CASE_INSENSITIVE));
        }
    }

    private void throwIfStarted()
    {
        if (isStarted())
            throw new IllegalStateException("Cannot configure after start");
    }
}
