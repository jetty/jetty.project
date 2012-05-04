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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
 *
 * TODO: this class is kind-of leaking since the resources map is always adding entries
 * TODO: although these entries will be limited by the number of application pages.
 * TODO: however, there is no ConcurrentLinkedHashMap yet in JDK (there is in Guava though)
 * TODO: so we cannot use the built-in LRU features of LinkedHashMap
 */
public class ReferrerPushStrategy implements PushStrategy
{
    private static final Logger logger = Log.getLogger(ReferrerPushStrategy.class);
    private final ConcurrentMap<String, Set<String>> resources = new ConcurrentHashMap<>();
    private List<String> mainSuffixes = new ArrayList<>();
    private List<String> pushSuffixes = new ArrayList<>();

    @Override
    public Set<String> apply(Stream stream, Headers requestHeaders, Headers responseHeaders)
    {
        String url = requestHeaders.get("url").value();
        if (!hasQueryString(url))
        {
            if (isMainResource(url, responseHeaders))
            {
                return pushResources(url);
            }
            else if (isPushResource(url, responseHeaders))
            {
                String referrer = requestHeaders.get("referer").value();
                Set<String> pushResources = resources.get(referrer);
                if (pushResources == null || !pushResources.contains(url))
                {
                    buildMetadata(url, referrer);
                }
                else
                {
                    return pushResources(url);
                }
            }
        }
        return Collections.emptySet();
    }

    private boolean hasQueryString(String url)
    {
        return url.contains("?");
    }

    private boolean isMainResource(String url, Headers responseHeaders)
    {
        // TODO
        return false;
    }

    private boolean isPushResource(String url, Headers responseHeaders)
    {
        // TODO
        return false;
    }

    private Set<String> pushResources(String url)
    {
        Set<String> pushResources = resources.get(url);
        if (pushResources == null)
            return Collections.emptySet();
        return Collections.unmodifiableSet(pushResources);
    }

    private void buildMetadata(String url, String referrer)
    {
        Set<String> pushResources = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        Set<String> existing = resources.putIfAbsent(referrer, pushResources);
        if (existing != null)
            pushResources = existing;
        pushResources.add(url);
    }
}
