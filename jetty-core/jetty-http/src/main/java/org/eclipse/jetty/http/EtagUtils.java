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
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;

import org.eclipse.jetty.util.QuotedStringTokenizer;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.resource.Resource;

/**
 * Utility classes for Entity Tag behaviors as outlined in <a href="https://www.rfc-editor.org/rfc/rfc9110#name-etag">RFC9110 : Section 8.8.3 - Entity Tag</a>
 */
public final class EtagUtils
{
    private EtagUtils()
    {
        // prevent instantiation
    }

    /**
     * <p>The separator within an etag used to indicate a compressed variant.</p>
     *
     * <p>Default separator is {@code --}</p>
     *
     * <p>Adding a suffix of {@code gzip} to an etag of {@code W/"28c772d6"} will result in {@code W/"28c772d6--gzip"}</p>
     *
     * <p>The separator may be changed by the
     * {@code org.eclipse.jetty.http.EtagUtil.ETAG_SEPARATOR} System property. If changed, it should be changed to a string
     * that will not be found in a normal etag or at least is very unlikely to be a substring of a normal etag.</p>
     */
    public static final String ETAG_SEPARATOR = System.getProperty(EtagUtils.class.getName() + ".ETAG_SEPARATOR", "--");

    /**
     * Create a new {@link HttpField} {@link HttpHeader#ETAG} field suitable to represent the provided Resource.
     *
     * @param resource the resource to represent with an Etag.
     * @return the field if possible to create etag, or null if not possible.
     */
    public static HttpField createWeakEtagField(Resource resource)
    {
        return createWeakEtagField(resource, null);
    }

    /**
     * Create a new {@link HttpField} {@link HttpHeader#ETAG} field suitable to represent the provided Resource.
     *
     * @param resource the resource to represent with an Etag.
     * @param etagSuffix the suffix for the etag
     * @return the field if possible to create etag, or null if not possible.
     */
    public static HttpField createWeakEtagField(Resource resource, String etagSuffix)
    {
        Path path = resource.getPath();
        if (path == null)
            return null;

        String etagValue = EtagUtils.computeWeakEtag(path, etagSuffix);
        if (etagValue != null)
            return new PreEncodedHttpField(HttpHeader.ETAG, etagValue);
        return null;
    }

    /**
     * Compute the weak etag for the provided {@link Resource} reference.
     *
     * <p>
     * Will use the {@link Resource#getPath()} if the resource provides it, otherwise it will
     * use the {@link Resource} provided details from {@link Resource#getName()}, {@link Resource#lastModified()},
     * and {@link Resource#length()} to build the ETag.
     * </p>
     *
     * @param resource the resource to calculate etag from
     * @return the weak etag
     */
    public static String computeWeakEtag(Resource resource)
    {
        return computeWeakEtag(resource, null);
    }

    /**
     * Compute the weak etag for the provided {@link Resource} reference.
     *
     * <p>
     * Will use the {@link Resource#getPath()} if the resource provides it, otherwise it will
     * use the {@link Resource} provided details from {@link Resource#getName()}, {@link Resource#lastModified()},
     * and {@link Resource#length()} to build the ETag.
     * </p>
     *
     * @param resource the resource to calculate etag from
     * @param etagSuffix the suffix for the etag
     * @return the weak etag
     */
    public static String computeWeakEtag(Resource resource, String etagSuffix)
    {
        if (resource == null || !resource.exists() || resource.isDirectory())
            return null;

        Path path = resource.getPath();
        if (path != null)
        {
            // This is the most reliable technique.
            // ResourceCollection can return a different resource for each call name/lastModified/length
            // Using Path here ensures that if a Path is available, we can use it to get the name/lastModified/length
            // for same referenced Path object (something that ResourceCollection does not guarantee)
            return computeWeakEtag(path, etagSuffix);
        }
        else
        {
            // Use the URI / lastModified / size in case the Resource does not support Path
            // These fields must be returned by the implementation of Resource for the Resource to be valid
            String name = resource.getName();
            Instant lastModified = resource.lastModified();
            long size = resource.length();
            return computeWeakEtag(name, lastModified, size, etagSuffix);
        }
    }

