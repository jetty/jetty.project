//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.Assert.assertThat;

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

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.Part;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.TestingDir;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.hamcrest.Matchers;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MultiPartCaptureTest
{

    public static final int MAX_FILE_SIZE = 2 * 1024 * 1024;
    public static final int MAX_REQUEST_SIZE = MAX_FILE_SIZE + (60 * 1024);
    public static final int FILE_SIZE_THRESHOLD = 50;

    @Parameterized.Parameters(name = "{0}")
    public static List<Object[]> data()
    {
        List<Object[]> ret = new ArrayList<>();

        // == Arbitrary / Non-Standard Examples ==

        ret.add(new String[]{"multipart-uppercase"});
        // ret.add(new String[]{"multipart-base64"});  // base64 transfer encoding deprecated
        // ret.add(new String[]{"multipart-base64-long"}); // base64 transfer encoding deprecated

        // == Capture of raw request body contents from Apache HttpClient 4.5.5 ==

        ret.add(new String[]{"browser-capture-company-urlencoded-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-complex-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-duplicate-names-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-encoding-mess-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-nested-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-nested-binary-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-number-only2-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-number-only-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-sjis-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-strange-quoting-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-text-files-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-unicode-names-apache-httpcomp"});
        ret.add(new String[]{"browser-capture-zalgo-text-plain-apache-httpcomp"});

        // == Capture of raw request body contents from Eclipse Jetty Http Client 9.4.9 ==

        ret.add(new String[]{"browser-capture-complex-jetty-client"});
        ret.add(new String[]{"browser-capture-duplicate-names-jetty-client"});
        ret.add(new String[]{"browser-capture-encoding-mess-jetty-client"});
        ret.add(new String[]{"browser-capture-nested-jetty-client"});
        ret.add(new String[]{"browser-capture-number-only-jetty-client"});
        ret.add(new String[]{"browser-capture-sjis-jetty-client"});
        ret.add(new String[]{"browser-capture-text-files-jetty-client"});
        ret.add(new String[]{"browser-capture-unicode-names-jetty-client"});
        ret.add(new String[]{"browser-capture-whitespace-only-jetty-client"});

        // == Capture of raw request body contents from various browsers ==

        // simple form - 2 fields
        ret.add(new String[]{"browser-capture-form1-android-chrome"});
        ret.add(new String[]{"browser-capture-form1-android-firefox"});
        ret.add(new String[]{"browser-capture-form1-chrome"});
        ret.add(new String[]{"browser-capture-form1-edge"});
        ret.add(new String[]{"browser-capture-form1-firefox"});
        ret.add(new String[]{"browser-capture-form1-ios-safari"});
        ret.add(new String[]{"browser-capture-form1-msie"});
        ret.add(new String[]{"browser-capture-form1-osx-safari"});

        // form submitted as shift-jis
        ret.add(new String[]{"browser-capture-sjis-form-edge"});
        ret.add(new String[]{"browser-capture-sjis-form-msie"});
        // TODO: these might be addressable via Issue #2398
        // ret.add(new String[]{"browser-capture-sjis-form-android-chrome"}); // contains html encoded character and unspecified charset defaults to utf-8
        // ret.add(new String[]{"browser-capture-sjis-form-android-firefox"}); // contains html encoded character and unspecified charset defaults to utf-8
        // ret.add(new String[]{"browser-capture-sjis-form-chrome"}); // contains html encoded character and unspecified charset defaults to utf-8
        // ret.add(new String[]{"browser-capture-sjis-form-firefox"}); // contains html encoded character and unspecified charset defaults to utf-8
        // ret.add(new String[]{"browser-capture-sjis-form-ios-safari"}); // contains html encoded character and unspecified charset defaults to utf-8
        // ret.add(new String[]{"browser-capture-sjis-form-safari"}); // contains html encoded character and unspecified charset defaults to utf-8

        // form submitted as shift-jis (with HTML5 specific hidden _charset_ field)
        ret.add(new String[]{"browser-capture-sjis-charset-form-android-chrome"}); // contains html encoded character
        ret.add(new String[]{"browser-capture-sjis-charset-form-android-firefox"}); // contains html encoded character
        ret.add(new String[]{"browser-capture-sjis-charset-form-chrome"}); // contains html encoded character
        ret.add(new String[]{"browser-capture-sjis-charset-form-edge"});
        ret.add(new String[]{"browser-capture-sjis-charset-form-firefox"}); // contains html encoded character
        ret.add(new String[]{"browser-capture-sjis-charset-form-ios-safari"}); // contains html encoded character
        ret.add(new String[]{"browser-capture-sjis-charset-form-msie"});
        ret.add(new String[]{"browser-capture-sjis-charset-form-safari"}); // contains html encoded character

        // form submitted with simple file upload
        ret.add(new String[]{"browser-capture-form-fileupload-android-chrome"});
        ret.add(new String[]{"browser-capture-form-fileupload-android-firefox"});
        ret.add(new String[]{"browser-capture-form-fileupload-chrome"});
        ret.add(new String[]{"browser-capture-form-fileupload-edge"});
        ret.add(new String[]{"browser-capture-form-fileupload-firefox"});
        ret.add(new String[]{"browser-capture-form-fileupload-ios-safari"});
        ret.add(new String[]{"browser-capture-form-fileupload-msie"});
        ret.add(new String[]{"browser-capture-form-fileupload-safari"});

        // form submitted with 2 files (1 binary, 1 text) and 2 text fields
        ret.add(new String[]{"browser-capture-form-fileupload-alt-chrome"});
        ret.add(new String[]{"browser-capture-form-fileupload-alt-edge"});
        ret.add(new String[]{"browser-capture-form-fileupload-alt-firefox"});
        ret.add(new String[]{"browser-capture-form-fileupload-alt-msie"});
        ret.add(new String[]{"browser-capture-form-fileupload-alt-safari"});

        return ret;
    }

    @Rule
    public TestingDir testingDir = new TestingDir();

    private final Path multipartRawFile;
    private final MultipartExpectations multipartExpectations;

    public MultiPartCaptureTest(String rawPrefix) throws IOException
    {
        multipartRawFile = MavenTestingUtils.getTestResourcePathFile("multipart/" + rawPrefix + ".raw");
        Path expectationPath = MavenTestingUtils.getTestResourcePathFile("multipart/" + rawPrefix + ".expected.txt");
        multipartExpectations = new MultipartExpectations(expectationPath);
    }

    @Test
    public void testUtilParse() throws Exception
    {
        Path outputDir = testingDir.getEmptyPathDir();
        MultipartConfigElement config = newMultipartConfigElement(outputDir);
        try (InputStream in = Files.newInputStream(multipartRawFile))
        {
            org.eclipse.jetty.util.MultiPartInputStreamParser parser = new org.eclipse.jetty.util.MultiPartInputStreamParser(in,multipartExpectations.contentType,config,outputDir.toFile());

            checkParts(parser.getParts(),s-> 
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

    @Test
    public void testHttpParse() throws Exception
    {
        Path outputDir = testingDir.getEmptyPathDir();
        MultipartConfigElement config = newMultipartConfigElement(outputDir);
        try (InputStream in = Files.newInputStream(multipartRawFile))
        {
            MultiPartFormInputStream parser = new MultiPartFormInputStream(in, multipartExpectations.contentType, config, outputDir.toFile());

            checkParts(parser.getParts(),s-> 
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
    
    private void checkParts(Collection<Part> parts, Function<String, Part> getPart) throws Exception
    {
        // Evaluate Count
        if (multipartExpectations.partCount >= 0)
        {
            assertThat("Mulitpart.parts.size", parts.size(), is(multipartExpectations.partCount));
        }

        String defaultCharset = UTF_8.toString();
        Part charSetPart = getPart.apply("_charset_");
        if(charSetPart != null)
        {
            defaultCharset = IO.toString(charSetPart.getInputStream());
        }
        
        
        // Evaluate expected Contents
        for (NameValue expected : multipartExpectations.partContainsContents)
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
        for (NameValue expected : multipartExpectations.partFilenames)
        {
            Part part = getPart.apply(expected.name);
            assertThat("Part[" + expected.name + "]", part, is(notNullValue()));
            assertThat("Part[" + expected.name + "]", part.getSubmittedFileName(), is(expected.value));
        }

        // Evaluate expected contents checksums
        for (NameValue expected : multipartExpectations.partSha1sums)
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

    private MultipartConfigElement newMultipartConfigElement(Path path)
    {
        return new MultipartConfigElement(path.toString(), MAX_FILE_SIZE, MAX_REQUEST_SIZE, FILE_SIZE_THRESHOLD);
    }

    private String getCharsetFromContentType(String contentType, String defaultCharset)
    {
        if(StringUtil.isBlank(contentType))
        {
            return defaultCharset;
        }

        QuotedStringTokenizer tok = new QuotedStringTokenizer(contentType, ";", false, false);
        while(tok.hasMoreTokens())
        {
            String str = tok.nextToken().trim();
            if(str.startsWith("charset="))
            {
                return str.substring("charset=".length());
            }
        }

        return defaultCharset;
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

                    String split[] = line.split("\\|");
                    switch (split[0])
                    {
                        case "Request-Header":
                            if(split[1].equalsIgnoreCase("Content-Type"))
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
    }

    class NoOpOutputStream extends OutputStream
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
