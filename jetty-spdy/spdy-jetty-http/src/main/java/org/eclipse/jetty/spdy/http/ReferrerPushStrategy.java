//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.eclipse.jetty.spdy.api.Headers;
import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A SPDY push strategy that auto-populates push metadata based on referrer URLs.</p>
 * <p>A typical request for a main resource such as <tt>index.html</tt> is immediately
 * followed by a number of requests for associated resources. Associated resource requests
 * will have a <tt>Referer</tt> HTTP header that points to <tt>index.html</tt>, which we
 * use to link the associated resource to the main resource.</p>
 * <p>However, also following a hyperlink generates a HTTP request with a <tt>Referer</tt>
 * HTTP header that points to <tt>index.html</tt>; therefore a proper value for {@link #getReferrerPushPeriod()}
 * has to be set. If the referrerPushPeriod for a main resource has been passed, no more
 * associated resources will be added for that main resource.</p>
 * <p>This class distinguishes associated main resources by their URL path suffix and content
 * type.
 * CSS stylesheets, images and JavaScript files have recognizable URL path suffixes that
 * are classified as associated resources. The suffix regexs can be configured by constructor argument</p>
 * <p>When CSS stylesheets refer to images, the CSS image request will have the CSS
 * stylesheet as referrer. This implementation will push also the CSS image.</p>
 * <p>The push metadata built by this implementation is limited by the number of pages
 * of the application itself, and by the
 * {@link #getMaxAssociatedResources() max associated resources} parameter.
 * This parameter limits the number of associated resources per each main resource, so
 * that if a main resource has hundreds of associated resources, only up to the number
 * specified by this parameter will be pushed.</p>
 */
public class ReferrerPushStrategy implements PushStrategy
{
    private static final Logger logger = Log.getLogger(ReferrerPushStrategy.class);
    private final ConcurrentMap<String, MainResource> mainResources = new ConcurrentHashMap<>();
    private final Set<Pattern> pushRegexps = new HashSet<>();
    private final Set<String> pushContentTypes = new HashSet<>();
    private final Set<Pattern> allowedPushOrigins = new HashSet<>();
    private volatile int maxAssociatedResources = 32;
    private volatile int referrerPushPeriod = 5000;

    public ReferrerPushStrategy()
    {
        this(Arrays.asList(".*\\.css", ".*\\.js", ".*\\.png", ".*\\.jpeg", ".*\\.jpg", ".*\\.gif", ".*\\.ico"));
    }

    public ReferrerPushStrategy(List<String> pushRegexps)
    {
        this(pushRegexps, Arrays.asList(
                "text/css",
                "text/javascript", "application/javascript", "application/x-javascript",
                "image/png", "image/x-png",
                "image/jpeg",
                "image/gif",
                "image/x-icon", "image/vnd.microsoft.icon"));
    }

    public ReferrerPushStrategy(List<String> pushRegexps, List<String> pushContentTypes)
    {
        this(pushRegexps, pushContentTypes, Collections.<String>emptyList());
    }

    public ReferrerPushStrategy(List<String> pushRegexps, List<String> pushContentTypes, List<String> allowedPushOrigins)
    {
        for (String pushRegexp : pushRegexps)
            this.pushRegexps.add(Pattern.compile(pushRegexp));
        this.pushContentTypes.addAll(pushContentTypes);
        for (String allowedPushOrigin : allowedPushOrigins)
            this.allowedPushOrigins.add(Pattern.compile(allowedPushOrigin.replace(".", "\\.").replace("*", ".*")));
    }

    public int getMaxAssociatedResources()
    {
        return maxAssociatedResources;
    }

    public void setMaxAssociatedResources(int maxAssociatedResources)
    {
        this.maxAssociatedResources = maxAssociatedResources;
    }

    public int getReferrerPushPeriod()
    {
        return referrerPushPeriod;
    }

    public void setReferrerPushPeriod(int referrerPushPeriod)
    {
        this.referrerPushPeriod = referrerPushPeriod;
    }

    @Override
    public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders)
    {
        Set<String> result = Collections.<String>emptySet();
        short version = stream.getSession().getVersion();
        if (!isIfModifiedSinceHeaderPresent(requestHeaders) && isValidMethod(requestHeaders.get(HTTPSPDYHeader.METHOD.name(version)).value()))
        {
            String scheme = requestHeaders.get(HTTPSPDYHeader.SCHEME.name(version)).value();
            String host = requestHeaders.get(HTTPSPDYHeader.HOST.name(version)).value();
            String origin = scheme + "://" + host;
            String url = requestHeaders.get(HTTPSPDYHeader.URI.name(version)).value();
            String absoluteURL = origin + url;
            logger.debug("Applying push strategy for {}", absoluteURL);
            if (isMainResource(url, responseHeaders))
            {
                MainResource mainResource = getOrCreateMainResource(absoluteURL);
                result = mainResource.getResources();
            }
            else if (isPushResource(url, responseHeaders))
            {
                Headers.Header referrerHeader = requestHeaders.get("referer");
                if (referrerHeader != null)
                {
                    String referrer = referrerHeader.value();
                    MainResource mainResource = mainResources.get(referrer);
                    if (mainResource == null)
                        mainResource = getOrCreateMainResource(referrer);

                    Set<String> pushResources = mainResource.getResources();
                    if (!pushResources.contains(url))
                        mainResource.addResource(url, origin, referrer);
                    else
                        result = getPushResources(absoluteURL);
                }
            }
            logger.debug("Pushing {} resources for {}: {}", result.size(), absoluteURL, result);
        }
        return result;
    }

    private Set<String> getPushResources(String absoluteURL)
    {
        Set<String> result = Collections.emptySet();
        if (mainResources.get(absoluteURL) != null)
            result = mainResources.get(absoluteURL).getResources();
        return result;
    }

    private MainResource getOrCreateMainResource(String absoluteURL)
    {
        MainResource mainResource = mainResources.get(absoluteURL);
        if (mainResource == null)
        {
            logger.debug("Creating new main resource for {}", absoluteURL);
            MainResource value = new MainResource(absoluteURL);
            mainResource = mainResources.putIfAbsent(absoluteURL, value);
            if (mainResource == null)
                mainResource = value;
        }
        return mainResource;
    }

    private boolean isIfModifiedSinceHeaderPresent(Headers headers)
    {
        return headers.get("if-modified-since") != null;
    }

    private boolean isValidMethod(String method)
    {
        return "GET".equalsIgnoreCase(method);
    }

    private boolean isMainResource(String url, Headers responseHeaders)
    {
        return !isPushResource(url, responseHeaders);
    }

    private boolean isPushResource(String url, Headers responseHeaders)
    {
        for (Pattern pushRegexp : pushRegexps)
        {
            if (pushRegexp.matcher(url).matches())
            {
                Headers.Header header = responseHeaders.get("content-type");
                if (header == null)
                    return true;

                String contentType = header.value().toLowerCase(Locale.ENGLISH);
                for (String pushContentType : pushContentTypes)
                    if (contentType.startsWith(pushContentType))
                        return true;
            }
        }
        return false;
    }

    private class MainResource
    {
        private final String name;
        private final Set<String> resources = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        private final AtomicLong firstResourceAdded = new AtomicLong(-1);

        private MainResource(String name)
        {
            this.name = name;
        }

        public boolean addResource(String url, String origin, String referrer)
        {
            // We start the push period here and not when initializing the main resource, because a browser with a
            // prefilled cache won't request the subresources. If the browser with warmed up cache now hits the main
            // resource after a server restart, the push period shouldn't start until the first subresource is
            // being requested.
            firstResourceAdded.compareAndSet(-1, System.nanoTime());

            long delay = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - firstResourceAdded.get());
            if (!referrer.startsWith(origin) && !isPushOriginAllowed(origin))
            {
                logger.debug("Skipped store of push metadata {} for {}: Origin: {} doesn't match or origin not allowed",
                        url, name, origin);
                return false;
            }

            // This check is not strictly concurrent-safe, but limiting
            // the number of associated resources is achieved anyway
            // although in rare cases few more resources will be stored
            if (resources.size() >= maxAssociatedResources)
            {
                logger.debug("Skipped store of push metadata {} for {}: max associated resources ({}) reached",
                        url, name, maxAssociatedResources);
                return false;
            }
            if (delay > referrerPushPeriod)
            {
                logger.debug("Delay: {}ms longer than referrerPushPeriod: {}ms. Not adding resource: {} for: {}", delay, referrerPushPeriod, url, name);
                return false;
            }

            logger.debug("Adding resource: {} for: {} with delay: {}ms.", url, name, delay);
            resources.add(url);
            return true;
        }

        public Set<String> getResources()
        {
            return Collections.unmodifiableSet(resources);
        }

        public String toString()
        {
            return "MainResource: " + name + " associated resources:" + resources.size();
        }

        private boolean isPushOriginAllowed(String origin)
        {
            for (Pattern allowedPushOrigin : allowedPushOrigins)
            {
                if (allowedPushOrigin.matcher(origin).matches())
                    return true;
            }
            return false;
        }
    }
}
