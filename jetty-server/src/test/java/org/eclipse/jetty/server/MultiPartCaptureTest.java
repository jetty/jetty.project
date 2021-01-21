//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Stream;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.Part;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@ExtendWith(WorkDirExtension.class)
public class MultiPartCaptureTest
{
    public static final int MAX_FILE_SIZE = 2 * 1024 * 1024;
    public static final int MAX_REQUEST_SIZE = MAX_FILE_SIZE + (60 * 1024);
    public static final int FILE_SIZE_THRESHOLD = 50;

    public static Stream<Arguments> data()
    {
        return Stream.of(
            // == Arbitrary / Non-Standard Examples ==

            "multipart-uppercase",
            // "multipart-base64",  // base64 transfer encoding deprecated
            // "multipart-base64-long", // base64 transfer encoding deprecated

            // == Capture of raw request body contents from Apache HttpClient 4.5.5 ==

            "browser-capture-company-urlencoded-apache-httpcomp",
            "browser-capture-complex-apache-httpcomp",
            "browser-capture-duplicate-names-apache-httpcomp",
            "browser-capture-encoding-mess-apache-httpcomp",
            "browser-capture-nested-apache-httpcomp",
            "browser-capture-nested-binary-apache-httpcomp",
            "browser-capture-number-only2-apache-httpcomp",
            "browser-capture-number-only-apache-httpcomp",
            "browser-capture-sjis-apache-httpcomp",
            "browser-capture-strange-quoting-apache-httpcomp",
            "browser-capture-text-files-apache-httpcomp",
            "browser-capture-unicode-names-apache-httpcomp",
            "browser-capture-zalgo-text-plain-apache-httpcomp",

            // == Capture of raw request body contents from Eclipse Jetty Http Client 9.4.9 ==

            "browser-capture-complex-jetty-client",
            "browser-capture-duplicate-names-jetty-client",
            "browser-capture-encoding-mess-jetty-client",
            "browser-capture-nested-jetty-client",
            "browser-capture-number-only-jetty-client",
            "browser-capture-sjis-jetty-client",
            "browser-capture-text-files-jetty-client",
            "browser-capture-unicode-names-jetty-client",
            "browser-capture-whitespace-only-jetty-client",

            // == Capture of raw request body contents from various browsers ==

            // simple form - 2 fields
            "browser-capture-form1-android-chrome",
            "browser-capture-form1-android-firefox",
            "browser-capture-form1-chrome",
            "browser-capture-form1-edge",
            "browser-capture-form1-firefox",
            "browser-capture-form1-ios-safari",
            "browser-capture-form1-msie",
            "browser-capture-form1-osx-safari",

            // form submitted as shift-jis
            "browser-capture-sjis-form-edge",
            "browser-capture-sjis-form-msie",
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
            "browser-capture-sjis-charset-form-edge",
            "browser-capture-sjis-charset-form-firefox", // contains html encoded character
            "browser-capture-sjis-charset-form-ios-safari", // contains html encoded character
            "browser-capture-sjis-charset-form-msie",
            "browser-capture-sjis-charset-form-safari", // contains html encoded character

            // form submitted with simple file upload
            "browser-capture-form-fileupload-android-chrome",
            "browser-capture-form-fileupload-android-firefox",
            "browser-capture-form-fileupload-chrome",
            "browser-capture-form-fileupload-edge",
            "browser-capture-form-fileupload-firefox",
            "browser-capture-form-fileupload-ios-safari",
            "browser-capture-form-fileupload-msie",
            "browser-capture-form-fileupload-safari",

            // form submitted with 2 files (1 binary, 1 text) and 2 text fields
            "browser-capture-form-fileupload-alt-chrome",
            "browser-capture-form-fileupload-alt-edge",
            "browser-capture-form-fileupload-alt-firefox",
            "browser-capture-form-fileupload-alt-msie",
            "browser-capture-form-fileupload-alt-safari"
        ).map(Arguments::of);
    }

    public WorkDir testingDir;

