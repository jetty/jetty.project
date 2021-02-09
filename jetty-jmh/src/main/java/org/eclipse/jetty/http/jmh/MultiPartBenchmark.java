//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.http.jmh;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.eclipse.jetty.http.MultiPartFormInputStream;
import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

@State(Scope.Benchmark)
@Threads(4)
@Warmup(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 7, time = 500, timeUnit = TimeUnit.MILLISECONDS)
public class MultiPartBenchmark
{
    public static final int MAX_FILE_SIZE = Integer.MAX_VALUE;
    public static final int MAX_REQUEST_SIZE = Integer.MAX_VALUE;
    public static final int FILE_SIZE_THRESHOLD = 50;

    public int count = 0;
    static String _contentType;
    static File _file;
    static int _numSections;
    static int _numBytesPerSection;

    public static List<String> data = new ArrayList<>();

    static
    {
        // Capture of raw request body contents from various browsers

        // simple form - 2 fields
        data.add("browser-capture-form1-android-chrome");
        data.add("browser-capture-form1-android-firefox");
        data.add("browser-capture-form1-chrome");
        data.add("browser-capture-form1-edge");
        data.add("browser-capture-form1-firefox");
        data.add("browser-capture-form1-ios-safari");
        data.add("browser-capture-form1-msie");
        data.add("browser-capture-form1-osx-safari");

        // form submitted as shift-jis
        data.add("browser-capture-sjis-form-edge");
        data.add("browser-capture-sjis-form-msie");

        // form submitted as shift-jis (with HTML5 specific hidden _charset_ field)
        data.add("browser-capture-sjis-charset-form-edge");
        data.add("browser-capture-sjis-charset-form-msie");

        // form submitted with simple file upload
        data.add("browser-capture-form-fileupload-android-chrome");
        data.add("browser-capture-form-fileupload-android-firefox");
        data.add("browser-capture-form-fileupload-chrome");
        data.add("browser-capture-form-fileupload-edge");
        data.add("browser-capture-form-fileupload-firefox");
        data.add("browser-capture-form-fileupload-ios-safari");
        data.add("browser-capture-form-fileupload-msie");
        data.add("browser-capture-form-fileupload-safari");

        // form submitted with 2 files (1 binary, 1 text) and 2 text fields
        data.add("browser-capture-form-fileupload-alt-chrome");
        data.add("browser-capture-form-fileupload-alt-edge");
        data.add("browser-capture-form-fileupload-alt-firefox");
        data.add("browser-capture-form-fileupload-alt-msie");
        data.add("browser-capture-form-fileupload-alt-safari");
    }

    @Param({"UTIL", "HTTP"})
    public static String parserType;

    @Setup(Level.Trial)
    public static void setupTrial() throws Exception
    {
        _file = File.createTempFile("test01", null);
        _file.deleteOnExit();

        _numSections = 1;
        _numBytesPerSection = 1024 * 1024 * 10;

        _contentType = "multipart/form-data, boundary=WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW";
        String initialBoundary = "--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n";
        String boundary = "\r\n--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW\r\n";
        String closingBoundary = "\r\n--WebKitFormBoundary7MA4YWf7OaKlSxkTrZu0gW--\r\n";
        String headerStart = "Content-Disposition: form-data; name=\"";

        for (int i = 0; i < _numSections; i++)
        {
            //boundary and headers
            if (i == 0)
                Files.write(_file.toPath(), initialBoundary.getBytes(), StandardOpenOption.APPEND);
            else
                Files.write(_file.toPath(), boundary.getBytes(), StandardOpenOption.APPEND);

            Files.write(_file.toPath(), headerStart.getBytes(), StandardOpenOption.APPEND);
            Files.write(_file.toPath(), ("part" + (i + 1)).getBytes(), StandardOpenOption.APPEND);
            Files.write(_file.toPath(), ("\"\r\n\r\n").getBytes(), StandardOpenOption.APPEND);

            //append random data
            byte[] data = new byte[_numBytesPerSection];
            new Random().nextBytes(data);
            Files.write(_file.toPath(), data, StandardOpenOption.APPEND);
        }

        //closing boundary
        Files.write(_file.toPath(), closingBoundary.getBytes(), StandardOpenOption.APPEND);
    }

