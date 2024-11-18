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

package org.eclipse.jetty.compression.server;

import java.util.List;

import org.eclipse.jetty.http.QuotedQualityCSV;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;

public class CompressionConfigTest
{
    private static List<String> qcsv(String rawheadervalue)
    {
        QuotedQualityCSV csv = new QuotedQualityCSV();
        csv.addValue(rawheadervalue);
        return csv.getValues();
    }

    @ParameterizedTest
    @CsvSource(useHeadersInDisplayName = true, delimiterString = "|", textBlock = """
        PreferredEncoders | AcceptEncodings | ExpectedResult
                          | gzip, br        | gzip, br
                          | br, gzip        | br, gzip
        br, gzip          | gzip, br        | br, gzip
        zstd, br          | gzip, br, zstd  | zstd, br
        zstd, br, gzip    | *               | zstd, br, gzip
                          | *               |
        """)
    public void testCalcPreferredEncoders(String preferredEncoderOrderCsv, String acceptEncodingHeaderValuesCsv, String expectedEncodersCsv)
    {
        List<String> preferredEncoderOrder = qcsv(preferredEncoderOrderCsv);
        List<String> acceptEncodingHeaderValues = qcsv(acceptEncodingHeaderValuesCsv);
        List<String> expectedEncodersResult = qcsv(expectedEncodersCsv);

        CompressionConfig config = CompressionConfig.builder()
            .compressPreferredEncoderOrder(preferredEncoderOrder)
            .build();
        List<String> result = config.calcPreferredEncoders(acceptEncodingHeaderValues);
        if (expectedEncodersResult.isEmpty())
        {
            assertThat(result, hasSize(0));
        }
        else
        {
            String[] expected = expectedEncodersResult.toArray(new String[0]);
            assertThat(result, contains(expected));
        }
    }
}
