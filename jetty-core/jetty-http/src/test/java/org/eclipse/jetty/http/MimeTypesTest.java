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

package org.eclipse.jetty.http;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertNull;

public class MimeTypesTest
{
    public static Stream<Arguments> mimeTypesByExtensionCases()
    {
        return Stream.of(
            Arguments.of("test.gz", "application/gzip"),
            Arguments.of("test.tar.gz", "application/gzip"),
            Arguments.of("test.tgz", "application/x-gtar"),
            Arguments.of("foo.webp", "image/webp"),
            Arguments.of("zed.avif", "image/avif"),
            // make sure that filename case isn't an issue
            Arguments.of("test.png", "image/png"),
            Arguments.of("TEST.PNG", "image/png"),
            Arguments.of("Test.Png", "image/png"),
            Arguments.of("test.txt", "text/plain"),
            Arguments.of("TEST.TXT", "text/plain"),
            // Make sure that multiple dots don't interfere
            Arguments.of("org.eclipse.jetty.Logo.png", "image/png"),
            // Make sure that a deep path doesn't interfere
            Arguments.of("org/eclipse/jetty/Logo.png", "image/png"),
            // Make sure that path that looks like a filename doesn't interfere
            Arguments.of("org/eclipse.jpg/jetty/Logo.png", "image/png")
        );
    }

    @ParameterizedTest
    @MethodSource("mimeTypesByExtensionCases")
    public void testMimeTypesByExtension(String filename, String expectedMimeType)
    {
        MimeTypes mimetypes = new MimeTypes();
        String contentType = mimetypes.getMimeByExtension(filename);
        assertThat("MimeTypes.getMimeByExtension(\"" + filename + "\")",
            contentType, is(expectedMimeType));
    }

    @Test
    public void testGetMimeByExtensionNoExtension()
    {
        MimeTypes mimetypes = new MimeTypes();
        String contentType = mimetypes.getMimeByExtension("README");
        assertNull(contentType);
    }

    public static Stream<Arguments> charsetFromContentTypeCases()
    {
        return Stream.of(
            Arguments.of("foo/bar;charset=abc;some=else", "abc"),
            Arguments.of("foo/bar;charset=abc", "abc"),
            Arguments.of("foo/bar ; charset = abc", "abc"),
            Arguments.of("foo/bar ; charset = abc ; some=else", "abc"),
            Arguments.of("foo/bar;other=param;charset=abc;some=else", "abc"),
            Arguments.of("foo/bar;other=param;charset=abc", "abc"),
            Arguments.of("foo/bar other = param ; charset = abc", "abc"),
            Arguments.of("foo/bar other = param ; charset = abc ; some=else", "abc"),
            Arguments.of("foo/bar other = param ; charset = abc", "abc"),
            Arguments.of("foo/bar other = param ; charset = \"abc\" ; some=else", "abc"),
            Arguments.of("foo/bar", null),
            Arguments.of("foo/bar;charset=uTf8", "utf-8"),
            Arguments.of("foo/bar;other=\"charset=abc\";charset=uTf8", "utf-8"),
            Arguments.of("application/pdf; charset=UTF-8", "utf-8"),
            Arguments.of("application/pdf;; charset=UTF-8", "utf-8"),
            Arguments.of("application/pdf;;; charset=UTF-8", "utf-8"),
            Arguments.of("application/pdf;;;; charset=UTF-8", "utf-8"),
            Arguments.of("text/html;charset=utf-8", "utf-8")
        );
    }

    @ParameterizedTest
    @MethodSource("charsetFromContentTypeCases")
    public void testCharsetFromContentType(String contentType, String expectedCharset)
    {
        assertThat("getCharsetFromContentType(\"" + contentType + "\")",
            MimeTypes.getCharsetFromContentType(contentType), is(expectedCharset));
    }

    public static Stream<Arguments> contentTypeWithoutCharsetCases()
    {
        return Stream.of(
            Arguments.of("foo/bar;charset=abc;some=else", "foo/bar;some=else"),
            Arguments.of("foo/bar;charset=abc", "foo/bar"),
            Arguments.of("foo/bar ; charset = abc", "foo/bar"),
            Arguments.of("foo/bar ; charset = abc ; some=else", "foo/bar;some=else"),
            Arguments.of("foo/bar;other=param;charset=abc;some=else", "foo/bar;other=param;some=else"),
            Arguments.of("foo/bar;other=param;charset=abc", "foo/bar;other=param"),
            Arguments.of("foo/bar ; other = param ; charset = abc", "foo/bar ; other = param"),
            Arguments.of("foo/bar ; other = param ; charset = abc ; some=else", "foo/bar ; other = param;some=else"),
            Arguments.of("foo/bar ; other = param ; charset = abc", "foo/bar ; other = param"),
            Arguments.of("foo/bar ; other = param ; charset = \"abc\" ; some=else", "foo/bar ; other = param;some=else"),
            Arguments.of("foo/bar", "foo/bar"),
            Arguments.of("foo/bar;charset=uTf8", "foo/bar"),
            Arguments.of("foo/bar;other=\"charset=abc\";charset=uTf8", "foo/bar;other=\"charset=abc\""),
            Arguments.of("text/html;charset=utf-8", "text/html")
        );
    }