    @Benchmark
    @BenchmarkMode({Mode.AverageTime})
    @SuppressWarnings("deprecation")
    public long testLargeGenerated() throws Exception
    {
        Path multipartRawFile = _file.toPath();
        Path outputDir = Files.createTempDirectory("jetty_multipart_benchmark");

        MultipartConfigElement config = newMultipartConfigElement(outputDir);

        try (InputStream in = Files.newInputStream(multipartRawFile))
        {
            switch (parserType)
            {
                case "HTTP":
                {
                    MultiPartFormInputStream parser = new MultiPartFormInputStream(in, _contentType, config, outputDir.toFile());
                    parser.setDeleteOnExit(true);
                    if (parser.getParts().size() != _numSections)
                        throw new IllegalStateException("Incorrect Parsing");
                    for (Part p : parser.getParts())
                    {
                        count += p.getSize();
                        if (p instanceof MultiPartFormInputStream.MultiPart)
                            ((MultiPartFormInputStream.MultiPart)p).cleanUp();
                        else
                            p.delete();
                    }
                    break;
                }

                case "UTIL":
                {
                    org.eclipse.jetty.util.MultiPartInputStreamParser parser = new org.eclipse.jetty.util.MultiPartInputStreamParser(in, _contentType, config, outputDir.toFile());
                    parser.setDeleteOnExit(true);
                    if (parser.getParts().size() != _numSections)
                        throw new IllegalStateException("Incorrect Parsing");
                    for (Part p : parser.getParts())
                    {
                        count += p.getSize();
                        if (p instanceof org.eclipse.jetty.util.MultiPartInputStreamParser.MultiPart)
                            ((org.eclipse.jetty.util.MultiPartInputStreamParser.MultiPart)p).cleanUp();
                        else
                            p.delete();
                    }
                    break;
                }

                default:
                    throw new IllegalStateException("Unknown parserType Parameter");
            }
        }
        IO.delete(outputDir.toFile());
        return count;
    }

    @TearDown(Level.Trial)
    public static void stopTrial() throws Exception
    {
        _file = null;
    }

    private MultipartConfigElement newMultipartConfigElement(Path path)
    {
        return new MultipartConfigElement(path.toString(), MAX_FILE_SIZE, MAX_REQUEST_SIZE, FILE_SIZE_THRESHOLD);
    }

    @Benchmark
    @BenchmarkMode({Mode.AverageTime})
    @SuppressWarnings("deprecation")
    public long testParser() throws Exception
    {
        for (String multiPart : data)
        {
            String expectationPath = "multipart/" + multiPart + ".expected.txt";

            File expectationFile = File.createTempFile(expectationPath, ".tmp");

            try (InputStream inputStream = Thread.currentThread().getContextClassLoader().getResourceAsStream(expectationPath);
                 //
                 OutputStream os = Files.newOutputStream(expectationFile.toPath()))
            {
                IO.copy(inputStream, os);
            }

            Path outputDir = Files.createTempDirectory("expected_output_jmh_jetty");

            MultipartExpectations multipartExpectations = new MultipartExpectations(expectationFile.toPath());
            MultipartConfigElement config = newMultipartConfigElement(outputDir);

            try (InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream("multipart/" + multiPart + ".raw"))
            {
                switch (parserType)
                {
                    case "HTTP":
                    {
                        MultiPartFormInputStream parser = new MultiPartFormInputStream(in, multipartExpectations.contentType, config, outputDir.toFile());
                        parser.setDeleteOnExit(true);
                        for (Part p : parser.getParts())
                        {
                            count += p.getSize();
                            if (p instanceof MultiPartFormInputStream.MultiPart)
                                ((MultiPartFormInputStream.MultiPart)p).cleanUp();
                            else
                                p.delete();
                        }
                        break;
                    }
                    case "UTIL":
                    {
                        org.eclipse.jetty.util.MultiPartInputStreamParser parser = new org.eclipse.jetty.util.MultiPartInputStreamParser(in, multipartExpectations.contentType, config, outputDir.toFile());
                        parser.setDeleteOnExit(true);
                        for (Part p : parser.getParts())
                        {
                            count += p.getSize();
                            if (p instanceof org.eclipse.jetty.util.MultiPartInputStreamParser.MultiPart)
                                ((org.eclipse.jetty.util.MultiPartInputStreamParser.MultiPart)p).cleanUp();
                            else
                                p.delete();
                        }
                        break;
                    }
                    default:
                        throw new IllegalStateException("Unknown parserType Parameter");
                }
            }
            Files.deleteIfExists(expectationFile.toPath());
            IO.delete(outputDir.toFile());
        }
        return count;
    }

    public static void main(String[] args) throws RunnerException
    {
        Options opt = new OptionsBuilder()
            .include(MultiPartBenchmark.class.getSimpleName())
            .warmupIterations(20)
            .measurementIterations(10)
            .forks(1)
            .threads(1)
            .build();

        new Runner(opt).run();
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
                defaultCharset = org.eclipse.jetty.util.IO.toString(charSetPart.getInputStream());
            }

            // Evaluate expected Contents
            for (NameValue expected : partContainsContents)
            {
                Part part = getPart.apply(expected.name);
                assertThat("Part[" + expected.name + "]", part, is(notNullValue()));
                try (InputStream partInputStream = part.getInputStream())
                {
                    String charset = getCharsetFromContentType(part.getContentType(), defaultCharset);
                    String contents = org.eclipse.jetty.util.IO.toString(partInputStream, charset);
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
                    org.eclipse.jetty.util.IO.copy(partInputStream, digester);
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

    public static class NameValue
    {
        public String name;
        public String value;
    }
}


