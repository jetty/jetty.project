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

package org.eclipse.jetty.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Simple, yet surprisingly common utility methods for identifying various file types commonly seen and worked with in a
 * deployment scenario.
 */
public class FileID
{
    /**
     * Retrieve the basename of a path. This is the name of the
     * last segment of the path, with any dot suffix (e.g. ".war") removed
     *
     * @param path The string path
     * @return The last segment of the path without any dot suffix
     */
    public static String getBasename(Path path)
    {
        Path filename = path.getFileName();
        if (filename == null)
            return "";
        String basename = filename.toString();
        int dot = basename.lastIndexOf('.');
        if (dot >= 0)
            basename = basename.substring(0, dot);
        return basename;
    }

    /**
     * Get the last segment of the URI returning it as the filename
     *
     * @param uri the URI to look for the filename
     * @return The last segment of the uri
     */
    public static String getFileName(URI uri)
    {
        if (uri == null)
            return "";
        String path = uri.getPath();
        if (path == null || "/".equals(path))
            return "";
        int idx = path.lastIndexOf('/');
        if (idx >= 0)
            return path.substring(idx + 1);
        return path;
    }

    /**
     * <p>
     * Retrieve the extension of a URI path.
     * </p>
     *
     * <p>
     * This is the name of the last segment of the URI path with a substring
     * for the extension (if any), excluding the dot, lower-cased.
     * </p>
     *
     * <code>{@code
     * "foo.tar.gz" "gz"
     * "foo.bar"    "bar"
     * "foo."       ""
     * "foo"        null
     * ".bar"       null
     * null         null
     * }</code>
     *
     * @param uri The URI to search
     * @return The last segment extension. Null if input uri is null, or scheme is null, or URI is not a `jar:file:` or `file:` based URI
     */
    public static String getExtension(URI uri)
    {
        if (uri == null)
            return null;
        if (uri.getScheme() == null)
            return null;

        String path = null;
        if (uri.getScheme().equalsIgnoreCase("jar"))
        {
            URI sspUri = URI.create(uri.getRawSchemeSpecificPart());
            if (!sspUri.getScheme().equalsIgnoreCase("file"))
            {
                return null; // not a `jar:file:` based URI
            }

            path = sspUri.getPath();
        }
        else
        {
            path = uri.getPath();
        }

        // look for `!/` split
        int jarEnd = path.indexOf("!/");
        if (jarEnd >= 0)
        {
            return getExtension(path.substring(0, jarEnd));
        }
        return getExtension(path);
    }

    /**
     * <p>
     * Retrieve the extension of a file path (not a directory).
     * </p>
     *
     * <p>
     * This is the name of the last segment of the file path with a substring
     * for the extension (if any), excluding the dot, lower-cased.
     * </p>
     *
     * <code>{@code
     * "foo.tar.gz" "gz"
     * "foo.bar"    "bar"
     * "foo."       ""
     * "foo"        null
     * ".bar"       null
     * null         null
     * }</code>
     *
     * @param path The string path
     * @return The last segment extension, or null if not a file, or null if there is no extension present
     */
    public static String getExtension(Path path)
    {
        if (path == null)
            return null; // no path

        if (!Files.isRegularFile(path))
            return null; // not a file

        return getExtension(path.getFileName().toString());
    }

    /**
     * Retrieve the extension of a file path (not a directory).
     * This is the extension of filename of the last segment of the file path with a substring
     * for the extension (if any), including the dot, lower-cased.
     *
     * @param filename The string path
     * @return The last segment extension, or null if not a file, or null if there is no extension present
     */
    public static String getExtension(String filename)
    {
        if (filename == null)
            return null; // no filename
        if (filename.endsWith("/") || filename.endsWith("\\"))
            return null; // not a filename
        int lastSlash = filename.lastIndexOf(File.separator);
        if (lastSlash >= 0)
            filename = filename.substring(lastSlash + 1);
        int lastDot = filename.lastIndexOf('.');
        if (lastDot <= 0)
            return null; // no extension, or only filename that is only an extension (".foo")
        return filename.substring(lastDot + 1).toLowerCase(Locale.ENGLISH);
    }

    /**
     * Test if Path matches any of the indicated extensions.
     *
     * @param path the Path to test
     * @param extensions the list of extensions (all lowercase, without preceding {@code .} dot)
     * @return true if Path is a file, and has an extension, and it matches any of the indicated extensions
     */
    public static boolean isExtension(Path path, String... extensions)
    {
        return matchesExtension(getExtension(path), extensions);
    }

    /**
     * Test if URI matches any of the indicated extensions.
     *
     * @param uri the URI to test
     * @param extensions the list of extensions (all lowercase, without preceding {@code .} dot)
     * @return true if URI has an extension, and it matches any of the indicated extensions
     */
    public static boolean isExtension(URI uri, String... extensions)
    {
        return matchesExtension(getExtension(uri), extensions);
    }

