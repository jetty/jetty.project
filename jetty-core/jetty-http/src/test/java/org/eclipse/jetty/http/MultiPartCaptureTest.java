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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.Promise;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class MultiPartCaptureTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            // == Arbitrary / Non-Standard Examples ==

            "multipart-uppercase",
            // "multipart-base64",  // base64 transfer encoding deprecated
            // "multipart-base64-long", // base64 transfer encoding deprecated

            // == Capture of raw request body contents from Apache HttpClient 4.5.5 ==

            "browser-capture-company-urlencoded-apache-httpcomp", "browser-capture-complex-apache-httpcomp", "browser-capture-duplicate-names-apache-httpcomp", "browser-capture-encoding-mess-apache-httpcomp", "browser-capture-nested-apache-httpcomp", "browser-capture-nested-binary-apache-httpcomp", "browser-capture-number-only2-apache-httpcomp", "browser-capture-number-only-apache-httpcomp", "browser-capture-sjis-apache-httpcomp", "browser-capture-strange-quoting-apache-httpcomp", "browser-capture-text-files-apache-httpcomp", "browser-capture-unicode-names-apache-httpcomp", "browser-capture-zalgo-text-plain-apache-httpcomp",

            // == Capture of raw request body contents from Eclipse Jetty Http Client 9.4.9 ==

            "browser-capture-complex-jetty-client", "browser-capture-duplicate-names-jetty-client", "browser-capture-encoding-mess-jetty-client", "browser-capture-nested-jetty-client", "browser-capture-number-only-jetty-client", "browser-capture-sjis-jetty-client", "browser-capture-text-files-jetty-client", "browser-capture-unicode-names-jetty-client", "browser-capture-whitespace-only-jetty-client",

            // == Capture of raw request body contents from various browsers ==

            // simple form - 2 fields
            "browser-capture-form1-android-chrome", "browser-capture-form1-android-firefox", "browser-capture-form1-chrome", "browser-capture-form1-edge", "browser-capture-form1-firefox", "browser-capture-form1-ios-safari", "browser-capture-form1-msie", "browser-capture-form1-osx-safari",

            // form submitted as shift-jis
            "browser-capture-sjis-form-edge", "browser-capture-sjis-form-msie",
            // TODO: these might be addressable via Issue #2398
            // "browser-capture-sjis-form-android-chrome", // contains html encoded character and unspecified charset defaults to utf-8
            // "browser-capture-sjis-form-android-firefox", // contains html encoded character and unspecified charset defaults to utf-8
            // "browser-capture-sjis-form-chrome", // contains html encoded character and unspecified charset defaults to utf-8
            // "browser-capture-sjis-form-firefox", // contains html encoded character and unspecified charset defaults to utf-8
            // "browser-capture-sjis-form-ios-safari", // contains html encoded character and unspecified charset defaults to utf-8
            // "browser-capture-sjis-form-safari", // contains html encoded character and unspecified charset defaults to utf-8

            // form submitted as shift-jis (with HTML5 specific hidden _charset_ field)
            "browser-capture-sjis-charset-form-android-chrome", // contains html encoded character
            "browser-capture-sjis-charset-form-android-firefox", // contains html encoded character
            "browser-capture-sjis-charset-form-chrome", // contains html encoded character
            "browser-capture-sjis-charset-form-edge", "browser-capture-sjis-charset-form-firefox", // contains html encoded character
            "browser-capture-sjis-charset-form-ios-safari", // contains html encoded character
            "browser-capture-sjis-charset-form-msie", "browser-capture-sjis-charset-form-safari", // contains html encoded character

            // form submitted with simple file upload
            "browser-capture-form-fileupload-android-chrome", "browser-capture-form-fileupload-android-firefox", "browser-capture-form-fileupload-chrome", "browser-capture-form-fileupload-edge", "browser-capture-form-fileupload-firefox", "browser-capture-form-fileupload-ios-safari", "browser-capture-form-fileupload-msie", "browser-capture-form-fileupload-safari",

            // form submitted with 2 files (1 binary, 1 text) and 2 text fields
            "browser-capture-form-fileupload-alt-chrome", "browser-capture-form-fileupload-alt-edge", "browser-capture-form-fileupload-alt-firefox", "browser-capture-form-fileupload-alt-msie", "browser-capture-form-fileupload-alt-safari").map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMultipartCapture(String fileName) throws Exception
    {
        Path rawPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".expected.txt");
        MultiPartExpectations expectations = new MultiPartExpectations(expectationPath);

        String boundaryAttribute = "boundary=";
        int boundaryIndex = expectations.contentType.indexOf(boundaryAttribute);
        assertThat(boundaryIndex, greaterThan(0));
        String boundary = QuotedStringTokenizer.unquoteOnly(expectations.contentType.substring(boundaryIndex + boundaryAttribute.length()));

        TestPartsListener listener = new TestPartsListener(expectations);
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        parser.parse(Content.Chunk.from(Files.readAllBytes(rawPath), true));
        listener.assertParts();
    }

    private record NameValue(String name, String value)
    {
    }

    private static class MultiPartExpectations
    {
        public final String contentType;
        public final int partCount;
        public final List<NameValue> partFilenames = new ArrayList<>();
        public final List<NameValue> partSha1Sums = new ArrayList<>();
        public final List<NameValue> partContainsContents = new ArrayList<>();

        public MultiPartExpectations(Path expectationsPath) throws IOException
        {
            String parsedContentType = null;
            String parsedPartCount = "-1";

            try (BufferedReader reader = Files.newBufferedReader(expectationsPath))
            {
                String line;
                while ((line = reader.readLine()) != null)
                {
                    line = line.trim();
                    if (StringUtil.isBlank(line) || line.startsWith("#"))
                    {
                        // skip blanks and comments
                        continue;
                    }

                    String[] split = line.split("\\|");
                    switch (split[0])
                    {
                        case "Request-Header":
                            if (split[1].equalsIgnoreCase("Content-Type"))
                            {
                                parsedContentType = split[2];
                            }
                            break;
                        case "Content-Type":
                            parsedContentType = split[1];
                            break;
                        case "Parts-Count":
                            parsedPartCount = split[1];
                            break;
                        case "Part-ContainsContents":
                        {
                            NameValue pair = new NameValue(split[1], split[2]);
                            partContainsContents.add(pair);
                            break;
                        }
                        case "Part-Filename":
                        {
                            NameValue pair = new NameValue(split[1], split[2]);
                            partFilenames.add(pair);
                            break;
                        }
                        case "Part-Sha1sum":
                        {
                            NameValue pair = new NameValue(split[1], split[2]);
                            partSha1Sums.add(pair);
                            break;
                        }
                        default:
                            throw new IOException("Bad Line in " + expectationsPath + ": " + line);
                    }
                }
            }

            Objects.requireNonNull(parsedContentType, "Missing required 'Content-Type' declaration: " + expectationsPath);
            this.contentType = parsedContentType;
            this.partCount = Integer.parseInt(parsedPartCount);
        }

        private void assertParts(Map<String, List<MultiPart.Part>> allParts) throws Exception
        {
            if (partCount >= 0)
                assertThat(allParts.values().stream().mapToInt(List::size).sum(), is(partCount));

            String defaultCharset = UTF_8.toString();
            List<MultiPart.Part> charSetParts = allParts.get("_charset_");
            if (charSetParts != null)
            {
                Promise.Completable<String> promise = new Promise.Completable<>();
                Content.Source.asString(charSetParts.get(0).getContent(), StandardCharsets.US_ASCII, promise);
                defaultCharset = promise.get();
            }

            for (NameValue expected : partContainsContents)
            {
                List<MultiPart.Part> parts = allParts.get(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                String charset = getCharsetFromContentType(part.getHttpFields().get(HttpHeader.CONTENT_TYPE), defaultCharset);
                Promise.Completable<String> promise = new Promise.Completable<>();
                Content.Source.asString(part.getContent(), Charset.forName(charset), promise);
                assertThat("Part[" + expected.name + "].contents", promise.get(), containsString(expected.value));
            }

            // Evaluate expected filenames
            for (NameValue expected : partFilenames)
            {
                List<MultiPart.Part> parts = allParts.get(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                assertThat("Part[" + expected.name + "]", part.getFileName(), is(expected.value));
            }

            // Evaluate expected contents checksums
            for (NameValue expected : partSha1Sums)
            {
                List<MultiPart.Part> parts = allParts.get(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                MessageDigest digest = MessageDigest.getInstance("SHA1");
                // TODO: is rewind() needed only for this test?
//                assertThat(part.getContent().rewind(), is(true));
                try (InputStream partInputStream = Content.Source.asInputStream(part.getContent());
                     DigestOutputStream digester = new DigestOutputStream(IO.getNullStream(), digest))
                {
                    IO.copy(partInputStream, digester);
                    String actualSha1sum = Hex.asHex(digest.digest()).toLowerCase(Locale.US);
                    assertThat("Part[" + expected.name + "].sha1sum", actualSha1sum, Matchers.equalToIgnoringCase(expected.value));
                }
            }
        }

        private String getCharsetFromContentType(String contentType, String defaultCharset)
        {
            if (StringUtil.isBlank(contentType))
                return defaultCharset;

            QuotedStringTokenizer tok = new QuotedStringTokenizer(contentType, ";", false, false);
            while (tok.hasMoreTokens())
            {
                String str = tok.nextToken().trim();
                if (str.startsWith("charset="))
                {
                    return str.substring("charset=".length());
                }
            }

            return defaultCharset;
        }
    }

    private static class TestPartsListener extends MultiPart.AbstractPartsListener
    {
        // Preserve parts order.
        private final Map<String, List<MultiPart.Part>> parts = new LinkedHashMap<>();
        private final MultiPartExpectations expectations;

        private TestPartsListener(MultiPartExpectations expectations)
        {
            this.expectations = expectations;
        }

        @Override
        public void onPart(MultiPart.Part part)
        {
            parts.compute(part.getName(), (k, v) -> v == null ? new ArrayList<>() : v).add(part);
        }

        private void assertParts() throws Exception
        {
            expectations.assertParts(parts);
        }
    }
}
