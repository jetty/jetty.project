//
//  ========================================================================
//  Copyright (c) 1995-2019 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

/**
 * HTTP (RFC) Spec Compliance behavior.
 *
 * <p>
 * Configurable behavior of HTTP/1.x with regards to compliance to current
 * (<a href="https://tools.ietf.org/html/rfc7230">RFC 7230: HTTP/1.1</a>)
 * and obsolete (<a href="https://tools.ietf.org/html/rfc2616">RFC 2616: HTTP/1.1</a>) Specs.
 * </p>
 *
 * <p>
 * To define a custom Compliance behavior, start with a {@link Builder} based on either
 * {@link #rfc7230Builder()} or {@link #rfc2616Builder()}, modify it (or not), and then {@link Builder#build()} it.
 * </p>
 *
 * <code>
 * HttpCompliance compliance = HttpCompliance.rfc7230Builder()
 *     .allowCaseInsensitiveFieldNames(true)
 *     .requireColonAfterFieldName(true)
 *     .allowHttp09(false)
 *     .allowMultipleContentLengths(true)
 *     .build();
 * </code>
 */
public final class HttpCompliance
{
    /**
     * Get the Default Strict HttpCompliance of <a href="https://tools.ietf.org/html/rfc7230">RFC 7230: HTTP/1.1</a>
     */
    public static final HttpCompliance RFC7230 = HttpCompliance.rfc7230Builder().build();

    /**
     * Get the Default Strict HttpCompliance of <a href="https://tools.ietf.org/html/rfc2616">RFC 2616: HTTP/1.1</a>
     */
    public static final HttpCompliance RFC2616 = HttpCompliance.rfc2616Builder().build();

    // Identification of the mode
    private String mode;
    // The default values here are just Jetty defaults and not associated with any specific RFC/Spec
    // Use the appropriate builder to start with a specific RFC/Spec behavior
    private boolean allowCaseInsensitiveFieldNames = false;
    private boolean allowCaseInsensitiveRequestMethod = true;
    private boolean allowHttp09 = false;
    private boolean allowMultiLineFieldValue = false;
    private boolean allowMultipleContentLengths = false;
    private boolean allowTransferEncodingWithContentLength = false;
    private boolean allowWhitespaceAfterFieldName = false;
    private boolean requireColonAfterFieldName = true;
    private boolean useCaseInsensitiveFieldValueCache = true;

    // Prevent direct instantiation
    private HttpCompliance()
    {
    }

    /**
     * <p>
     * Builder based on obsolete <a href="https://tools.ietf.org/html/rfc2616">RFC 2616: HTTP/1.1</a>.
     * </p>
     *
     * <table>
     * <caption>RFC 2616 Defaults</caption>
     * <thead>
     * <tr><td>Configurable</td><td>Default Value</td></tr>
     * </thead>
     * <tbody>
     * <tr><td>allowCaseInsensitiveFieldNames</td><td>true</td></tr>
     * <tr><td>allowCaseInsensitiveRequestMethod</td><td>true</td></tr>
     * <tr><td>allowHttp09</td><td>true</td></tr>
     * <tr><td>allowMultiLineFieldValue</td><td>true</td></tr>
     * <tr><td>allowMultipleContentLengths</td><td>true</td></tr>
     * <tr><td>allowTransferEncodingWithContentLength</td><td>true</td></tr>
     * <tr><td>allowWhitespaceAfterFieldName</td><td>true</td></tr>
     * <tr><td>requireColonAfterFieldName</td><td>true</td></tr>
     * <tr><td>useCaseInsensitiveFieldValueCache</td><td>true</td></tr>
     * </tbody>
     * </table>
     *
     * @return new Builder with configuration set to RFC2616 Strict behavior.
     */
    public static Builder rfc2616Builder()
    {
        return new Builder()
                .allowCaseInsensitiveFieldNames(true)
                .allowCaseInsensitiveRequestMethod(true)
                .allowHttp09(true)
                .allowMultiLineFieldValue(true)
                .allowMultipleContentLengths(true)
                .allowTransferEncodingWithContentLength(true)
                .allowWhitespaceAfterFieldName(true)
                .requireColonAfterFieldName(false)
                .useCaseInsensitiveFieldValueCache(true)
                .asMode("RFC2616");
    }

