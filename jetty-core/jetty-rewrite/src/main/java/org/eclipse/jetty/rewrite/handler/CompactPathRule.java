//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.URIUtil;

/**
 * <p>Rewrites the URI by compacting to remove occurrences of {@code //}.</p>
 * <p>For example, {@code //foo/bar//baz} is compacted to {@code /foo/bar/baz}.</p>
 */
public class CompactPathRule extends Rule
{
    @Override
    public Request.WrapperProcessor matchAndApply(Request.WrapperProcessor input) throws IOException
    {
        String path = input.getPathInContext();
        String compacted = URIUtil.compactPath(path);

        if (path.equals(compacted))
            return null;

        return new Request.WrapperProcessor(input)
        {
            @Override
            public String getPathInContext()
            {
                return compacted;
            }
        };
    }
}
