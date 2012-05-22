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
import java.util.LinkedHashSet;
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
 * <p>This class distinguishes associated resources by their URL path suffix.
 * CSS stylesheets, images and JavaScript files have recognizable URL path suffixes that
 * are classified as associated resources.</p>
 * <p>Note however, that CSS stylesheets may refer to images, and the CSS image request
 * will have the CSS stylesheet as referrer, so there is some degree of recursion that
 * needs to be handled.</p>
 *
 * TODO: this class is kind-of leaking since the resources map is always adding entries
 * TODO: although these entries will be limited by the number of application pages.
 * TODO: however, there is no ConcurrentLinkedHashMap yet in JDK (there is in Guava though)
 * TODO: so we cannot use the built-in LRU features of LinkedHashMap
 *
 * TODO: Wikipedia maps URLs like http://en.wikipedia.org/wiki/File:PNG-Gradient_hex.png
 * TODO: to text/html, so perhaps we need to improve isPushResource() by looking at the
 * TODO: response Content-Type header, and not only at the URL extension
 */
public class ReferrerPushStrategy implements PushStrategy
{
    private static final Logger logger = Log.getLogger(ReferrerPushStrategy.class);
    private final ConcurrentMap<String, Set<String>> resources = new ConcurrentHashMap<>();
    private final Set<Pattern> pushRegexps = new LinkedHashSet<>();
    private final Set<Pattern> allowedPushOrigins = new LinkedHashSet<>();

    public ReferrerPushStrategy()
    {
        this(Arrays.asList(".*\\.css", ".*\\.js", ".*\\.png", ".*\\.jpg", ".*\\.gif"));
    }

    public ReferrerPushStrategy(List<String> pushRegexps)
    {
        this(pushRegexps, Collections.<String>emptyList());
    }

    public ReferrerPushStrategy(List<String> pushRegexps, List<String> allowedPushOrigins)
    {
        for (String pushRegexp : pushRegexps)
            this.pushRegexps.add(Pattern.compile(pushRegexp));
        for (String allowedPushOrigin : allowedPushOrigins)
            this.allowedPushOrigins.add(Pattern.compile(allowedPushOrigin.replace(".", "\\.").replace("*", ".*")));
    }

    @Override
    public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders)
    {
        Set<String> result = Collections.emptySet();
        String scheme = requestHeaders.get("scheme").value();
        String host = requestHeaders.get("host").value();
        String origin = new StringBuilder(scheme).append("://").append(host).toString();
        String url = requestHeaders.get("url").value();
        String absoluteURL = new StringBuilder(origin).append(url).toString();
        logger.debug("Applying push strategy for {}", absoluteURL);
        if (isValidMethod(requestHeaders.get("method").value()))
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
                return true;
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
            pushResources.add(url);
            logger.debug("Built push metadata for {}: {}", referrer, pushResources);
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