    /**
     * <p>
     * Builder based on current <a href="https://tools.ietf.org/html/rfc7230">RFC 7230: HTTP/1.1</a>.
     * </p>
     *
     * <table>
     * <caption>RFC 7230 Defaults</caption>
     * <thead>
     * <tr><td>Configurable</td><td>Default Value</td></tr>
     * </thead>
     * <tbody>
     * <tr><td>allowCaseInsensitiveFieldNames</td><td>true</td></tr>
     * <tr><td>allowCaseInsensitiveRequestMethod</td><td>false</td></tr>
     * <tr><td>allowHttp09</td><td>false</td></tr>
     * <tr><td>allowMultiLineFieldValue</td><td>false</td></tr>
     * <tr><td>allowMultipleContentLengths</td><td>false</td></tr>
     * <tr><td>allowTransferEncodingWithContentLength</td><td>false</td></tr>
     * <tr><td>allowWhitespaceAfterFieldName</td><td>false</td></tr>
     * <tr><td>requireColonAfterFieldName</td><td>true</td></tr>
     * <tr><td>useCaseInsensitiveFieldValueCache</td><td>false</td></tr>
     * </tbody>
     * </table>
     *
     * @return new Builder with configuration set to RFC7230 Strict behavior.
     */
    public static Builder rfc7230Builder()
    {
        return new Builder()
                .allowCaseInsensitiveFieldNames(true)
                .allowCaseInsensitiveRequestMethod(false)
                .allowHttp09(false)
                .allowMultiLineFieldValue(false)
                .allowMultipleContentLengths(false)
                .allowTransferEncodingWithContentLength(false)
                .allowWhitespaceAfterFieldName(false)
                .requireColonAfterFieldName(true)
                .useCaseInsensitiveFieldValueCache(false)
                .asMode("RFC7230");
    }

    public boolean allowCaseInsensitiveFieldNames()
    {
        return allowCaseInsensitiveFieldNames;
    }

    public boolean allowCaseInsensitiveRequestMethod()
    {
        return allowCaseInsensitiveRequestMethod;
    }

    public boolean allowHttp09()
    {
        return allowHttp09;
    }

    public boolean allowMultiLineFieldValue()
    {
        return allowMultiLineFieldValue;
    }

    public boolean allowMultipleContentLengths()
    {
        return allowMultipleContentLengths;
    }

    public boolean allowTransferEncodingWithContentLength()
    {
        return allowTransferEncodingWithContentLength;
    }

    public boolean allowWhitespaceAfterFieldName()
    {
        return allowWhitespaceAfterFieldName;
    }

    public boolean requireColonAfterFieldName()
    {
        return requireColonAfterFieldName;
    }

    @Override
    public String toString()
    {
        return String.format("HttpCompliance.%s", mode);
    }

    public boolean useCaseInsensitiveFieldValueCache()
    {
        return useCaseInsensitiveFieldValueCache;
    }

    public static class Builder
    {
        private String baseMode;
        private HttpCompliance compliance;
        private boolean dirty = false;

        private Builder()
        {
            this.compliance = new HttpCompliance();
        }

        /**
         * Field name is case-insensitive. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.2">RFC 7230: Section 3.2 - Header Fields</a>
         * the parsing of Header Field names is case insensitive.
         * </p>
         *
         * <p>
         * If this configuration option is disabled with {@code false} then two fields with
         * a case difference will result in different header fields being produced.
         * </p>
         * <p>
         * TODO: document behavior better
         *
         * @param flag true for case insensitive field name parsing.
         */
        public Builder allowCaseInsensitiveFieldNames(boolean flag)
        {
            dirty |= (compliance.allowCaseInsensitiveFieldNames != flag);
            compliance.allowCaseInsensitiveFieldNames = flag;
            return this;
        }

        /**
         * The Request Method is Case Sensitive. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.1.1">RFC 7230: Section 3.1.1 - Request Line</a> and
         * <a href="https://tools.ietf.org/html/rfc7231#section-4">RFC 7231: Section 4 - Request Methods</a>
         * the Request Method is mandated as being Case Sensitive.
         * </p>
         * <p>
         * TODO: document what happens if set to false and lowercase method is encountered
         *
         * @param flag true to have case sensitive method name, false for case-insensitive mode.
         */
        public Builder allowCaseInsensitiveRequestMethod(boolean flag)
        {
            dirty |= (compliance.allowCaseInsensitiveRequestMethod != flag);
            compliance.allowCaseInsensitiveRequestMethod = flag;
            return this;
        }

