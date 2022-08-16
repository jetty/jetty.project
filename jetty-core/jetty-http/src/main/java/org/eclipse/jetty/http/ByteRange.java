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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>A representation of a byte range as specified by
 * <a href="https://datatracker.ietf.org/doc/html/rfc7233">RFC 7233</a>.</p>
 * <p>This class parses the value of the {@code Range} request header value,
 * for example:</p>
 * <pre>{@code
 * Range: bytes=100-499
 * Range: bytes=1-10,5-25,50-,-20
 * }</pre>
 */
public record ByteRange(long first, long last)
{
    private static final Logger LOG = LoggerFactory.getLogger(ByteRange.class);

    private ByteRange coalesce(ByteRange r)
    {
        return new ByteRange(Math.min(first, r.first), Math.max(last, r.last));
    }

    private boolean overlaps(ByteRange range)
    {
        return
            // Partial right overlap: 10-20,15-30.
            (range.first >= this.first && range.first <= this.last) ||
            // Partial left overlap: 20-30,15-25.
            (range.last >= this.first && range.last <= this.last) ||
            // Full inclusion: 20-30,10-40.
            (range.first < this.first && range.last > this.last);
    }

    /**
     * @return the length of this byte range
     */
    public long getLength()
    {
        return last - first + 1;
    }

    /**
     * <p>Returns the value for the {@code Content-Range}
     * response header corresponding to this byte range.</p>
     *
     * @param length the content length
     * @return the value for the {@code Content-Range} response header for this byte range
     */
    public String toHeaderValue(long length)
    {
        return "bytes %d-%d/%d".formatted(first, last, length);
    }

    /**
     * <p>Parses the {@code Range} header values such as {@code byte=10-20}
     * to obtain a list of {@code ByteRange}s.</p>
     * <p>Returns an empty list if the parsing fails.</p>
     *
     * @param headers a list of range values
     * @param length the length of the resource for which ranges are requested
     * @return a list of {@code ByteRange}s
     */
    public static List<ByteRange> parse(List<String> headers, long length)
    {
        long end = length - 1;
        List<ByteRange> ranges = null;
        String prefix = "bytes=";
        for (String header : headers)
        {
            try
            {
                String value = header.trim();
                if (!value.startsWith(prefix))
                    continue;
                value = value.substring(prefix.length());
                for (String range : value.split(","))
                {
                    range = range.trim();
                    long first = -1;
                    long last = -1;
                    int dash = range.indexOf('-');
                    if (dash < 0 || range.indexOf("-", dash + 1) >= 0)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("bad range format: {}", range);
                        break;
                    }

                    if (dash > 0)
                        first = Long.parseLong(range.substring(0, dash).trim());
                    if (dash < (range.length() - 1))
                        last = Long.parseLong(range.substring(dash + 1).trim());

                    if (first == -1)
                    {
                        if (last == -1)
                        {
                            if (LOG.isDebugEnabled())
                                LOG.debug("bad range format: {}", range);
                            break;
                        }

                        if (last == 0)
                            continue;

                        // This is a suffix range of the form "-20".
                        first = Math.max(0, end - last + 1);
                        last = end;
                    }
                    else
                    {
                        // Range starts after end.
                        if (first > end)
                            continue;

                        if (last == -1 || last > end)
                            last = end;
                    }

                    if (last < first)
                    {
                        if (LOG.isDebugEnabled())
                            LOG.debug("bad range format: {}", range);
                        break;
                    }

                    ByteRange byteRange = new ByteRange(first, last);
                    if (ranges == null)
                        ranges = new ArrayList<>();
                    ranges.add(byteRange);
                }
            }
            catch (Throwable x)
            {
                if (LOG.isDebugEnabled())
                    LOG.debug("could not parse range {}", header, x);
                return List.of();
            }
        }

        if (ranges == null)
            return List.of();
        if (ranges.size() == 1)
            return ranges;

        // Sort and coalesce in one pass through the list.
        List<ByteRange> result = new ArrayList<>();
        ranges.sort(Comparator.comparingLong(ByteRange::first));
        ByteRange range1 = ranges.get(0);
        for (int i = 1; i < ranges.size(); ++i)
        {
            ByteRange range2 = ranges.get(i);
            if (range1.overlaps(range2))
            {
                range1 = range1.coalesce(range2);
            }
            else
            {
                result.add(range1);
                range1 = range2;
            }
        }
        result.add(range1);
        return result;
    }

    /**
     * <p>Returns the value for the {@code Content-Range} response header
     * when the range is non satisfiable and therefore the response status
     * code is {@link HttpStatus#RANGE_NOT_SATISFIABLE_416}.</p>
     *
     * @param length the content length in bytes
     * @return the non satisfiable value for the {@code Content-Range} response header
     */
    public static String toNonSatisfiableHeaderValue(long length)
    {
        return "bytes */" + length;
    }
}
