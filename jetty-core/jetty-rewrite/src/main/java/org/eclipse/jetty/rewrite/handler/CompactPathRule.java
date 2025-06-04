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

package org.eclipse.jetty.rewrite.handler;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.http.BadMessageException;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Rewrites the URI by compacting to remove occurrences of {@code //} and {@code /../}.</p>
 *
 * <p>Optionally decode the path before compacting.</p>
 * <p>Optionally canonicalize path before compacting.</p>
 *
 * <table>
 *     <caption>Examples</caption>
 *     <tr>
 *       <th>isDecoding</th>
 *       <th>isCanonicalizing</th>
 *       <th>Input Path</th>
 *       <th>Resulting Path</th>
 *     </tr>
 *     <tr>
 *       <td>false (default)</td>
 *       <td>false (default)</td>
 *       <td>{@code //foo/bar//baz}</td>
 *       <td>{@code /foo/bar/baz}</td>
 *     </tr>
 *     <tr>
 *       <td>false (default)</td>
 *       <td>false (default)</td>
 *       <td>{@code //foo/../bar//baz}</td>
 *       <td>{@code /bar/baz}</td>
 *     </tr>
 *     <tr>
 *       <td>true</td>
 *       <td>true</td>
 *       <td>{@code //foo/../bar//baz}</td>
 *       <td>{@code /bar/baz}</td>
 *     </tr>
 * </table>
 *
 * @see URIUtil#decodePath(String)
 * @see URIUtil#canonicalPath(String)
 * @see URIUtil#compactPath(String)
 */
public class CompactPathRule extends Rule
{
    public record CompactedEvent(Handler input, String changedPath, String message, CompactPathRule rule)
    {
    }

    public interface Listener
    {
        void onCompactedEvent(CompactedEvent event);
    }

    private List<Listener> listeners = new ArrayList<>();
    private boolean decoding = false;
    private boolean canonicalizing = false;

    public void addListener(Listener listener)
    {
        listeners.add(listener);
    }

    public boolean removeListener(Listener listener)
    {
        return listeners.remove(listener);
    }

    public boolean isCanonicalizing()
    {
        return canonicalizing;
    }

    public void setCanonicalizing(boolean canonicalizing)
    {
        this.canonicalizing = canonicalizing;
    }

    public boolean isDecoding()
    {
        return decoding;
    }

    public void setDecoding(boolean decoding)
    {
        this.decoding = decoding;
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        // Get the path without extra things like path parameters
        String path = input.getHttpURI().getCanonicalPath();
        String cleanedPath = path;

        if (isDecoding())
            cleanedPath = URIUtil.decodePath(cleanedPath);
        if (isCanonicalizing())
        {
            cleanedPath = URIUtil.canonicalPath(cleanedPath);
            if (cleanedPath == null)
            {
                notifyPathCompaction(input, cleanedPath, "Navigate above root URL");

                // Attempted to navigate to above root URL
                HttpURI uri = HttpURI.build(input.getHttpURI())
                    .path("/")
                    .asImmutable();

                return new HttpURIHandler(input, uri);
            }
        }

        cleanedPath = URIUtil.compactPath(cleanedPath);

        if (input.getHttpURI().getPath().equals(cleanedPath))
            return null; // nothing to do, input and cleaned is the same.

        notifyPathCompaction(input, cleanedPath, "Path was changed");

        try
        {
            HttpURI uri = HttpURI.build(input.getHttpURI())
                .path(cleanedPath)
                .asImmutable();

            return new HttpURIHandler(input, uri);
        }
        catch (IllegalArgumentException e)
        {
            throw new BadMessageException("Bad Path");
        }
    }

    private void notifyPathCompaction(Handler input, String cleanedPath, String message)
    {
        CompactedEvent event = new CompactedEvent(input, cleanedPath, message, this);
        for (Listener listener : listeners)
        {
            listener.onCompactedEvent(event);
        }
    }
}
