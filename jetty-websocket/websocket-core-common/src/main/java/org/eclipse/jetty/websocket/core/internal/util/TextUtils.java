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

package org.eclipse.jetty.websocket.core.internal.util;

/**
 * Collection of utility methods for Text content
 */
public final class TextUtils
{
    /**
     * Create a hint of what the text is like.
     * <p>
     * Used by logging and error messages to get a hint of what the text is like.
     *
     * @param text the text to abbreviate, quote, and generally give you a hint of what the value is.
     * @return the abbreviated text
     */
    public static String quote(String text)
    {
        if (text == null)
        {
            return "<null>";
        }
        return '"' + text + '"';
    }

    /**
     * Create a hint of what the text is like.
     * <p>
     * Used by logging and error messages to get a hint of what the text is like.
     *
     * @param text the text to abbreviate, quote, and generally give you a hint of what the value is.
     * @return the abbreviated text
     */
    public static String hint(String text)
    {
        if (text == null)
        {
            return "<null>";
        }
        return '"' + maxStringLength(30, text) + '"';
    }

    /**
     * Smash a long string to fit within the max string length, by taking the middle section of the string and replacing them with an ellipsis "..."
     *
     * <pre>
     * Examples:
     * .maxStringLength( 9, "Eatagramovabits") == "Eat...its"
     * .maxStringLength(10, "Eatagramovabits") == "Eat...bits"
     * .maxStringLength(11, "Eatagramovabits") == "Eata...bits"
     * </pre>
     *
     * @param max the maximum size of the string (minimum size supported is 9)
     * @param raw the raw string to smash
     * @return the ellipsis'd version of the string.
     */
    public static String maxStringLength(int max, String raw)
    {
        int length = raw.length();
        if (length <= max)
        {
            // already short enough
            return raw;
        }

        if (max < 9)
        {
            // minimum supported
            return raw.substring(0, max);
        }

        StringBuilder ret = new StringBuilder();
        int startLen = (int)Math.round((double)max / (double)3);
        ret.append(raw.substring(0, startLen));
        ret.append("...");
        ret.append(raw.substring(length - (max - startLen - 3)));

        return ret.toString();
    }
}
