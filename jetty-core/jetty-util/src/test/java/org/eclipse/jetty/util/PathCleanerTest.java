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

package org.eclipse.jetty.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PathCleanerTest
{
    private static PathCleaner cleaner(String mode)
    {
        PathCleaner cleaner = new PathCleaner();
        cleaner.setMode(mode);
        return cleaner;
    }

    @Test
    public void testEarlyDeletesRegularFile(@TempDir Path dir) throws IOException
    {
        Path file = Files.createFile(dir.resolve("secret.txt"));
        cleaner("EARLY").cleanPath(file);
        assertFalse(Files.exists(file), "EARLY should delete the regular file");
    }

    @Test
    public void testModeIsCaseInsensitive(@TempDir Path dir) throws IOException
    {
        Path file = Files.createFile(dir.resolve("secret.txt"));
        cleaner("early").cleanPath(file);
        assertFalse(Files.exists(file), "lowercase mode should work too");
    }

    @Test
    public void testNeverLeavesFile(@TempDir Path dir) throws IOException
    {
        Path file = Files.createFile(dir.resolve("keep.txt"));
        cleaner("NEVER").cleanPath(file);
        assertTrue(Files.exists(file), "NEVER should leave the file alone");
    }

    @Test
    public void testLateDoesNotDeleteImmediately(@TempDir Path dir) throws IOException
    {
        Path file = Files.createFile(dir.resolve("late.txt"));
        cleaner("LATE").cleanPath(file);
        // LATE defers to JVM shutdown via File.deleteOnExit, so the file is still present now.
        assertTrue(Files.exists(file), "LATE should not delete immediately");
    }

    @Test
    public void testDirectoryIsSkipped(@TempDir Path dir) throws IOException
    {
        Path sub = Files.createDirectory(dir.resolve("adir"));
        cleaner("EARLY").cleanPath(sub);
        assertTrue(Files.exists(sub), "a directory must never be deleted");
    }

    @Test
    public void testMissingPathIsNoOp(@TempDir Path dir)
    {
        // must not throw
        cleaner("EARLY").cleanPath(dir.resolve("does-not-exist.txt"));
    }

    @Test
    public void testCsvListIsCleaned(@TempDir Path dir) throws IOException
    {
        Path a = Files.createFile(dir.resolve("a.txt"));
        Path b = Files.createFile(dir.resolve("b.txt"));
        cleaner("EARLY").cleanPaths(a.toAbsolutePath() + "," + b.toAbsolutePath());
        assertFalse(Files.exists(a));
        assertFalse(Files.exists(b));
    }

    @Test
    public void testBlankAndNullInputsAreNoOp()
    {
        PathCleaner cleaner = cleaner("EARLY");
        // none of these should throw (notably no NPE and no operation on the working directory)
        cleaner.cleanPaths(null);
        cleaner.cleanPaths("");
        cleaner.cleanPaths("   ");
        cleaner.cleanPath((String)null);
        cleaner.cleanPath("");
        cleaner.cleanPath((Path)null);
    }

    @Test
    public void testNullModeThrowsMeaningfully()
    {
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> new PathCleaner().setMode((String)null));
        assertThat(e.getMessage(), containsString("may not be null"));
        assertThat(e.getMessage(), containsString("EARLY, LATE, NEVER"));
    }

    @Test
    public void testInvalidModeThrowsMeaningfully()
    {
        IllegalArgumentException e =
            assertThrows(IllegalArgumentException.class, () -> new PathCleaner().setMode("bogus"));
        assertThat(e.getMessage(), containsString("bogus"));
        assertThat(e.getMessage(), containsString("EARLY, LATE, NEVER"));
    }
}
