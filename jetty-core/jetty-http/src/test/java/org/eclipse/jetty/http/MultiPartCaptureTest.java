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

package org.eclipse.jetty.http;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.eclipse.jetty.io.Content;
import org.eclipse.jetty.io.content.ByteBufferContentSource;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.MavenPaths;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Assertions;
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
    public static Stream<Arguments> forms()
    {
        return Stream.of(
            // == Arbitrary / Non-Standard Examples ==

            "multipart-uppercase",
            "multipart-base64",  // base64 transfer encoding deprecated
            "multipart-base64-long", // base64 transfer encoding deprecated

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

    @ParameterizedTest
    @MethodSource("forms")
    public void testMultipartCapture(String fileName) throws Exception
    {
        Path rawPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".expected.txt");
        MultiPartExpectations expectations = new MultiPartExpectations(expectationPath);

        String boundaryAttribute = "boundary=";
        int boundaryIndex = expectations.contentType.indexOf(boundaryAttribute);
        assertThat(boundaryIndex, greaterThan(0));
        String boundary = HttpField.PARAMETER_TOKENIZER.unquote(expectations.contentType.substring(boundaryIndex + boundaryAttribute.length()));

        TestPartsListener listener = new TestPartsListener(expectations);
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        parser.parse(Content.Chunk.from(ByteBuffer.wrap(Files.readAllBytes(rawPath)), true));
        listener.assertParts();
    }

    @ParameterizedTest
    @MethodSource("forms")
    public void testMultiPartFormDataParse(String fileName) throws Exception
    {
        Path rawPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".expected.txt");
        MultiPartExpectations expectations = new MultiPartExpectations(expectationPath);

        String boundaryAttribute = "boundary=";
        int boundaryIndex = expectations.contentType.indexOf(boundaryAttribute);
        assertThat(boundaryIndex, greaterThan(0));
        String boundary = HttpField.PARAMETER_TOKENIZER.unquote(expectations.contentType.substring(boundaryIndex + boundaryAttribute.length()));

        Path tempDir = MavenPaths.targetTestDir(MultiPartCaptureTest.class.getSimpleName() + "-temp");
        FS.ensureDirExists(tempDir);

        MultiPartFormData.Parser parser = new MultiPartFormData.Parser(boundary);
        parser.setUseFilesForPartsWithoutFileName(false);
        parser.setFilesDirectory(tempDir);
        ByteBufferContentSource contentSource = new ByteBufferContentSource(ByteBuffer.wrap(Files.readAllBytes(rawPath)));
        MultiPartFormData.Parts parts = parser.parse(contentSource).get();
        expectations.assertParts(parts, parser.getDefaultCharset());
    }

    /**
     * Forms that were submitted without {@code _charset_} named part (as specified by the HTML5 spec).
     * <p>
     *     This is a flaky and buggy part of the HTML spec in various browsers.
     *     This technique used to be common, but is being replaced by using
     *     the {@code _charset_} named part instead.
     * </p>
     */
    public static Stream<Arguments> formsAltCharset()
    {
        return Stream.of(
            // form parts submitted as UTF-8
            Arguments.of("browser-capture-sjis-form-edge", "UTF-8"),
            Arguments.of("browser-capture-sjis-form-msie", "UTF-8"),
            Arguments.of("browser-capture-sjis-jetty-client", "UTF-8"),
            // form parts submitted at Shift_JIS (also contains html encoded character entities)
            Arguments.of("browser-capture-sjis-form-android-chrome", "Shift_JIS"),
            Arguments.of("browser-capture-sjis-form-android-firefox", "Shift_JIS"),
            Arguments.of("browser-capture-sjis-form-chrome", "Shift_JIS"),
            Arguments.of("browser-capture-sjis-form-firefox", "Shift_JIS"),
            Arguments.of("browser-capture-sjis-form-ios-safari", "Shift_JIS"),
            Arguments.of("browser-capture-sjis-form-safari", "Shift_JIS")
        );
    }

    @ParameterizedTest
    @MethodSource("formsAltCharset")
    public void testMultipartCaptureAltCharset(String fileName, String altCharset) throws Exception
    {
        Path rawPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".expected.txt");
        Charset charset = Charset.forName(altCharset);
        MultiPartExpectations expectations = new MultiPartExpectations(expectationPath);

        String boundaryAttribute = "boundary=";
        int boundaryIndex = expectations.contentType.indexOf(boundaryAttribute);
        assertThat(boundaryIndex, greaterThan(0));
        String boundary = HttpField.PARAMETER_TOKENIZER.unquote(expectations.contentType.substring(boundaryIndex + boundaryAttribute.length()));

        TestPartsListener listener = new TestPartsListener(expectations);
        MultiPart.Parser parser = new MultiPart.Parser(boundary, listener);
        parser.parse(Content.Chunk.from(ByteBuffer.wrap(Files.readAllBytes(rawPath)), true));
        listener.setDefaultCharset(charset);
        listener.assertParts();
    }

    @ParameterizedTest
    @MethodSource("formsAltCharset")
    public void testMultiPartFormDataParseAltCharset(String fileName, String altCharset) throws Exception
    {
        Path rawPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + fileName + ".expected.txt");
        Charset charset = Charset.forName(altCharset);
        MultiPartExpectations expectations = new MultiPartExpectations(expectationPath);

        String boundaryAttribute = "boundary=";
        int boundaryIndex = expectations.contentType.indexOf(boundaryAttribute);
        assertThat(boundaryIndex, greaterThan(0));
        String boundary = HttpField.PARAMETER_TOKENIZER.unquote(expectations.contentType.substring(boundaryIndex + boundaryAttribute.length()));

        Path tempDir = MavenPaths.targetTestDir(MultiPartCaptureTest.class.getSimpleName() + "-temp");
        FS.ensureDirExists(tempDir);

        MultiPartFormData.Parser parser = new MultiPartFormData.Parser(boundary);
        parser.setUseFilesForPartsWithoutFileName(false);
        parser.setFilesDirectory(tempDir);
        parser.setDefaultCharset(charset);
        ByteBufferContentSource contentSource = new ByteBufferContentSource(ByteBuffer.wrap(Files.readAllBytes(rawPath)));
        MultiPartFormData.Parts parts = parser.parse(contentSource).get();
        expectations.assertParts(parts, parser.getDefaultCharset());
    }

    private record NameValue(String name, String value)
    {
    }

    private static class MultiPartExpectations
    {
        public final String testfilename;
        public final String contentType;
        public final int partCount;
        public final List<NameValue> partFilenames = new ArrayList<>();
        public final List<NameValue> partSha1Sums = new ArrayList<>();
        public final List<NameValue> partContainsContents = new ArrayList<>();
        public final List<NameValue> partContainsHex = new ArrayList<>();

        public MultiPartExpectations(Path expectationsPath) throws IOException
        {
            String filename = expectationsPath.getFileName().toString();
            testfilename = filename.replaceFirst(".expected.txt$", "");
            String parsedContentType = null;
            String parsedPartCount = "-1";

            try (BufferedReader reader = Files.newBufferedReader(expectationsPath, UTF_8))
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
                        case "Part-ContainsHex":
                        {
                            NameValue pair = new NameValue(split[1], split[2]);
                            partContainsHex.add(pair);
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

        public boolean hasPartName(String name)
        {
            for (NameValue nameValue: partContainsContents)
            {
                if (nameValue.name.equals(name))
                    return true;
            }
            return false;
        }

        private void assertParts(Map<String, List<MultiPart.Part>> allParts, Charset formCharset) throws Exception
        {
            assertParts(() -> allParts.values().stream().mapToInt(List::size).sum(),
                (name) -> allParts.get(name),
                formCharset);
        }

        private void assertParts(MultiPartFormData.Parts parts, Charset formCharset) throws Exception
        {
            assertParts(() -> parts.size(),
                (name) -> parts.getAll(name),
                formCharset);
        }

        private void assertParts(Supplier<Integer> partCountSupplier,
                                 Function<String, List<MultiPart.Part>> namedPartsFunction,
                                 Charset formCharset) throws Exception
        {
            if (partCount >= 0)
                assertThat(partCountSupplier.get(), is(partCount));

            Charset defaultCharset = UTF_8;
            if (formCharset != null)
                defaultCharset = formCharset;

            for (NameValue expected : partContainsContents)
            {
                List<MultiPart.Part> parts = namedPartsFunction.apply(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                assertThat("Part count for [" + expected.name + "]", parts.size(), is(1));
                MultiPart.Part part = parts.get(0);
                // Parse part with charset.
                Charset charset = getCharsetFromContentType(part.getHeaders().get(HttpHeader.CONTENT_TYPE), defaultCharset);
                ByteBuffer partBuffer = Content.Source.asByteBuffer(part.newContentSource());
                assertThat("part[" + expected.name + "].newContentSource", partBuffer, is(notNullValue()));
                String partBufferAsString = BufferUtil.toString(partBuffer, charset);
                assertThat("Part[" + expected.name + "].contents", partBufferAsString, containsString(expected.value));
                String partContent = Content.Source.asString(part.newContentSource(), charset);
                assertThat("Part[" + expected.name + "].contents", partContent, containsString(expected.value));
            }

            for (NameValue expected : partContainsHex)
            {
                List<MultiPart.Part> parts = namedPartsFunction.apply(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                // Parse part with charset.
                Charset charset = getCharsetFromContentType(part.getHeaders().get(HttpHeader.CONTENT_TYPE), defaultCharset);
                ByteBuffer partBuffer = Content.Source.asByteBuffer(part.newContentSource());
                String partAsHex = Hex.asHex(partBuffer.slice());
                assertThat("Part[" + expected.name + "].contents", partAsHex, containsString(expected.value));
            }

            // Evaluate expected filenames
            for (NameValue expected : partFilenames)
            {
                List<MultiPart.Part> parts = namedPartsFunction.apply(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                assertThat("Part[" + expected.name + "]", part.getFileName(), is(expected.value));
            }

            Path debugPath = MavenPaths.targetTestDir("multipart-debug");
            FS.ensureDirExists(debugPath);

            // Evaluate expected contents checksums
            for (NameValue expected : partSha1Sums)
            {
                List<MultiPart.Part> parts = namedPartsFunction.apply(expected.name);
                assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
                MultiPart.Part part = parts.get(0);
                MessageDigest digest = MessageDigest.getInstance("SHA1");

                Path debugFile = debugPath.resolve(testfilename + ".part." + expected.name + ".debug");

                try (InputStream partInputStream = Content.Source.asInputStream(part.newContentSource());
                     OutputStream debugOutput = Files.newOutputStream(debugFile);
                     DigestOutputStream digester = new DigestOutputStream(debugOutput, digest))
                {
                    IO.copy(partInputStream, digester);
                    String actualSha1sum = Hex.asHex(digest.digest()).toLowerCase(Locale.US);
                    assertThat("Part[" + expected.name + "].sha1sum", actualSha1sum, Matchers.equalToIgnoringCase(expected.value));
                }
            }
        }

        private Charset getCharsetFromContentType(String contentType, Charset defaultCharset)
        {
            if (StringUtil.isBlank(contentType))
                return defaultCharset;

            QuotedStringTokenizer tok = QuotedStringTokenizer.builder().delimiters(";").ignoreOptionalWhiteSpace().build();
            for (Iterator<String> i = tok.tokenize(contentType); i.hasNext();)
            {
                String str = i.next().trim();
                if (str.startsWith("charset="))
                {
                    return Charset.forName(str.substring("charset=".length()));
                }
            }

            return defaultCharset;
        }
    }

    private static class TestPartsListener extends MultiPart.AbstractPartsListener
    {
        // Preserve parts order.
        private Charset defaultCharset = UTF_8;
        private Charset formCharset;
        private final Map<String, List<MultiPart.Part>> parts = new LinkedHashMap<>();
        private final List<ByteBuffer> partByteBuffers = new ArrayList<>();
        private final MultiPartExpectations expectations;

        private TestPartsListener(MultiPartExpectations expectations)
        {
            this.expectations = expectations;
        }

        @Override
        public void onPartContent(Content.Chunk chunk)
        {
            // Copy the part content, as we need to iterate over it multiple times.
            partByteBuffers.add(BufferUtil.copy(chunk.getByteBuffer()));
        }

        @Override
        public void onPart(String name, String fileName, HttpFields headers)
        {
            List<ByteBuffer> copyOfByteBuffers = new ArrayList<>();
            for (ByteBuffer capture: partByteBuffers)
            {
                copyOfByteBuffers.add(BufferUtil.copy(capture));
            }
            MultiPart.Part newPart = new MultiPart.ByteBufferPart(name, fileName, headers, copyOfByteBuffers);
            partByteBuffers.clear();
            parts.compute(newPart.getName(), (k, v) -> v == null ? new ArrayList<>() : v).add(newPart);
        }

        public void setDefaultCharset(Charset charset)
        {
            this.defaultCharset = charset;
        }

        public Charset getFormCharset()
        {
            List<MultiPart.Part> formCharset = parts.get("_charset_");
            if (formCharset == null || formCharset.isEmpty())
            {
                if (expectations.hasPartName("_charset_"))
                    Assertions.fail("Unexpected form parse: expecting _charset_, but part not found");
                else
                    return defaultCharset;
            }
            return Charset.forName(formCharset.get(0).getContentAsString(UTF_8));
        }

        private void assertParts() throws Exception
        {
            expectations.assertParts(parts, getFormCharset());
        }
    }
}
