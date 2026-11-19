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

package org.eclipse.jetty.util.resource.nfd;

import java.io.IOException;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.Map;
import java.util.Set;

/**
 * A {@link FileSystemProvider} that forwards every operation to the default provider after unwrapping
 * {@link NfdPath} back to its real backing path. Files therefore live on the real (default) filesystem
 * and {@code toRealPath()}, {@code exists()}, and {@code isDirectory()} all behave normally; the only
 * difference is the {@link Path} representation, which lets {@link NfdPath#toUri()} report the macOS NFD
 * form. See {@link NfdPath} for the issue this reproduces.
 *
 * <p>
 * This is a test fixture, not a general-purpose provider: it is driven by constructing an
 * {@link NfdFileSystem} directly, so the URI-based entry points are unsupported.
 * </p>
 */
public class NfdFileSystemProvider extends FileSystemProvider
{
    private final FileSystemProvider delegate = FileSystems.getDefault().provider();

    static Path unwrap(Path path)
    {
        return (path instanceof NfdPath nfd) ? nfd.backing : path;
    }

    @Override
    public String getScheme()
    {
        return "nfd";
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env)
    {
        throw new UnsupportedOperationException("construct NfdFileSystem directly");
    }

    @Override
    public FileSystem getFileSystem(URI uri)
    {
        throw new UnsupportedOperationException("construct NfdFileSystem directly");
    }

    @Override
    public Path getPath(URI uri)
    {
        throw new UnsupportedOperationException("construct NfdFileSystem directly");
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException
    {
        return delegate.newByteChannel(unwrap(path), options, attrs);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter) throws IOException
    {
        return delegate.newDirectoryStream(unwrap(dir), filter);
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) throws IOException
    {
        delegate.createDirectory(unwrap(dir), attrs);
    }

    @Override
    public void delete(Path path) throws IOException
    {
        delegate.delete(unwrap(path));
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) throws IOException
    {
        delegate.copy(unwrap(source), unwrap(target), options);
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) throws IOException
    {
        delegate.move(unwrap(source), unwrap(target), options);
    }

    @Override
    public boolean isSameFile(Path path, Path path2) throws IOException
    {
        return delegate.isSameFile(unwrap(path), unwrap(path2));
    }

    @Override
    public boolean isHidden(Path path) throws IOException
    {
        return delegate.isHidden(unwrap(path));
    }

    @Override
    public FileStore getFileStore(Path path) throws IOException
    {
        return delegate.getFileStore(unwrap(path));
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException
    {
        delegate.checkAccess(unwrap(path), modes);
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options)
    {
        return delegate.getFileAttributeView(unwrap(path), type, options);
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options) throws IOException
    {
        return delegate.readAttributes(unwrap(path), type, options);
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException
    {
        return delegate.readAttributes(unwrap(path), attributes, options);
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) throws IOException
    {
        delegate.setAttribute(unwrap(path), attribute, value, options);
    }
}
