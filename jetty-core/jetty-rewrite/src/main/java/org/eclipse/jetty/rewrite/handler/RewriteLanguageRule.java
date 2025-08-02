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
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.http.HttpFields;
import org.eclipse.jetty.http.HttpHeader;
import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.http.PreEncodedHttpField;
import org.eclipse.jetty.server.Context;
import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.util.Callback;
import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Rule that can rewrite a request based on the {@code Accept-Language} header.
 */
public class RewriteLanguageRule extends Rule
{
    public static String localizeWithSuffix(String pathInContext, String language)
    {
        if (pathInContext.isEmpty())
            return "/" + language + "/";
        if (pathInContext.endsWith("/"))
            return pathInContext + language + "/";
        return pathInContext + "." + language;
    }

    public static String localizeWithPrefix(String pathInContext, String language)
    {
        return "/" + language + pathInContext;
    }

    public static Resource getResource(Context context, String pathInContext)
    {
        return context.getBaseResource().resolve(pathInContext);
    }

    private static final HttpField VARY_ACCEPT_LANGUAGE = new PreEncodedHttpField(HttpHeader.VARY, HttpHeader.ACCEPT_LANGUAGE.asString());
    private final BiFunction<String, String, String> _localizePath;
    private final BiFunction<Context, String, Resource> _getResource;
    private final List<String> _wildCardLanguages;

    public RewriteLanguageRule()
    {
        this(true);
    }

    public RewriteLanguageRule(List<String> wildCardLanguages)
    {
        this(RewriteLanguageRule::localizeWithPrefix, RewriteLanguageRule::getResource, wildCardLanguages);
    }

    public RewriteLanguageRule(boolean prefix)
    {
        this(prefix ? RewriteLanguageRule::localizeWithPrefix : RewriteLanguageRule::localizeWithSuffix, RewriteLanguageRule::getResource, null);
    }

    public RewriteLanguageRule(BiFunction<String, String, String> localizePath, BiFunction<Context, String, Resource> getResource)
    {
        this(localizePath, getResource, null);
    }

    public RewriteLanguageRule(BiFunction<String, String, String> localizePath, BiFunction<Context, String, Resource> getResource, List<String> wildCardLanguages)
    {
        _localizePath = localizePath;
        _getResource = getResource;
        _wildCardLanguages = wildCardLanguages == null ? List.of() : Collections.unmodifiableList(wildCardLanguages);
    }

    @Override
    public Handler matchAndApply(Handler input) throws IOException
    {
        List<String> languages = input.getHeaders().getQualityCSV(HttpHeader.ACCEPT_LANGUAGE);
        if (languages != null && !languages.isEmpty())
        {
            for (String language : languages)
            {
                if (language == null)
                    continue;
                if ("*".equals(language))
                {
                    for (String wildCardLanguage : _wildCardLanguages)
                    {
                        String pathInContext = Request.getPathInContext(input);
                        String languagePathInContext = _localizePath.apply(pathInContext, wildCardLanguage);
                        Resource resource = _getResource.apply(input.getContext(), languagePathInContext);
                        if (resource.exists())
                            return newLanguageHandler(input, languagePathInContext, wildCardLanguage);
                    }
                }
                else
                {
                    String pathInContext = Request.getPathInContext(input);
                    String languagePathInContext = _localizePath.apply(pathInContext, language);
                    Resource resource = _getResource.apply(input.getContext(), languagePathInContext);
                    if (resource.exists())
                        return newLanguageHandler(input, languagePathInContext, language);
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

    protected List<String> getWildCardLanguages()
    {
        return _wildCardLanguages;
    }

    protected Handler newLanguageHandler(Handler input, String languagePathInContext, String language)
    {
        return new LanguageHandler(input, languagePathInContext, language);
    }

    private void ensureVaryAcceptLanguages(Response response)
    {
        response.getHeaders().computeField(HttpHeader.VARY, (h, l) ->
        {
            if (l == null || l.isEmpty())
                return VARY_ACCEPT_LANGUAGE;

            boolean acceptLanguage = false;
            StringBuilder vary = new StringBuilder();
            loop:
            for (HttpField field : l)
            {
                for (String value : field.getValues())
                {
                    if (HttpHeader.ACCEPT_LANGUAGE.asString().equalsIgnoreCase(value))
                    {
                        acceptLanguage = true;
                        break loop;
                    }
                    if (!vary.isEmpty())
                        vary.append(", ");
                    vary.append(value);
                }
            }

            if (!acceptLanguage)
                vary.append(", ").append(HttpHeader.ACCEPT_LANGUAGE.asString());

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
            ensureVaryAcceptLanguages(response);
            return super.handle(response, callback);
        }
    }

    protected class LanguageHandler extends Handler
    {
        private static final EnumSet<HttpHeader> IF_MATCHES = EnumSet.of(HttpHeader.IF_MATCH, HttpHeader.IF_NONE_MATCH);
        private final String _dashLanguage;
        private final HttpURI _languageURI;
        private final HttpField _languageField;
        private final HttpFields _httpFields;

        public LanguageHandler(Rule.Handler input, String languagePathInContext, String language)
        {
            super(input);
            _dashLanguage = '-' + language;
            _languageURI = HttpURI.build(input.getHttpURI()).path(URIUtil.addPaths(input.getContext().getContextPath(), languagePathInContext)).asImmutable();
            _languageField = new HttpField(HttpHeader.CONTENT_LANGUAGE, language);
            HttpFields httpFields = input.getHeaders();
            if (httpFields.contains(IF_MATCHES))
            {
                httpFields = HttpFields.build(httpFields)
                    .computeField(HttpHeader.IF_MATCH, this::computeNoLangEtag)
                    .computeField(HttpHeader.IF_NONE_MATCH, this::computeNoLangEtag).asImmutable();
            }
            _httpFields = httpFields;
        }

        private HttpField computeNoLangEtag(HttpHeader header, List<HttpField> fields)
        {
            if (fields == null || fields.isEmpty())
                return null;
            return new HttpField(header, fields.stream()
                .flatMap(field -> field.getValueList(true).stream())
                .map(value -> value.replace(_dashLanguage, ""))
                .collect(Collectors.joining(", ")));
        }

        @Override
        public HttpURI getHttpURI()
        {
            return _languageURI;
        }

        @Override
        public HttpFields getHeaders()
        {
            return _httpFields;
        }

        @Override
        protected boolean handle(Response response, Callback callback) throws Exception
        {
            // Add the language field if not already set
            response.getHeaders().computeField(HttpHeader.CONTENT_LANGUAGE, (h, l) ->
            {
                if (l == null || l.isEmpty())
                    return _languageField;
                return l.get(0);
            });

            // Add the vary header
            ensureVaryAcceptLanguages(response);

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
                            return new HttpField(HttpHeader.ETAG, etag.substring(0, etag.length() - 1) + _dashLanguage + "\"");
                    }

                    return field;
                }

                @Override
                public HttpField onReplaceField(HttpField oldField, HttpField newField)
                {
                    if (oldField.getHeader() == HttpHeader.VARY && !newField.getValue().contains(HttpHeader.ACCEPT_LANGUAGE.asString()))
                        return new HttpField(HttpHeader.VARY, newField.getValue() + ", " + HttpHeader.ACCEPT_LANGUAGE.asString());
                    return onAddField(newField);
                }

                @Override
                public boolean onRemoveField(HttpField field)
                {
                    return !(field.getHeader() == HttpHeader.VARY && field.getValue().contains(HttpHeader.ACCEPT_LANGUAGE.asString()));
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
