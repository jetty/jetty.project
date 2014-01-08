//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.spdy.server.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

import org.eclipse.jetty.spdy.api.Stream;
import org.eclipse.jetty.spdy.http.HTTPSPDYHeader;
import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

/**
 * <p>A SPDY push strategy that auto-populates push metadata based on referrer URLs.<p>A typical request for a main
 * resource such as {@code index.html} is immediately followed by a number of requests for associated resources.
 * Associated resource requests will have a {@code Referer} HTTP header that points to {@code index.html}, which is used
 * to link the associated resource to the main resource.<p>However, also following a hyperlink generates a HTTP request
 * with a {@code Referer} HTTP header that points to {@code index.html}; therefore a proper value for {@link
 * #setReferrerPushPeriod(int)} has to be set. If the referrerPushPeriod for a main resource has elapsed, no more
 * associated resources will be added for that main resource.<p>This class distinguishes associated main resources by
 * their URL path suffix and content type. CSS stylesheets, images and JavaScript files have recognizable URL path
 * suffixes that are classified as associated resources. The suffix regexs can be configured by constructor argument</p>
 * <p>When CSS stylesheets refer to images, the CSS image request will have the CSS stylesheet as referrer. This
 * implementation will push also the CSS image.<p>The push metadata built by this implementation is limited by the
 * number of pages of the application itself, and by the {@link #setMaxAssociatedResources(int)} max associated
 * resources} parameter. This parameter limits the number of associated resources per each main resource, so that if a
 * main resource has hundreds of associated resources, only up to the number specified by this parameter will be
 * pushed.
 */
public class ReferrerPushStrategy implements PushStrategy
{
    private static final Logger LOG = Log.getLogger(ReferrerPushStrategy.class);
    private final ConcurrentMap<String, MainResource> mainResources = new ConcurrentHashMap<>();
    private final Set<Pattern> pushRegexps = new HashSet<>();
    private final Set<String> pushContentTypes = new HashSet<>();
    private final Set<Pattern> allowedPushOrigins = new HashSet<>();
    private final Set<Pattern> userAgentBlacklist = new HashSet<>();
    private volatile int maxAssociatedResources = 32;
    private volatile int referrerPushPeriod = 5000;

    public ReferrerPushStrategy()
    {
        List<String> defaultPushRegexps = Arrays.asList(".*\\.css", ".*\\.js", ".*\\.png", ".*\\.jpeg", ".*\\.jpg",
                ".*\\.gif", ".*\\.ico");
        addPushRegexps(defaultPushRegexps);

        List<String> defaultPushContentTypes = Arrays.asList(
                "text/css",
                "text/javascript", "application/javascript", "application/x-javascript",
                "image/png", "image/x-png",
                "image/jpeg",
                "image/gif",
                "image/x-icon", "image/vnd.microsoft.icon");
        this.pushContentTypes.addAll(defaultPushContentTypes);
    }

    public void setPushRegexps(List<String> pushRegexps)
    {
        pushRegexps.clear();
        addPushRegexps(pushRegexps);
    }

    private void addPushRegexps(List<String> pushRegexps)
    {
        for (String pushRegexp : pushRegexps)
            this.pushRegexps.add(Pattern.compile(pushRegexp));
    }

    public void setPushContentTypes(List<String> pushContentTypes)
    {
        pushContentTypes.clear();
        pushContentTypes.addAll(pushContentTypes);
    }

    public void setAllowedPushOrigins(List<String> allowedPushOrigins)
    {
        allowedPushOrigins.clear();
        for (String allowedPushOrigin : allowedPushOrigins)
            this.allowedPushOrigins.add(Pattern.compile(allowedPushOrigin.replace(".", "\\.").replace("*", ".*")));
    }

    public void setUserAgentBlacklist(List<String> userAgentPatterns)
    {
        userAgentBlacklist.clear();
        for (String userAgentPattern : userAgentPatterns)
            userAgentBlacklist.add(Pattern.compile(userAgentPattern));
    }

    public void setMaxAssociatedResources(int maxAssociatedResources)
    {
        this.maxAssociatedResources = maxAssociatedResources;
    }

    public void setReferrerPushPeriod(int referrerPushPeriod)
    {
        this.referrerPushPeriod = referrerPushPeriod;
    }

    public Set<Pattern> getPushRegexps()
    {
        return pushRegexps;
    }

    public Set<String> getPushContentTypes()
    {
        return pushContentTypes;
    }

    public Set<Pattern> getAllowedPushOrigins()
    {
        return allowedPushOrigins;
    }