    @ParameterizedTest
    @MethodSource("data")
    public void testHttpParse(String rawPrefix) throws Exception
    {
        Path multipartRawFile = MavenTestingUtils.getTestResourcePathFile("multipart/" + rawPrefix + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + rawPrefix + ".expected.txt");
        MultipartExpectations multipartExpectations = new MultipartExpectations(expectationPath);

        Path outputDir = testingDir.getEmptyPathDir();
        MultipartConfigElement config = newMultipartConfigElement(outputDir);
        try (InputStream in = Files.newInputStream(multipartRawFile))
        {
            MultiPartFormInputStream parser = new MultiPartFormInputStream(in, multipartExpectations.contentType, config, outputDir.toFile());

            multipartExpectations.checkParts(parser.getParts(), s ->
            {
                try
                {
                    return parser.getPart(s);
                }
                catch (Exception e)
                {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private MultipartConfigElement newMultipartConfigElement(Path path)
    {
        return new MultipartConfigElement(path.toString(), MAX_FILE_SIZE, MAX_REQUEST_SIZE, FILE_SIZE_THRESHOLD);
    }

    public static class NameValue
    {
        public String name;
        public String value;
    }

    public static class MultipartExpectations
    {
        public final String contentType;
        public final int partCount;
        public final List<NameValue> partFilenames = new ArrayList<>();
        public final List<NameValue> partSha1sums = new ArrayList<>();
        public final List<NameValue> partContainsContents = new ArrayList<>();

        public MultipartExpectations(Path expectationsPath) throws IOException
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
                            NameValue pair = new NameValue();
                            pair.name = split[1];
                            pair.value = split[2];
                            partContainsContents.add(pair);
                            break;
                        }
                        case "Part-Filename":
                        {
                            NameValue pair = new NameValue();
                            pair.name = split[1];
                            pair.value = split[2];
                            partFilenames.add(pair);
                            break;
                        }
                        case "Part-Sha1sum":
                        {
                            NameValue pair = new NameValue();
                            pair.name = split[1];
                            pair.value = split[2];
                            partSha1sums.add(pair);
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

        private void checkParts(Collection<Part> parts, Function<String, Part> getPart) throws Exception
        {
            // Evaluate Count
            if (partCount >= 0)
            {
                assertThat("Mulitpart.parts.size", parts.size(), is(partCount));
            }

            String defaultCharset = UTF_8.toString();
            Part charSetPart = getPart.apply("_charset_");
            if (charSetPart != null)
            {
                defaultCharset = IO.toString(charSetPart.getInputStream());
            }

            // Evaluate expected Contents
            for (NameValue expected : partContainsContents)
            {
                Part part = getPart.apply(expected.name);
                assertThat("Part[" + expected.name + "]", part, is(notNullValue()));
                try (InputStream partInputStream = part.getInputStream())
                {
                    String charset = getCharsetFromContentType(part.getContentType(), defaultCharset);
                    String contents = IO.toString(partInputStream, charset);
                    assertThat("Part[" + expected.name + "].contents", contents, containsString(expected.value));
                }
            }

            // Evaluate expected filenames
            for (NameValue expected : partFilenames)
            {
                Part part = getPart.apply(expected.name);
                assertThat("Part[" + expected.name + "]", part, is(notNullValue()));
                assertThat("Part[" + expected.name + "]", part.getSubmittedFileName(), is(expected.value));
            }

            // Evaluate expected contents checksums
            for (NameValue expected : partSha1sums)
            {
                Part part = getPart.apply(expected.name);
                assertThat("Part[" + expected.name + "]", part, is(notNullValue()));
                MessageDigest digest = MessageDigest.getInstance("SHA1");
                try (InputStream partInputStream = part.getInputStream();
                     NoOpOutputStream noop = new NoOpOutputStream();
                     DigestOutputStream digester = new DigestOutputStream(noop, digest))
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
            {
                return defaultCharset;
            }

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

    static class NoOpOutputStream extends OutputStream
    {
        @Override
        public void write(byte[] b) throws IOException
        {
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException
        {
        }

        @Override
        public void flush() throws IOException
        {
        }

        @Override
        public void close() throws IOException
        {
        }

        @Override
        public void write(int b) throws IOException
        {
        }
    }
}
