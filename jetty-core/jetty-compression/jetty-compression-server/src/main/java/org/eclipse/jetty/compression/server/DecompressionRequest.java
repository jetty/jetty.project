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

package org.eclipse.jetty.compression.server;

import java.util.ListIterator;

import org.eclipse.jetty.compression.Compression;
import org.eclipse.jetty.compression.DecoderSource;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.util.component.Destroyable;

public class DecompressionRequest extends Request.Wrapper implements Destroyable
{
    private Compression compression;
    private HttpFields fields;
    private DecoderSource decoderSource;

    public DecompressionRequest(
        Compression compression,
        Request request,
        CompressionConfig config)
    {
        super(request);
        this.compression = compression;
        fields = updateRequestFields(request);
        decoderSource = compression.newDecoderSource(request);
    }

    @Override
    public void demand(Runnable demandCallback)
    {
        decoderSource.demand(demandCallback);
    }

    public void destroy()
    {
        if (decoderSource instanceof Destroyable destroyable)
            destroyable.destroy();
    }

    @Override
    public HttpFields getHeaders()
    {
        if (fields == null)
            return super.getHeaders();
        return fields;
    }

    @Override
    public Content.Chunk read()
    {
        return decoderSource.read();
    }

    private HttpFields updateRequestFields(Request request)
    {
        HttpFields fields = request.getHeaders();
        HttpFields.Mutable newFields = HttpFields.build(fields);
        boolean contentEncodingSeen = false;

        // iterate in reverse to see last content encoding first
        for (ListIterator<HttpField> i = newFields.listIterator(newFields.size()); i.hasPrevious(); )
        {
            HttpField field = i.previous();

            HttpHeader header = field.getHeader();
            if (header == null)
                continue;

            switch (header)
            {
                case CONTENT_ENCODING ->
                {
                    if (!contentEncodingSeen)
                    {
                        contentEncodingSeen = true;

                        if (field.getValue().equalsIgnoreCase(compression.getEncodingName()))
                        {
                            i.set(compression.getXContentEncodingField());
                        }
                        else if (field.containsLast(compression.getEncodingName()))
                        {
                            String v = field.getValue();
                            v = v.substring(0, v.lastIndexOf(','));
                            i.set(new HttpField(HttpHeader.CONTENT_ENCODING, v));
                            i.add(compression.getXContentEncodingField());
                        }
                    }
                }
                case IF_MATCH, IF_NONE_MATCH ->
                {
                    String etags = field.getValue();
                    String etagsNoSuffix = compression.stripSuffixes(etags);
                    if (!etagsNoSuffix.equals(etags))
                    {
                        i.set(new HttpField(field.getHeader(), etagsNoSuffix));
                        request.setAttribute(CompressionHandler.HANDLER_ETAGS, etags);
                    }
                }
                case CONTENT_LENGTH ->
                {
                    i.set(new HttpField("X-Content-Length", field.getValue()));
                }
            }
        }
        return newFields.asImmutable();
    }
}