        /**
         * HTTP/0.9 support. (HTTP Parsing Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#appendix-A.2">RFC 7230: Appendix A.2 - Changes from RFC2616</a>
         * the HTTP/0.9 requests have been deprecated.
         * </p>
         *
         * @param flag true to enable HTTP/0.9 support, false to disable it.
         */
        public Builder allowHttp09(boolean flag)
        {
            dirty |= (compliance.allowHttp09 != flag);
            compliance.allowHttp09 = flag;
            return this;
        }

        /**
         * Field Value Line Folding. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230: Section 3.2.4 - Field Parsing</a>
         * the historical (now deprecated) behavior of allowing a HTTP header field value to be extended over multiple lines
         * using {@code obs-fold} to indicate extra lines.
         * </p>
         * <p>
         * TODO: document, demonstrate line folding
         */
        public Builder allowMultiLineFieldValue(boolean flag)
        {
            dirty |= (compliance.allowMultiLineFieldValue != flag);
            compliance.allowMultiLineFieldValue = flag;
            return this;
        }

        /**
         * Allow Multiple {@code Content-Length} headers. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230: Section 3.3.2 - Content-Length</a>
         * if multiple {@code Content-Length} request headers are encountered, the request MUST be rejected.
         * </p>
         * <p>
         * TODO: document and example
         * TODO: indicate security issue with allowing this
         *
         * @param flag true to allow multiple {@code Content-Length} headers, false to reject request
         * with multiple {@code Content-Length} headers.
         */
        public Builder allowMultipleContentLengths(boolean flag)
        {
            dirty |= (compliance.allowMultipleContentLengths != flag);
            compliance.allowMultipleContentLengths = flag;
            return this;
        }

        /**
         * Requests with both {@code Transfer-Encoding} and {@code Content-Length} headers. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.3.2">RFC 7230: Section 3.3.2 - Content-Length</a>
         * when a {@code Transfer-Encoding} request header is provided, the request MUST not contain a
         * {@code Content-Length} header.
         * </p>
         * <p>
         * TODO: document.
         * TODO: indicate security issue with allowing this
         *
         * @param flag true to allow both {@code Transfer-Encoding} and {@code Content-Length} headers, false to fail requests
         * with both headers.
         */
        public Builder allowTransferEncodingWithContentLength(boolean flag)
        {
            dirty |= (compliance.allowTransferEncodingWithContentLength != flag);
            compliance.allowTransferEncodingWithContentLength = flag;
            return this;
        }

        /**
         * Whitespace Allowed after Field Name.  (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.2.4">RFC 7230: Section 3.2.4 - Field Parsing</a>
         * there is no whitespace allowed between the Header Field Name and the Colon {@code ":"} character.
         * </p>
         *
         * @param flag true to allow whitespace after field name (and before colon), false to not allow whitespace between field name and colon.
         */
        public Builder allowWhitespaceAfterFieldName(boolean flag)
        {
            dirty |= (compliance.allowWhitespaceAfterFieldName != flag);
            compliance.allowWhitespaceAfterFieldName = flag;
            return this;
        }

        public HttpCompliance build()
        {
            compliance.mode = baseMode + (dirty ? ".CUSTOMIZED" : "");
            return compliance;
        }

        /**
         * Request Fields must have a Colon. (HTTP/1.x Parser Behavior)
         *
         * <p>
         * In <a href="https://tools.ietf.org/html/rfc7230#section-3.2">RFC 7230: Section 3.2 - Header Fields</a>
         * a proper Header Field must have a colon {@code ":"} character to separate the name from the value.
         * </p>
         *
         * <p>
         * Note: if you chose {@code false} for this configuration, know that it can impact behaviors
         * surrounding fields utilizing line folding, resulting in fields with line folding being seen
         * as separate fields by the Parser.
         * </p>
         *
         * @param flag true for mandated field colon, false to process potential field without colon.
         */
        public Builder requireColonAfterFieldName(boolean flag)
        {
            dirty |= (compliance.requireColonAfterFieldName != flag);
            compliance.requireColonAfterFieldName = flag;
            return this;
        }

        /**
         * Internal Field Value Cache is case-insensitive. (HTTP/1.x Parser Behavior)
         * <p>
         * TODO: document behaviors here
         *
         * @param flag true for case-insensitive, false for case sensitive behaviors.
         */
        public Builder useCaseInsensitiveFieldValueCache(boolean flag)
        {
            dirty |= (compliance.useCaseInsensitiveFieldValueCache != flag);
            compliance.useCaseInsensitiveFieldValueCache = flag;
            return this;
        }

        private Builder asMode(String baseMode)
        {
            this.baseMode = baseMode;
            this.dirty = false;
            return this;
        }
    }
}
