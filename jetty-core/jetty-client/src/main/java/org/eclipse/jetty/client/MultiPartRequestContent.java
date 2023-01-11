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

package org.eclipse.jetty.client;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.MultiPart;
import org.eclipse.jetty.http.MultiPartFormData;
import org.eclipse.jetty.io.Content;

/**
 * <p>A {@link Request.Content} for form uploads with the {@code "multipart/form-data"}
 * content type.</p>
 * <p>Example usage:</p>
 * <pre>
 * MultiPartRequestContent multiPart = new MultiPartRequestContent();
 * multiPart.addPart(new MultiPart.ContentSourcePart("field", null, HttpFields.EMPTY, new StringRequestContent("foo")));
 * multiPart.addPart(new MultiPart.PathPart("icon", "img.png", HttpFields.EMPTY, Path.of("/tmp/img.png")));
 * multiPart.close();
 * ContentResponse response = client.newRequest("localhost", connector.getLocalPort())
 *         .method(HttpMethod.POST)
 *         .content(multiPart)
 *         .send();
 * </pre>
 * <p>The above example would be the equivalent of submitting this form:</p>
 * <pre>
 * &lt;form method="POST" enctype="multipart/form-data"  accept-charset="UTF-8"&gt;
 *     &lt;input type="text" name="field" value="foo" /&gt;
 *     &lt;input type="file" name="icon" /&gt;
 * &lt;/form&gt;
 * </pre>
 */
public class MultiPartRequestContent extends MultiPartFormData.ContentSource implements Request.Content
{
    private final String contentType;

    public MultiPartRequestContent()
    {
        this(MultiPart.generateBoundary("JettyHttpClient-", 24));
    }

    public MultiPartRequestContent(String boundary)
    {
        super(boundary);
        this.contentType = "multipart/form-data; boundary=\"%s\"".formatted(boundary);
    }

    @Override
    public String getContentType()
    {
        return contentType;
    }

    @Override
    protected HttpFields customizePartHeaders(MultiPart.Part part)
    {
        HttpFields headers = super.customizePartHeaders(part);
        if (headers.contains(HttpHeader.CONTENT_TYPE))
            return headers;

        Content.Source partContent = part.getContent();
        if (partContent instanceof Request.Content requestContent)
        {
            String contentType = requestContent.getContentType();
            if (contentType != null)
                return HttpFields.build(headers).put(HttpHeader.CONTENT_TYPE, contentType);
        }

        return headers;
    }
}