    /**
     * Test if filename matches any of the indicated extensions.
     *
     * @param filename the filename to test
     * @param extensions the list of extensions (all lowercase, without preceding {@code .} dot)
     * @return true if filename has an extension, and it matches any of the indicated extensions
     */
    public static boolean isExtension(String filename, String... extensions)
    {
        return matchesExtension(getExtension(filename), extensions);
    }

    private static boolean matchesExtension(String ext, String... extensions)
    {
        if (ext == null)
            return false;
        for (String extension : extensions)
        {
            if (ext.equals(extension))
                return true;
        }
        return false;
    }

    /**
     * Does the provided path have a directory segment with the given name.
     *
     * @param path the path to search
     * @param segmentName the segment name (of the given path) to look for (case-insensitive lookup),
     * only capable of searching 1 segment name at a time, does not support "foo/bar" multi-segment
     * names.
     * @return true if the directory name exists in path, false if otherwise
     */
    public static boolean hasNamedPathSegment(Path path, String segmentName)
    {
        if (path == null)
            return false;
        int segmentCount = path.getNameCount();
        for (int i = segmentCount - 1; i >= 0; i--)
        {
            Path segment = path.getName(i);
            if (segment.getFileName().toString().equalsIgnoreCase(segmentName))
                return true;
        }
        return false;
    }

    /**
     * Test if Path is any supported Java Archive type (ends in {@code jar}, {@code war}, or {@code zip}).
     *
     * @param path the path to test
     * @return true if path is a file, and an extension of {@code jar}, {@code war}, or {@code zip}
     * @see #getExtension(Path)
     */
    public static boolean isArchive(Path path)
    {
        return isExtension(path, "jar", "war", "zip");
    }

    /**
     * Test if filename is any supported Java Archive type (ends in {@code jar}, {@code war}, or {@code zip}).
     *
     * @param filename the filename to test
     * @return true if path is a file and name ends with {@code jar}, {@code war}, or {@code zip}
     * @see #getExtension(String)
     */
    public static boolean isArchive(String filename)
    {
        return isExtension(filename, "jar", "war", "zip");
    }

    /**
     * Test if URI is any supported Java Archive type.
     *
     * @param uri the URI to test
     * @return true if the URI has a path that seems to point to a ({@code jar}, {@code war}, or {@code zip}).
     * @see #isArchive(String)
     */
    public static boolean isArchive(URI uri)
    {
        return isExtension(uri, "jar", "war", "zip");
    }

    /**
     * Test if Path is any supported Java Library Archive type (suitable to use as a library in a classpath/classloader)
     *
     * @param path the path to test
     * @return true if path is a file, and an extension of {@code jar}, or {@code zip}
     * @see #getExtension(Path)
     */
    public static boolean isLibArchive(Path path)
    {
        return isExtension(path, "jar", "zip");
    }

    /**
     * Test if filename is any supported Java Library Archive type (suitable to use as a library in a classpath/classloader)
     *
     * @param filename the filename to test
     * @return true if path is a file and name ends with {@code jar}, or {@code zip}
     * @see #getExtension(String)
     */
    public static boolean isLibArchive(String filename)
    {
        return isExtension(filename, "jar", "zip");
    }

    /**
     * Test if URI is any supported Java Library Archive type (suitable to use as a library in a classpath/classloader)
     *
     * @param uri the URI to test
     * @return true if the URI has a path that seems to point to a ({@code jar},  or {@code zip}).
     * @see #getExtension(URI)
     */
    public static boolean isLibArchive(URI uri)
    {
        return isExtension(uri, "jar", "zip");
    }

    /**
     * Predicate to select all class files
     *
     * @param path the path to test
     * @return true if the filename ends with {@code .class}
     */
    public static boolean isClassFile(Path path)
    {
        String filename = path.getFileName().toString();
        // has to end in ".class"
        if (!filename.toLowerCase(Locale.ENGLISH).endsWith(".class"))
            return false;
        // is it a valid class filename?
        int start = 0;
        int end = filename.length() - 6; // drop ".class"
        if (end <= start) // if the filename is only ".class"
            return false;
        // Test first character
        if (!Character.isJavaIdentifierStart(filename.charAt(0)))
            return false;
        // Test rest
        for (int i = start + 1; i < end; i++)
        {
            if (!Character.isJavaIdentifierPart(filename.codePointAt(i)))
            {
                // not a java identifier
                return false;
            }
        }
        return true;
    }