    @ParameterizedTest
    @MethodSource("contentTypeWithoutCharsetCases")
    public void testContentTypeWithoutCharset(String contentTypeWithCharset, String expectedContentType)
    {
        assertThat("MimeTypes.getContentTypeWithoutCharset(\"" + contentTypeWithCharset + "\")",
            MimeTypes.getContentTypeWithoutCharset(contentTypeWithCharset), is(expectedContentType));
    }

    public static Stream<Arguments> mimeTypesGetBaseTypeCases()
    {
        return Stream.of(
            Arguments.of("foo/bar", null),
            Arguments.of("foo/bar;charset=abc;some=else", null),
            Arguments.of("text/html", MimeTypes.Type.TEXT_HTML),
            Arguments.of("text/html;charset=utf-8", MimeTypes.Type.TEXT_HTML),
            Arguments.of("text/html; charset=iso-8859-1", MimeTypes.Type.TEXT_HTML),
            Arguments.of("text/html;charset=utf-8;other=param", MimeTypes.Type.TEXT_HTML),
            Arguments.of("text/html;other=param;charset=iso-8859-1", MimeTypes.Type.TEXT_HTML),

            Arguments.of(null, null)
        );
    }

    @ParameterizedTest
    @MethodSource("mimeTypesGetBaseTypeCases")
    public void testMimeTypesGetBaseType(String contentTypeWithCharset, MimeTypes.Type expectedType)
    {
        MimeTypes.CACHE.keySet().forEach(System.err::println);
        assertThat(MimeTypes.getBaseType(contentTypeWithCharset), is(expectedType));
    }

    @Test
    public void testWrapper()
    {
        MimeTypes.Wrapper wrapper = new MimeTypes.Wrapper();
        assertThat(wrapper.getMimeMap().size(), is(0));
        assertThat(wrapper.getInferredMap().size(), is(0));
        assertThat(wrapper.getAssumedMap().size(), is(0));

        wrapper.addMimeMapping("txt", "text/plain");
        wrapper.addInferred("text/plain", "usascii");
        wrapper.addAssumed("json", "utf-8");

        assertThat(wrapper.getMimeMap().size(), is(1));
        assertThat(wrapper.getInferredMap().size(), is(1));
        assertThat(wrapper.getAssumedMap().size(), is(1));
        assertThat(wrapper.getMimeByExtension("fee.txt"), is("text/plain"));
        assertThat(wrapper.getCharsetInferredFromContentType("text/plain"), is("usascii"));
        assertThat(wrapper.getCharsetAssumedFromContentType("json"), is("utf-8"));

        MimeTypes.Mutable wrapped = new MimeTypes.Mutable(null);
        wrapper.setWrapped(wrapped);

        assertThat(wrapper.getMimeMap().size(), is(1));
        assertThat(wrapper.getInferredMap().size(), is(1));
        assertThat(wrapper.getAssumedMap().size(), is(1));
        assertThat(wrapper.getMimeByExtension("fee.txt"), is("text/plain"));
        assertThat(wrapper.getCharsetInferredFromContentType("text/plain"), is("usascii"));
        assertThat(wrapper.getCharsetAssumedFromContentType("json"), is("utf-8"));

        wrapped.addMimeMapping("txt", "overridden");
        wrapped.addInferred("text/plain", "overridden");
        wrapped.addAssumed("json", "overridden");

        assertThat(wrapper.getMimeMap().size(), is(1));
        assertThat(wrapper.getInferredMap().size(), is(1));
        assertThat(wrapper.getAssumedMap().size(), is(1));
        assertThat(wrapper.getMimeByExtension("fee.txt"), is("text/plain"));
        assertThat(wrapper.getCharsetInferredFromContentType("text/plain"), is("usascii"));
        assertThat(wrapper.getCharsetAssumedFromContentType("json"), is("utf-8"));

        wrapped.addMimeMapping("xml", "text/xml");
        wrapped.addInferred("text/xml", "iso-8859-1");
        wrapped.addAssumed("text/xxx", "assumed");
        assertThat(wrapped.getMimeMap().size(), is(2));
        assertThat(wrapped.getInferredMap().size(), is(2));
        assertThat(wrapped.getAssumedMap().size(), is(2));

        assertThat(wrapper.getMimeMap().size(), is(2));
        assertThat(wrapper.getInferredMap().size(), is(2));
        assertThat(wrapper.getAssumedMap().size(), is(2));
        assertThat(wrapper.getMimeByExtension("fee.txt"), is("text/plain"));
        assertThat(wrapper.getCharsetInferredFromContentType("text/plain"), is("usascii"));
        assertThat(wrapper.getCharsetAssumedFromContentType("json"), is("utf-8"));
        assertThat(wrapper.getMimeByExtension("fee.xml"), is("text/xml"));
        assertThat(wrapper.getCharsetInferredFromContentType("text/xml"), is("iso-8859-1"));
        assertThat(wrapper.getCharsetAssumedFromContentType("text/xxx"), is("assumed"));
    }
}
