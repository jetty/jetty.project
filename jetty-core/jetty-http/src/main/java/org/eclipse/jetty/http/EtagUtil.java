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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

/**
 * Utility classes for Etag behaviors
 */
public class EtagUtil
{
    /**
     * Calculate the weak etag for the provided {@link Path} reference.
     *
     * <p>
     * This supports relative path references as well.
     * Which can be useful to establish a reliable etag if the base changes.
     * </p>
     *
     * @param path the path to calculate from
     * @return the weak etag
     */
    public static String calcWeakEtag(Path path)
    {
        return calcWeakEtag(path, "");
    }

    /**
     * Calculate the weak etag for the provided {@link Path} reference.
     *
     * @param path the path to calculate from
     * @param suffix the suffix for the ETag
     * @return the weak etag
     */
    public static String calcWeakEtag(Path path, String suffix)
    {
        StringBuilder b = new StringBuilder(32);
        b.append("W/\"");

        String name = path.toString();
        int length = name.length();
        long lhash = 0;
        for (int i = 0; i < length; i++)
        {
            lhash = 31 * lhash + name.charAt(i);
        }

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        long lastModifiedMillis = -1;
        long size = -1;
        try
        {
            lastModifiedMillis = Files.getLastModifiedTime(path).toMillis();
            size = Files.size(path);
        }
        catch (IOException ignored)
        {
            // TODO: should we ignore the inability to read last modified or size?
        }
        b.append(encoder.encodeToString(longToBytes(lastModifiedMillis ^ lhash)));
        b.append(encoder.encodeToString(longToBytes(size ^ lhash)));
        if (suffix != null)
            b.append(suffix);
        b.append('"');
        return b.toString();
    }

    /**
     * Rewrite etag with a new suffix, satisfying quoting rules, and preserving optional weak flag.
     *
     * @param etag the original etag
     * @param newSuffix the new suffix to add or change (if a preexisting suffix exists)
     * @return the new etag,
     */
    public static String rewriteWithSuffix(String etag, String newSuffix)
    {
        StringBuilder ret = new StringBuilder();
        boolean weak = etag.startsWith("W/");
        int start = 0;
        if (etag.startsWith("W/"))
        {
            weak = true;
            start = 2;
        }

        // ignore quotes
        while (etag.charAt(start) == '"')
        {
            start++;
        }
        int end = etag.length();
        while (etag.charAt(end - 1) == '"')
        {
            end--;
        }
        // find suffix (if present)
        int suffixIdx = etag.lastIndexOf('-', end);
        if (suffixIdx >= 0 && suffixIdx >= start)
            end = suffixIdx;

        // build new etag
        if (weak)
            ret.append("W/");
        ret.append('"');
        ret.append(etag, start, end);
        ret.append(newSuffix);
        ret.append('"');
        return ret.toString();
    }

    private static byte[] longToBytes(long value)
    {
        byte[] result = new byte[Long.BYTES];
        for (int i = Long.BYTES - 1; i >= 0; i--)
        {
            result[i] = (byte)(value & 0xFF);
            value >>= 8;
        }
        return result;
    }
}
