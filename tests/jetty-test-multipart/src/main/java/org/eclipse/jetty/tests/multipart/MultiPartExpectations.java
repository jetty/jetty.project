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

package org.eclipse.jetty.tests.multipart;

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
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalToIgnoringCase;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class MultiPartExpectations
{
    record NameValue(String name, String value) {}

    public final String contentType;
    public final int partCount;
    public final List<NameValue> partFilenames = new ArrayList<>();
    public final List<NameValue> partSha1Sums = new ArrayList<>();
    public final List<NameValue> partContainsContents = new ArrayList<>();
    public final List<NameValue> partContainsHex = new ArrayList<>();

    public static MultiPartExpectations from(BufferedReader reader) throws IOException
    {
        return new MultiPartExpectations(reader);
    }

    public static MultiPartExpectations from(Path expectationsPath) throws IOException
    {
        try (BufferedReader reader = Files.newBufferedReader(expectationsPath))
        {
            return new MultiPartExpectations(reader);
        }
    }

    private MultiPartExpectations(BufferedReader reader) throws IOException
    {
        String parsedContentType = null;
        String parsedPartCount = "-1";

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
                    throw new IOException("Bad Line: " + line);
            }
        }

        Objects.requireNonNull(parsedContentType, "Missing required 'Content-Type' declaration");
        this.contentType = parsedContentType;
        this.partCount = Integer.parseInt(parsedPartCount);
    }

    public boolean hasPartName(String name)
    {
        for (NameValue nameValue : partContainsContents)
        {
            if (nameValue.name.equals(name))
                return true;
        }
        return false;
    }

    public void assertParts(MultiPartResults multiPartResults, Charset defaultCharset) throws Exception
    {
        if (partCount >= 0)
            assertThat(multiPartResults.getCount(), is(partCount));

        Charset formCharset = defaultCharset != null ? defaultCharset : UTF_8;

        List<MultiPartResults.PartResult> charsetParts = multiPartResults.get("_charset_");
        if (charsetParts != null && !charsetParts.isEmpty())
        {
            String charset = charsetParts.get(0).asString(UTF_8);
            if (StringUtil.isNotBlank(charset))
                formCharset = Charset.forName(charset);
        }

        for (NameValue expected : partContainsContents)
        {
            List<MultiPartResults.PartResult> parts = multiPartResults.get(expected.name);
            assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
            assertThat("Part count for [" + expected.name + "]", parts.size(), is(1));
            MultiPartResults.PartResult part = parts.get(0);
            // Parse part with charset.
            Charset charset = getCharsetFromContentType(part.getContentType(), formCharset);
            ByteBuffer partBuffer = part.asByteBuffer();
            assertThat("part[" + expected.name + "].newContentSource", partBuffer, is(notNullValue()));
            String partBufferAsString = BufferUtil.toString(partBuffer, charset);
            assertThat("Part[" + expected.name + "].newContentSource > ByteBuffer > String", partBufferAsString, containsString(expected.value));
            String partContent = part.asString(charset);
            assertThat("Part[" + expected.name + "].asString", partContent, containsString(expected.value));
        }

        for (NameValue expected : partContainsHex)
        {
            List<MultiPartResults.PartResult> parts = multiPartResults.get(expected.name);
            assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
            MultiPartResults.PartResult part = parts.get(0);
            ByteBuffer partBuffer = part.asByteBuffer();
            String partAsHex = Hex.asHex(partBuffer.slice());
            assertThat("Part[" + expected.name + "].contents", partAsHex, containsString(expected.value));
        }

        // Evaluate expected filenames
        for (NameValue expected : partFilenames)
        {
            List<MultiPartResults.PartResult> parts = multiPartResults.get(expected.name);
            assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
            MultiPartResults.PartResult part = parts.get(0);
            assertThat("Part[" + expected.name + "]", part.getFileName(), is(expected.value));
        }

        // Evaluate expected contents checksums
        for (NameValue expected : partSha1Sums)
        {
            List<MultiPartResults.PartResult> parts = multiPartResults.get(expected.name);
            assertThat("Part[" + expected.name + "]", parts, is(notNullValue()));
            MultiPartResults.PartResult part = parts.get(0);
            MessageDigest digest = MessageDigest.getInstance("SHA1");

            try (InputStream partInputStream = part.asInputStream();
                 DigestOutputStream digester = new DigestOutputStream(OutputStream.nullOutputStream(), digest))
            {
                IO.copy(partInputStream, digester);
                String actualSha1sum = Hex.asHex(digest.digest()).toLowerCase(Locale.US);
                assertThat("Part[" + expected.name + "].sha1sum", actualSha1sum, equalToIgnoringCase(expected.value));
            }
        }
    }

    private Charset getCharsetFromContentType(String contentType, Charset defaultCharset)
    {
        if (StringUtil.isBlank(contentType))
            return defaultCharset;

        QuotedStringTokenizer tok = QuotedStringTokenizer.builder().delimiters(";").ignoreOptionalWhiteSpace().build();
        for (Iterator<String> i = tok.tokenize(contentType); i.hasNext(); )
        {
            String str = i.next().trim();
            if (str.startsWith("charset="))
            {
                return Charset.forName(str.substring("charset=".length()));
            }
        }

        return defaultCharset;
    }

    @Override
    public String toString()
    {
        return "expecting.multipart.count=" + partCount;
    }
}