    public Set<Pattern> getUserAgentBlacklist()
    {
        return userAgentBlacklist;
    }

    public int getMaxAssociatedResources()
    {
        return maxAssociatedResources;
    }

    public int getReferrerPushPeriod()
    {
        return referrerPushPeriod;
    }

    @Override
    public Set<String> apply(Stream stream, Fields requestHeaders, Fields responseHeaders)
    {
        Set<String> result = Collections.<String>emptySet();
        short version = stream.getSession().getVersion();
        if (!isIfModifiedSinceHeaderPresent(requestHeaders) && isValidMethod(requestHeaders.get(HTTPSPDYHeader.METHOD
                .name(version)).getValue()) && !isUserAgentBlacklisted(requestHeaders))
        {
            String scheme = requestHeaders.get(HTTPSPDYHeader.SCHEME.name(version)).getValue();
            String host = requestHeaders.get(HTTPSPDYHeader.HOST.name(version)).getValue();
            String origin = scheme + "://" + host;
            String url = requestHeaders.get(HTTPSPDYHeader.URI.name(version)).getValue();
            String absoluteURL = origin + url;
            LOG.debug("Applying push strategy for {}", absoluteURL);
            if (isMainResource(url, responseHeaders))
            {
                MainResource mainResource = getOrCreateMainResource(absoluteURL);
                result = mainResource.getResources();
            }
            else if (isPushResource(url, responseHeaders))
            {
                Fields.Field referrerHeader = requestHeaders.get("referer");
                if (referrerHeader != null)
                {
                    String referrer = referrerHeader.getValue();
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
            LOG.debug("Pushing {} resources for {}: {}", result.size(), absoluteURL, result);
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
            LOG.debug("Creating new main resource for {}", absoluteURL);
            MainResource value = new MainResource(absoluteURL);
            mainResource = mainResources.putIfAbsent(absoluteURL, value);
            if (mainResource == null)
                mainResource = value;
        }
        return mainResource;
    }

    private boolean isIfModifiedSinceHeaderPresent(Fields headers)
    {
        return headers.get("if-modified-since") != null;
    }

    private boolean isValidMethod(String method)
    {
        return "GET".equalsIgnoreCase(method);
    }

    private boolean isMainResource(String url, Fields responseHeaders)
    {
        return !isPushResource(url, responseHeaders);
    }

    public boolean isUserAgentBlacklisted(Fields headers)
    {
        Fields.Field userAgentHeader = headers.get("user-agent");
        if (userAgentHeader != null)
            for (Pattern userAgentPattern : userAgentBlacklist)
                if (userAgentPattern.matcher(userAgentHeader.getValue()).matches())
                    return true;
        return false;
    }

    private boolean isPushResource(String url, Fields responseHeaders)
    {
        for (Pattern pushRegexp : pushRegexps)
        {
            if (pushRegexp.matcher(url).matches())
            {
                Fields.Field header = responseHeaders.get("content-type");
                if (header == null)
                    return true;

                String contentType = header.getValue().toLowerCase(Locale.ENGLISH);
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
        private final CopyOnWriteArraySet<String> resources = new CopyOnWriteArraySet<>();
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
                LOG.debug("Skipped store of push metadata {} for {}: Origin: {} doesn't match or origin not allowed",
                        url, name, origin);
                return false;
            }

            // This check is not strictly concurrent-safe, but limiting
            // the number of associated resources is achieved anyway
            // although in rare cases few more resources will be stored
            if (resources.size() >= maxAssociatedResources)
            {
                LOG.debug("Skipped store of push metadata {} for {}: max associated resources ({}) reached",
                        url, name, maxAssociatedResources);
                return false;
            }
            if (delay > referrerPushPeriod)
            {
                LOG.debug("Delay: {}ms longer than referrerPushPeriod ({}ms). Not adding resource: {} for: {}", delay,
                        referrerPushPeriod, url, name);
                return false;
            }

            LOG.debug("Adding: {} to: {} with delay: {}ms.", url, this, delay);
            resources.add(url);
            return true;
        }

        public Set<String> getResources()
        {
            return Collections.unmodifiableSet(resources);
        }

        public String toString()
        {
            return String.format("%s@%x{name=%s,resources=%s}",
                    getClass().getSimpleName(),
                    hashCode(),
                    name,
                    resources
            );
        }

        private boolean isPushOriginAllowed(String origin)
        {
            for (Pattern allowedPushOrigin : allowedPushOrigins)
                if (allowedPushOrigin.matcher(origin).matches())
                    return true;
            return false;
        }
    }
}