    /**
     * Compute the weak etag for the provided {@link Path} reference.
     *
     * <p>
     * This supports relative path references as well.
     * Which can be useful to establish a reliable etag if the base changes.
     * </p>
     *
     * @param path the path to calculate from
     * @return the weak etag
     */
    public static String computeWeakEtag(Path path)
    {
        return computeWeakEtag(path, null);
    }

    /**
     * Compute the weak etag for the provided {@link Path} reference.
     *
     * @param path the path to calculate from
     * @param suffix the optional suffix for the ETag
     * @return the weak etag
     */
    public static String computeWeakEtag(Path path, String suffix)
    {
        if (path == null)
            return null;

        String name = path.toAbsolutePath().toString();
        Instant lastModified = Instant.EPOCH;
        try
        {
            lastModified = Files.getLastModifiedTime(path).toInstant();
        }
        catch (IOException ignored)
        {
        }
        long size = -1;
        try
        {
            size = Files.size(path);
        }
        catch (IOException ignored)
        {
        }
        return computeWeakEtag(name, lastModified, size, suffix);
    }

    /**
     * Main algorithm of how Jetty builds a unique Etag.
     *
     * @param name the name of the resource
     * @param lastModified the last modified time of the resource
     * @param size the size of the resource
     * @param etagSuffix the optional etag suffix
     * @return the calculated etag for the resource
     */
    private static String computeWeakEtag(String name, Instant lastModified, long size, String etagSuffix)
    {
        StringBuilder b = new StringBuilder(32);
        b.append("W/\"");

        int length = name.length();
        long lhash = 0;
        for (int i = 0; i < length; i++)
        {
            lhash = 31 * lhash + name.charAt(i);
        }

        Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
        long lastModifiedMillis = lastModified.toEpochMilli();
        b.append(encoder.encodeToString(longToBytes(lastModifiedMillis ^ lhash)));
        b.append(encoder.encodeToString(longToBytes(size ^ lhash)));
        if (etagSuffix != null)
            b.append(etagSuffix);
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

    /**
     * Test if the given Etag is considered weak.
     *
     * @param etag the etag to test
     * @return true if weak.
     */
    public static boolean isWeak(String etag)
    {
        return etag.startsWith("W/");
    }

    /**
     * Test if the given Etag is considered strong (not weak).
     *
     * @param etag the etag to test
     * @return true if strong (not weak).
     */
    public static boolean isStrong(String etag)
    {
        return !isWeak(etag);
    }

    /**
     * <p>Test if etag matches against an etagWithOptionalSuffix.</p>
     *
     * @param etag An etag without a compression suffix
     * @param etagWithOptionalSuffix An etag optionally with a compression suffix.
     * @return True if the tags are equal.
     * @see <a href="https://www.rfc-editor.org/rfc/rfc9110#section-8.8.3.2">RFC9110: Section 8.8.3.2 - Etag Comparison</a>.
     */
    public static boolean matches(String etag, String etagWithOptionalSuffix)
    {
        // Handle simple equality
        if (etag.equals(etagWithOptionalSuffix))
            return true;

        // If no separator defined, then simple equality is only possible positive
        if (StringUtil.isEmpty(ETAG_SEPARATOR))
            return false;

        // Are both tags quoted?
        boolean etagQuoted = etag.endsWith("\"");
        boolean etagSuffixQuoted = etagWithOptionalSuffix.endsWith("\"");

        // Look for a separator
        int separator = etagWithOptionalSuffix.lastIndexOf(ETAG_SEPARATOR);

        // If both tags are quoted the same (the norm) then any difference must be the suffix
        if (etagQuoted == etagSuffixQuoted)
            return separator > 0 && etag.regionMatches(0, etagWithOptionalSuffix, 0, separator);

        // If either tag is weak then we can't matches because weak tags must be quoted
        if (etagWithOptionalSuffix.startsWith("W/") || etag.startsWith("W/"))
            return false;

        // compare unquoted strong etags
        etag = etagQuoted ? QuotedStringTokenizer.unquote(etag) : etag;
        etagWithOptionalSuffix = etagSuffixQuoted ? QuotedStringTokenizer.unquote(etagWithOptionalSuffix) : etagWithOptionalSuffix;
        separator = etagWithOptionalSuffix.lastIndexOf(ETAG_SEPARATOR);
        if (separator > 0)
            return etag.regionMatches(0, etagWithOptionalSuffix, 0, separator);

        return Objects.equals(etag, etagWithOptionalSuffix);
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
