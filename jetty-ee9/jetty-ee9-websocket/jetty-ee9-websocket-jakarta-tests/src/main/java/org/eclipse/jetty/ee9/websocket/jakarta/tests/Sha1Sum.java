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

package org.eclipse.jetty.ee9.websocket.jakarta.tests;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jetty.toolchain.test.Hex;
import org.eclipse.jetty.toolchain.test.IO;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Calculate the sha1sum for various content
 */
public class Sha1Sum
{
    private static class NoOpOutputStream extends OutputStream
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
        public void write(int b) throws IOException
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
    }

    public static String calculate(File file) throws NoSuchAlgorithmException, IOException
    {
        return calculate(file.toPath());
    }

    public static String calculate(Path path) throws NoSuchAlgorithmException, IOException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        try (InputStream in = Files.newInputStream(path, StandardOpenOption.READ);
             NoOpOutputStream noop = new NoOpOutputStream();
             DigestOutputStream digester = new DigestOutputStream(noop, digest))
        {
            IO.copy(in, digester);
            return Hex.asHex(digest.digest());
        }
    }

    public static String calculate(byte[] buf) throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        digest.update(buf);
        return Hex.asHex(digest.digest());
    }

    public static String calculate(byte[] buf, int offset, int len) throws NoSuchAlgorithmException
    {
        MessageDigest digest = MessageDigest.getInstance("SHA1");
        digest.update(buf, offset, len);
        return Hex.asHex(digest.digest());
    }

    public static String loadSha1(File sha1File) throws IOException
    {
        String contents = IO.readToString(sha1File);
        Pattern pat = Pattern.compile("^[0-9A-Fa-f]*");
        Matcher mat = pat.matcher(contents);
        assertTrue(mat.find(), "Should have found HEX code in SHA1 file: " + sha1File);
        return mat.group();
    }
}
