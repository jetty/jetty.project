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

package org.eclipse.jetty.server;

import java.util.concurrent.CompletableFuture;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MimeTypes;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.util.Attributes;

public class MultiPartFormFields
{
    public static CompletableFuture<MultiPartFormData.Parts> from(Request request, MultiPartConfig config)
    {
        return from(request, request, config);
    }

    public static CompletableFuture<MultiPartFormData.Parts> from(Request request, Content.Source source, MultiPartConfig config)
    {
        String contentType = request.getHeaders().get(HttpHeader.CONTENT_TYPE);

        return from(source, request, contentType, config);
    }

    public static CompletableFuture<MultiPartFormData.Parts> from(Content.Source content, Attributes attributes, String contentType, MultiPartConfig config)
    {
        // Look for an existing future (we use the future here rather than the parts as it can remember any failure).
        CompletableFuture<MultiPartFormData.Parts> futureParts = MultiPartFormData.get(attributes);
        if (futureParts == null)
        {
            // No existing parts, so we need to try to read them ourselves

            // Are we the right content type to produce our own parts?
            if (contentType == null || !MimeTypes.Type.MULTIPART_FORM_DATA.is(HttpField.getValueParameters(contentType, null)))
                return CompletableFuture.failedFuture(new IllegalStateException("Not multipart Content-Type"));

            // Do we have a boundary?
            String boundary = MultiPart.extractBoundary(contentType);
            if (boundary == null)
                return CompletableFuture.failedFuture(new IllegalStateException("No multipart boundary parameter in Content-Type"));

            // Look for an existing future MultiPartFormData.Parts
            futureParts = MultiPartFormData.from(attributes, config.getMultiPartCompliance(), config.getViolationListener(), boundary, parser ->
            {
                try
                {
                    // No existing core parts, so we need to configure the parser.
                    parser.setMaxParts(config.getMaxFormKeys());
                    parser.setMaxMemoryFileSize(config.getFileSizeThreshold());
                    parser.setMaxFileSize(config.getMaxFileSize());
                    parser.setMaxLength(config.getMaxRequestSize());
                    parser.setPartHeadersMaxLength(config.getMaxHeadersSize());
                    if (config.getLocation() != null)
                        parser.setFilesDirectory(config.getLocation());

                    // parse the core parts.
                    return parser.parse(content);
                }
                catch (Throwable failure)
                {
                    return CompletableFuture.failedFuture(failure);
                }
            });
        }
        return futureParts;
    }
}
