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

package org.eclipse.jetty.client;

import java.net.URI;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A protocol handler that handles redirect status codes and caches permanent redirects (301, 308).</p>
 * <p>This handler extends the standard redirect behavior by storing permanent redirects
 * in a {@link PermanentRedirectCache} for later reuse.</p>
 */
public class CachingRedirectProtocolHandler implements ProtocolHandler, Response.Listener
{
    public static final String NAME = "caching-redirect";
    private static final Logger LOG = LoggerFactory.getLogger(CachingRedirectProtocolHandler.class);

    private final HttpRedirector redirector;
    private final PermanentRedirectCache cache;

    public CachingRedirectProtocolHandler(HttpClient client, PermanentRedirectCache cache)
    {
        this.redirector = new HttpRedirector(client);
        this.cache = cache;
    }

    @Override
    public String getName()
    {
        return NAME;
    }

    @Override
    public boolean accept(Request request, Response response)
    {
        return redirector.isRedirect(response) && request.isFollowRedirects();
    }

    @Override
    public Response.Listener getResponseListener()
    {
        return this;
    }

    @Override
    public boolean onHeader(Response response, HttpField field)
    {
        // Avoid that the content is decoded, which could generate
        // errors, since we are discarding the response content anyway.
        return field.getHeader() != HttpHeader.CONTENT_ENCODING;
    }

    @Override
    public void onSuccess(Response response)
    {
        // The request may still be sending content, stop it.
        Request request = response.getRequest();
        if (request.getBody() != null)
            request.abort(new HttpRequestException("Aborting request after receiving a %d response".formatted(response.getStatus()), request));
    }

    @Override
    public void onComplete(Result result)
    {
        Request request = result.getRequest();
        Response response = result.getResponse();

        // Cache permanent redirects before delegating to the redirector
        int status = response.getStatus();
        if (isPermanentRedirect(status))
        {
            cacheRedirect(request, response, status);
        }

        redirector.redirect(request, response, null);
    }

    private boolean isPermanentRedirect(int status)
    {
        return status == HttpStatus.MOVED_PERMANENTLY_301 || status == HttpStatus.PERMANENT_REDIRECT_308;
    }

    private void cacheRedirect(Request request, Response response, int status)
    {
        URI targetURI = redirector.extractRedirectURI(response);
        if (targetURI == null)
            return;

        String targetMethod = redirector.computeRedirectMethod(request, response);
        if (targetMethod == null)
            return;

        String key = PermanentRedirectCache.normalizeURI(request);
        PermanentRedirectCache.CachedRedirect cached = new PermanentRedirectCache.CachedRedirect(
            targetURI,
            targetMethod,
            status,
            System.currentTimeMillis()
        );

        cache.put(key, cached);

        if (LOG.isDebugEnabled())
            LOG.debug("Cached {} redirect: {} -> {}", status, key, targetURI);
    }

    /**
     * @return the {@link HttpRedirector} used by this handler
     */
    public HttpRedirector getRedirector()
    {
        return redirector;
    }
}
