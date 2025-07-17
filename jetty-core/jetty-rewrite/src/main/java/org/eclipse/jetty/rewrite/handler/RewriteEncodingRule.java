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
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Rule that can rewrite a request based on the {@code Accept-Encoding} header.
 */
public class RewriteEncodingRule extends Rule
{
    public static Resource getResource(Context context, String pathInContext)
    {
        return context.getBaseResource().resolve(pathInContext);
    }

    protected record Encoding(String encoding, String extension, HttpField contentEncodingField)
    {
        public static Encoding of(String encoding, String extension)
        {
           return new Encoding(encoding, extension, new PreEncodedHttpField(HttpHeader.CONTENT_ENCODING, encoding));
        }
    }

    private static final HttpField VARY_ACCEPT_ENCODING = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_ENCODING.asString());
    private final BiFunction<Context, String, Resource> _getResource;
    private final Map<String, Encoding> _encodings = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

    protected RewriteEncodingRule()
    {
        this(RewriteEncodingRule::getResource, "br", ".br", "gzip", ".gz");
    }

    protected RewriteEncodingRule(BiFunction<Context, String, Resource> getResource)
    {
        this(getResource, "br", ".br", "gzip", ".gz");
    }

    protected RewriteEncodingRule(BiFunction<Context, String, Resource> getResource, String... encodingsAndExtensions)
    {
        _getResource = getResource;
        if (encodingsAndExtensions.length % 2 != 0)
            throw new IllegalArgumentException("encodingsAndExtensions must be an even number of arguments");
        for (int i = 0; i < encodingsAndExtensions.length; i += 2)
            _encodings.put(encodingsAndExtensions[i], Encoding.of(encodingsAndExtensions[i], encodingsAndExtensions[i + 1]));
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        if (input.getHttpURI().getPath().endsWith("/"))
            return input;

        List<String> encodings = input.getHeaders().getQualityCSV(HttpHeader.ACCEPT_ENCODING);
        if (encodings != null && !encodings.isEmpty())
        {
            for (String encodingName : encodings)
            {
                if (encodingName == null)
                    continue;
                if (StringUtil.asciiEqualsIgnoreCase("identity", encodingName))
                    return new VaryHandler(input);

                if ("*".equals(encodingName))
                {
                    for (Map.Entry<String, Encoding> entry: _encodings.entrySet())
                    {
                        String pathInContext = Request.getPathInContext(input);
                        String encodedPathInContext = pathInContext + entry.getValue().extension();
                        Resource resource = _getResource.apply(input.getContext(), encodedPathInContext);
                        if (resource.exists())
                            return newEncodingHandler(input, encodedPathInContext, entry.getValue());
                    }
                }
                else
                {
                    Encoding encoding = _encodings.get(encodingName);
                    if (encoding == null)
                        continue;
                    String pathInContext = Request.getPathInContext(input);
                    String encodedPathInContext = pathInContext + encoding.extension();
                    Resource resource = _getResource.apply(input.getContext(), encodedPathInContext);
                    if (resource.exists())
                        return newEncodingHandler(input, encodedPathInContext, encoding);
                }
            }
        }

        return new VaryHandler(input);
    }

    @Override
    public String toString()
    {
        return "%s@%x".formatted(TypeUtil.toShortName(this.getClass()), hashCode());
    }

    protected Handler newEncodingHandler(Handler input, String languagePathInContext, Encoding encoding)
    {
        return new EncodingHandler(input, languagePathInContext, encoding);
    }

    private void ensureVaryAcceptEncoding(Response response)
    {
        response.getHeaders().computeField(HttpHeader.VARY, (h, l) ->
        {
            if (l == null || l.isEmpty())
                return VARY_ACCEPT_ENCODING;

            boolean acceptEncoding = false;
            StringBuilder vary = new StringBuilder();
            loop:
            for (HttpField field : l)
            {
                for (String value : field.getValues())
                {
                    if (HttpHeader.ACCEPT_ENCODING.asString().equalsIgnoreCase(value))
                    {
                        acceptEncoding = true;
                        break loop;
                    }
                    if (!vary.isEmpty())
                        vary.append(", ");
                    vary.append(value);
                }
            }

            if (!acceptEncoding)
                vary.append(", ").append(HttpHeader.ACCEPT_ENCODING.asString());

            return new HttpField(HttpHeader.VARY, vary.toString());
        });
    }

    protected class VaryHandler extends Handler
    {
        public VaryHandler(Rule.Handler input)
        {
            super(input);
        }

        @Override
        protected boolean handle(Response response, Callback callback) throws Exception
        {
            // Add the vary header
            ensureVaryAcceptEncoding(response);
            return super.handle(response, callback);
        }
    }

    protected class EncodingHandler extends Handler
    {
        private static final EnumSet<HttpHeader> IF_MATCHES = EnumSet.of(HttpHeader.IF_MATCH, HttpHeader.IF_NONE_MATCH);
        private static final HttpField VARY_ACCEPT_LANGUAGE = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_LANGUAGE.asString());
        private final Encoding _encoding;
        private final String _dashEncoding;
        private final HttpURI _encodingURI;
        private final HttpFields _httpFields;

        public EncodingHandler(Rule.Handler input, String encodingPathInContext, Encoding encoding)
        {
            super(input);
            _encoding = encoding;
            _dashEncoding = "-" + encoding.encoding();
            _encodingURI = HttpURI.build(input.getHttpURI()).path(URIUtil.addPaths(input.getContext().getContextPath(), encodingPathInContext)).asImmutable();

            HttpFields httpFields = input.getHeaders();
            if (httpFields.contains(IF_MATCHES))
            {
                httpFields = HttpFields.build(httpFields)
                    .computeField(HttpHeader.IF_MATCH, this::computeNoEncodingEtag)
                    .computeField(HttpHeader.IF_NONE_MATCH, this::computeNoEncodingEtag).asImmutable();
            }
            _httpFields = httpFields;
        }

        private HttpField computeNoEncodingEtag(HttpHeader header, List<HttpField> fields)
        {
            if (fields == null || fields.isEmpty())
                return null;
            return new HttpField(header, fields.stream()
                .flatMap(field -> Stream.of(field.getValues()))
                .map(value -> value.replace(_dashEncoding, ""))
                .collect(Collectors.joining(", ")));
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _encodingURI;
        }

        @Override
        public HttpFields getHeaders()
        {
            return _httpFields;
        }

        @Override
        protected boolean handle(Response response, Callback callback) throws Exception
        {
            // Add the encoding field if not already set
            response.getHeaders().computeField(HttpHeader.CONTENT_ENCODING, (h, l) ->
            {
                if (l == null || l.isEmpty())
                    return _encoding.contentEncodingField();
                return l.get(0);
            });

            // Add the vary header
            ensureVaryAcceptEncoding(response);

            // intercept etags
            final HttpFields.Mutable responseFields = new HttpFields.Mutable.Wrapper(response.getHeaders())
            {
                @Override
                public HttpField onAddField(HttpField field)
                {
                    if (field.getHeader() == HttpHeader.ETAG)
                    {
                        String etag = field.getValue();
                        if (etag.endsWith("\""))
                            return new HttpField(HttpHeader.ETAG, etag.substring(0, etag.length() - 1) + _dashEncoding + "\"");
                    }

                    return field;
                }

                @Override
                public HttpField onReplaceField(HttpField oldField, HttpField newField)
                {
                    if (oldField.getHeader() == HttpHeader.VARY && !newField.getValue().contains(HttpHeader.ACCEPT_ENCODING.asString()))
                        return new HttpField(HttpHeader.VARY, newField.getValue() + ", " + HttpHeader.ACCEPT_ENCODING.asString());
                    return onAddField(newField);
                }

                @Override
                public boolean onRemoveField(HttpField field)
                {
                    return !(field.getHeader() == HttpHeader.VARY && field.getValue().contains(HttpHeader.ACCEPT_ENCODING.asString()));
                }
            };

            Response wrappedResponse = new Response.Wrapper(response.getRequest(), response)
            {
                @Override
                public HttpFields.Mutable getHeaders()
                {
                    return responseFields;
                }
            };
            return super.handle(wrappedResponse, callback);
        }
    }
}
