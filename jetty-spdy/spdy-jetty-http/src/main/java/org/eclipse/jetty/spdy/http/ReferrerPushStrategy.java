/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy.http;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
 * HTTP header that points to <tt>index.html</tt>; therefore main resources and associated
 * resources must be distinguishable.</p>
 * <p>This class distinguishes associated resources by their URL path suffix and content
 * type.
 * CSS stylesheets, images and JavaScript files have recognizable URL path suffixes that
 * are classified as associated resources.</p>
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
    private final ConcurrentMap<String, Set<String>> resources = new ConcurrentHashMap<>();
    private final Set<Pattern> pushRegexps = new HashSet<>();
    private final Set<String> pushContentTypes = new HashSet<>();
    private final Set<Pattern> allowedPushOrigins = new HashSet<>();
    private volatile int maxAssociatedResources = 32;

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

    @Override
    public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders)
    {
        Set<String> result = Collections.emptySet();
        short version = stream.getSession().getVersion();
        String scheme = requestHeaders.get(HTTPSPDYHeader.SCHEME.name(version)).value();
        String host = requestHeaders.get(HTTPSPDYHeader.HOST.name(version)).value();
        String origin = new StringBuilder(scheme).append("://").append(host).toString();
        String url = requestHeaders.get(HTTPSPDYHeader.URI.name(version)).value();
        String absoluteURL = new StringBuilder(origin).append(url).toString();
        logger.debug("Applying push strategy for {}", absoluteURL);
        if (isValidMethod(requestHeaders.get(HTTPSPDYHeader.METHOD.name(version)).value()))
        {
            if (isMainResource(url, responseHeaders))
            {
                result = pushResources(absoluteURL);
            }
            else if (isPushResource(url, responseHeaders))
            {
                Headers.Header referrerHeader = requestHeaders.get("referer");
                if (referrerHeader != null)
                {
                    String referrer = referrerHeader.value();
                    Set<String> pushResources = resources.get(referrer);
                    if (pushResources == null || !pushResources.contains(url))
                        buildMetadata(origin, url, referrer);
                    else
                        result = pushResources(absoluteURL);
                }
            }
        }
        logger.debug("Push resources for {}: {}", absoluteURL, result);
        return result;
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

                String contentType = header.value().toLowerCase();
                for (String pushContentType : pushContentTypes)
                    if (contentType.startsWith(pushContentType))
                        return true;
            }
        }
        return false;
    }

    private Set<String> pushResources(String absoluteURL)
    {
        Set<String> pushResources = resources.get(absoluteURL);
        if (pushResources == null)
            return Collections.emptySet();
        return Collections.unmodifiableSet(pushResources);
    }

    private void buildMetadata(String origin, String url, String referrer)
    {
        if (referrer.startsWith(origin) || isPushOriginAllowed(origin))
        {
            Set<String> pushResources = resources.get(referrer);
            if (pushResources == null)
            {
                pushResources = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
                Set<String> existing = resources.putIfAbsent(referrer, pushResources);
                if (existing != null)
                    pushResources = existing;
            }
            // This check is not strictly concurrent-safe, but limiting
            // the number of associated resources is achieved anyway
            // although in rare cases few more resources will be stored
            if (pushResources.size() < getMaxAssociatedResources())
            {
                pushResources.add(url);
                logger.debug("Stored push metadata for {}: {}", referrer, pushResources);
            }
            else
            {
                logger.debug("Skipped store of push metadata {} for {}: max associated resources ({}) reached",
                        url, referrer, maxAssociatedResources);
            }
        }
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
