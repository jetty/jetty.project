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

package org.eclipse.jetty.client;

import java.util.Locale;

import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpMethod;

public abstract class AbstractResponseListener implements Response.Listener
{
    private final int maxLength;
    private String encoding;
    private String mediaType;

    public AbstractResponseListener()
    {
        this(2 * 1024 * 1024);
    }

    public AbstractResponseListener(int maxLength)
    {
        if (maxLength < 0)
            throw new IllegalArgumentException("Invalid max length " + maxLength);
        this.maxLength = maxLength;
    }

    public String getEncoding()
    {
        return encoding;
    }

    public int getMaxLength()
    {
        return maxLength;
    }

    public String getMediaType()
    {
        return mediaType;
    }

    @Override
    public void onHeaders(Response response)
    {
        Request request = response.getRequest();
        HttpFields headers = response.getHeaders();
        long length = headers.getLongField(HttpHeader.CONTENT_LENGTH);
        if (HttpMethod.HEAD.is(request.getMethod()))
            length = 0;
        if (length > maxLength)
        {
            response.abort(new IllegalArgumentException("Buffering capacity " + maxLength + " exceeded"));
            return;
        }

        String contentType = headers.get(HttpHeader.CONTENT_TYPE);
        if (contentType != null)
        {
            String media = contentType;

            String charset = "charset=";
            int index = contentType.toLowerCase(Locale.ENGLISH).indexOf(charset);
            if (index > 0)
            {
                media = contentType.substring(0, index);
                String encoding = contentType.substring(index + charset.length());
                // Sometimes charsets arrive with an ending semicolon.
                int semicolon = encoding.indexOf(';');
                if (semicolon > 0)
                    encoding = encoding.substring(0, semicolon).trim();
                // Sometimes charsets are quoted.
                int lastIndex = encoding.length() - 1;
                if (encoding.charAt(0) == '"' && encoding.charAt(lastIndex) == '"')
                    encoding = encoding.substring(1, lastIndex).trim();
                this.encoding = encoding;
            }

            int semicolon = media.indexOf(';');
            if (semicolon > 0)
                media = media.substring(0, semicolon).trim();
            this.mediaType = media;
        }
    }
}
