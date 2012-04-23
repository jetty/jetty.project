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
 * TODO: this class is kind-of leaking since the resources map is always adding entries
 * TODO: although these entries will be limited by the application pages
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
            if (isMainResource(url))
            {
                return pushResources(url);
            }
            else if (isPushResource(url))
            {
                String referrer = requestHeaders.get("referer").value();
                Set<String> pushResources = resources.get(referrer);
                if (pushResources == null || !pushResources.contains(url))
                {
                    buildPushResources(url, referrer);
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

    private boolean isMainResource(String url)
    {
        // TODO
        return false;
    }

    private boolean isPushResource(String url)
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

    private void buildPushResources(String url, String referrer)
    {
        Set<String> pushResources = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
        Set<String> existing = resources.putIfAbsent(referrer, pushResources);
        if (existing != null)
            pushResources = existing;
        pushResources.add(url);
    }
}
