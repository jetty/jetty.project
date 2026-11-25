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

package org.eclipse.jetty.server;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;

import org.eclipse.jetty.util.TypeUtil;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>Base for an {@link AliasCheck} that approves an alias whose as-requested and real URIs name the same
 * file and differ only in the Unicode normalization form of their non-ASCII content.</p>
 *
 * <p>Such an alias arises on a normalization-insensitive or normalizing filesystem (macOS APFS/HFS+, ZFS
 * {@code normalization=formD}): a non-ASCII name stored in one normal form is requested in another, both
 * resolve to the same file, but {@link Resource#getRealURI()} comes back in a different normal form than
 * {@link Resource#getURI()}, so {@code PathResource} reports it as an alias. See JDK-8289689 for the
 * JDK-version dependence of which side differs.</p>
 *
 * <p>The approval is directional: the as-requested URI is folded to the subclass's chosen normal form, and
 * the alias is approved only when the result is exactly the real URI. Folding touches only non-ASCII
 * content, so differences in ASCII characters or ASCII percent-escapes (e.g. {@code %27} vs {@code '}) and
 * structural differences such as a trailing dot remain significant and are never approved here.</p>
 */
abstract class AbstractNormalizationAliasChecker extends AbstractLifeCycle implements AliasCheck
{
    private final Normalizer.Form form;

    protected AbstractNormalizationAliasChecker(Normalizer.Form form)
    {
        this.form = form;
    }

    @Override
    public boolean checkAlias(String pathInContext, Resource resource)
    {
        URI realUri = resource.getRealURI();
        if (realUri == null)
            return false;

        URI uri = resource.getURI();
        if (uri == null || uri.equals(realUri))
            return false;

        return toNormalizedUri(uri).equals(realUri);
    }

    /**
     * Return a copy of a URI with its non-ASCII content folded to this checker's Unicode normal form.
     * Only non-ASCII percent-escapes are decoded and folded; ASCII characters and ASCII percent-escapes
     * are left verbatim, so unrelated encoding differences remain significant.
     */
    private URI toNormalizedUri(URI uri)
    {
        // Decode only the non-ASCII percent-escapes to characters, keeping ASCII characters and ASCII
        // escapes verbatim, so a base letter and any following combining mark sit together as text.
        String ascii = uri.toASCIIString();
        StringBuilder decoded = new StringBuilder(ascii.length());
        int i = 0;
        while (i < ascii.length())
        {
            if (!isEncodedNonAscii(ascii, i))
            {
                decoded.append(ascii.charAt(i++));
                continue;
            }

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            while (isEncodedNonAscii(ascii, i))
            {
                bytes.write((TypeUtil.convertHexDigit(ascii.charAt(i + 1)) << 4) | TypeUtil.convertHexDigit(ascii.charAt(i + 2)));
                i += 3;
            }
            decoded.append(bytes.toString(StandardCharsets.UTF_8));
        }

        // Normalize the whole string so a base letter and a combining mark compose (or decompose) across the
        // boundary between literal ASCII and the decoded name, then re-encode every non-ASCII byte as a UTF-8
        // percent-escape, leaving ASCII (including any ASCII escapes) as written.
        String normalized = Normalizer.normalize(decoded, form);
        StringBuilder out = new StringBuilder(normalized.length());
        for (byte b : normalized.getBytes(StandardCharsets.UTF_8))
        {
            int unsigned = b & 0xff;
            if (unsigned < 0x80)
                out.append((char)unsigned);
            else
                out.append('%')
                    .append(Character.toUpperCase(Character.forDigit(unsigned >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(unsigned & 0xf, 16)));
        }
        return URI.create(out.toString());
    }

    private static boolean isEncodedNonAscii(String value, int index)
    {
        return index + 2 < value.length() &&
            value.charAt(index) == '%' &&
            TypeUtil.convertHexDigit(value.charAt(index + 1)) >= 0x8;
    }
}