    /**
     * Predicate useful for {@code Stream<Path>} to exclude hidden paths following
     * filesystem rules for hidden directories and files.
     *
     * @param base the base path to search from (anything above this path is not evaluated)
     * @param path the path to evaluate
     * @return true if hidden by FileSystem rules, false if not
     * @see Files#isHidden(Path)
     */
    public static boolean isHidden(Path base, Path path)
    {
        // Work with the path in relative form, from the base onwards to the path
        Path relative = base.relativize(path);

        int count = relative.getNameCount();
        for (int i = 0; i < count; i++)
        {
            Path segment = relative.getName(i);

            String segmentName = segment.toString();

            if (segmentName.isBlank())
                continue; // skip blank entries

            // default behavior that Jetty enforces
            if (segmentName.charAt(0) == '.')
                return true;

            // FileSystem behavior, that the FileSystem enforces
            try
            {
                if (Files.isHidden(segment))
                    return true;
            }
            catch (IOException ignore)
            {
                // ignore, if filesystem gives us an error, we cannot make the call on hidden status
            }
        }

        return false;
    }

    /**
     * Is the URI pointing to a Java Archive (JAR) File (not directory)
     *
     * @param uri the uri to test.
     * @return True if a jar file.
     */
    public static boolean isJavaArchive(URI uri)
    {
        return isExtension(uri, "jar");
    }

    /**
     * Is the path a Java Archive (JAR) File (not directory)
     *
     * @param path the path to test.
     * @return True if a jar file.
     */
    public static boolean isJavaArchive(Path path)
    {
        return isExtension(path, "jar");
    }

    /**
     * Is the filename a JAR file.
     *
     * @param filename the filename to test.
     * @return True if a jar file.
     */
    public static boolean isJavaArchive(String filename)
    {
        return isExtension(filename, "jar");
    }

    /**
     * Predicate to filter on {@code META-INF/versions/*} tree in walk/stream results.
     *
     * <p>
     * This only works with a zipfs based FileSystem
     * </p>
     *
     * @param path the path to test
     * @return true if path is in {@code META-INF/versions/*} tree
     */
    public static boolean isMetaInfVersions(Path path)
    {
        if (path.getNameCount() < 3)
            return false;

        Path path0 = path.getName(0);
        Path path1 = path.getName(1);
        Path path2 = path.getName(2);

        return (path0.toString().equals("META-INF") &&
            path1.toString().equals("versions") &&
            path2.getFileName().toString().matches("[0-9]+"));
    }

    /**
     * Predicate to skip {@code META-INF/versions/*} tree from walk/stream results.
     *
     * <p>
     * This only works with a zipfs based FileSystem
     * </p>
     *
     * @param path the path to test
     * @return true if not in {@code META-INF/versions/*} tree
     */
    public static boolean isNotMetaInfVersions(Path path)
    {
        return !isMetaInfVersions(path);
    }

    /**
     * Predicate to skip {@code module-info.class} files.
     *
     * <p>
     * This is a simple test against the last path segment using {@link Path#getFileName()}
     * </p>
     *
     * @param path the path to test
     * @return true if not a {@code module-info.class} file
     */
    public static boolean isNotModuleInfoClass(Path path)
    {
        Path filenameSegment = path.getFileName();
        if (filenameSegment == null)
            return true;

        return !filenameSegment.toString().equalsIgnoreCase("module-info.class");
    }

    /**
     * Is the path a TLD File
     *
     * @param path the path to test.
     * @return True if a .tld file.
     */
    public static boolean isTld(Path path)
    {
        if (path == null)
            return false;
        if (!hasNamedPathSegment(path, "META-INF"))
            return false;
        return isExtension(path, ".tld");
    }

    /**
     * Is the path a Web Archive File (not directory)
     *
     * @param path the path to test.
     * @return True if a war file.
     */
    public static boolean isWebArchive(Path path)
    {
        return isExtension(path, "war");
    }

    /**
     * Is the path a Web Archive File (not directory)
     *
     * @param uri the uri to test.
     * @return True if a war file.
     */
    public static boolean isWebArchive(URI uri)
    {
        return isExtension(uri, "war");
    }

    /**
     * Is the filename a WAR file.
     *
     * @param filename the filename to test.
     * @return True if a war file.
     */
    public static boolean isWebArchive(String filename)
    {
        return isExtension(filename, "war");
    }

    /**
     * Is the Path a file that ends in XML?
     *
     * @param path the path to test
     * @return true if a xml, false otherwise
     */
    public static boolean isXml(Path path)
    {
        return isExtension(path, "xml");
    }

    /**
     * Is the Path a file that ends in XML?
     *
     * @param filename the filename to test
     * @return true if a xml, false otherwise
     */
    public static boolean isXml(String filename)
    {
        return isExtension(filename, "xml");
    }
}
