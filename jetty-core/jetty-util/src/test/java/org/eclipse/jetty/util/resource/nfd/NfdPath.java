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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.text.Normalizer;
import java.util.Iterator;

/**
 * A {@link Path} that wraps a real backing path and reproduces the macOS/APFS Unicode quirk, so the
 * alias behavior it triggers can be exercised on any OS (a plain Linux filesystem never produces it).
 *
 * <p>
 * The backing file really exists on the default filesystem (in NFC on Linux), so {@code exists()},
 * {@code isDirectory()}, and {@code toRealPath()} all behave normally. The only difference is that the
 * path returned from {@link #toRealPath} reports its {@link #toUri()} in Normalization Form D while its
 * {@link #toString()} stays in Form C: on macOS the JDK decodes native bytes to NFC for
 * {@code toString()} but percent-encodes the raw {@code realpath(3)} bytes (NFD) for {@code toUri()}.
 * </p>
 */
public class NfdPath implements Path
{
    final NfdFileSystem fs;
    final Path backing;
    // When non-null, the URI this path reports from toUri(); used to force the NFD form on a real path.
    private final URI uriOverride;

    NfdPath(NfdFileSystem fs, Path backing, URI uriOverride)
    {
        this.fs = fs;
        this.backing = backing;
        this.uriOverride = uriOverride;
    }

    private static URI toNfdUri(URI uri)
    {
        // Transform the as-requested (NFC) URI string in place: keep every ASCII character and ASCII
        // percent-escape exactly as written (scheme, the "file:///" prefix, slashes, ASCII escapes),
        // and re-encode only the non-ASCII name run in NFD.
        //
        // We must percent-encode the NFD bytes ourselves and feed an already-ASCII string to the
        // single-argument URI factory: the multi-argument constructors and URI.toASCIIString() run the
        // string through Normalizer.normalize(..., NFC) before encoding, which would undo the
        // decomposition. macOS does not hit this because its Path.toUri() likewise stores the name
        // pre-encoded in NFD (all-ASCII), so toASCIIString() passes it through verbatim.
        String ascii = uri.toASCIIString();
        StringBuilder out = new StringBuilder(ascii.length());
        int i = 0;
        while (i < ascii.length())
        {
            if (hexByteAt(ascii, i) < 0x80)
            {
                out.append(ascii.charAt(i));
                i++;
                continue;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int b;
            while ((b = hexByteAt(ascii, i)) >= 0x80)
            {
                bytes.write(b);
                i += 3;
            }
            String nfd = Normalizer.normalize(bytes.toString(StandardCharsets.UTF_8), Normalizer.Form.NFD);
            for (byte nb : nfd.getBytes(StandardCharsets.UTF_8))
            {
                int c = nb & 0xff;
                if (c < 0x80)
                    out.append((char)c);
                else
                    out.append('%')
                        .append(Character.toUpperCase(Character.forDigit((c >> 4) & 0xf, 16)))
                        .append(Character.toUpperCase(Character.forDigit(c & 0xf, 16)));
            }
        }
        return URI.create(out.toString());
    }

    private static int hexByteAt(String value, int index)
    {
        if (index + 2 >= value.length() || value.charAt(index) != '%')
            return -1;
        int hi = Character.digit(value.charAt(index + 1), 16);
        int lo = Character.digit(value.charAt(index + 2), 16);
        if (hi < 0 || lo < 0)
            return -1;
        return (hi << 4) | lo;
    }

    private Path wrap(Path p)
    {
        return p == null ? null : new NfdPath(fs, p, null);
    }

    @Override
    public NfdFileSystem getFileSystem()
    {
        return fs;
    }

    @Override
    public boolean isAbsolute()
    {
        return backing.isAbsolute();
    }

    @Override
    public Path getRoot()
    {
        return wrap(backing.getRoot());
    }

    @Override
    public Path getFileName()
    {
        return wrap(backing.getFileName());
    }

    @Override
    public Path getParent()
    {
        return wrap(backing.getParent());
    }

    @Override
    public int getNameCount()
    {
        return backing.getNameCount();
    }

    @Override
    public Path getName(int index)
    {
        return wrap(backing.getName(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex)
    {
        return wrap(backing.subpath(beginIndex, endIndex));
    }

    @Override
    public boolean startsWith(Path other)
    {
        return backing.startsWith(NfdFileSystemProvider.unwrap(other));
    }

    @Override
    public boolean endsWith(Path other)
    {
        return backing.endsWith(NfdFileSystemProvider.unwrap(other));
    }

    @Override
    public Path normalize()
    {
        return wrap(backing.normalize());
    }

    @Override
    public Path resolve(Path other)
    {
        return wrap(backing.resolve(NfdFileSystemProvider.unwrap(other)));
    }

    @Override
    public Path relativize(Path other)
    {
        return wrap(backing.relativize(NfdFileSystemProvider.unwrap(other)));
    }

    @Override
    public URI toUri()
    {
        return uriOverride != null ? uriOverride : backing.toUri();
    }

    @Override
    public Path toAbsolutePath()
    {
        return wrap(backing.toAbsolutePath());
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException
    {
        Path real = backing.toRealPath(options);
        // Simulate realpath(3) on macOS handing back NFD native bytes: toUri() is NFD, toString() is NFC.
        return new NfdPath(fs, real, toNfdUri(real.toUri()));
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers)
    {
        throw new UnsupportedOperationException("test fixture does not support watches");
    }

    @Override
    public int compareTo(Path other)
    {
        return backing.compareTo(NfdFileSystemProvider.unwrap(other));
    }

    @Override
    public Iterator<Path> iterator()
    {
        Iterator<Path> it = backing.iterator();
        return new Iterator<>()
        {
            @Override
            public boolean hasNext()
            {
                return it.hasNext();
            }

            @Override
            public Path next()
            {
                return wrap(it.next());
            }
        };
    }

    @Override
    public boolean equals(Object obj)
    {
        if (this == obj)
            return true;
        if (!(obj instanceof NfdPath other))
            return false;
        return backing.equals(other.backing);
    }

    @Override
    public int hashCode()
    {
        return backing.hashCode();
    }

    @Override
    public String toString()
    {
        return backing.toString();
    }
}
