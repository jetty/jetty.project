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

import java.net.URI;
import java.nio.file.Path;

import org.eclipse.jetty.server.handler.ContextHandler;
import org.eclipse.jetty.util.resource.Resource;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NfcAliasChecker} and {@link NfdAliasChecker}, which consume only the resource's
 * as-requested and real URIs, so a stub {@link Resource} is enough to exercise the full decision.
 */
public class NormalizationAliasCheckerTest
{
    // "swedish-å.txt" with the 'å' precomposed (NFC: U+00E5 == %C3%A5).
    private static final URI NFC_URI = URI.create("file:///tmp/dir/swedish-%C3%A5.txt");
    // The same name decomposed (NFD: 'a' + combining ring U+030A == a%CC%8A).
    private static final URI NFD_URI = URI.create("file:///tmp/dir/swedish-a%CC%8A.txt");

    private final NfcAliasChecker nfc = new NfcAliasChecker();
    private final NfdAliasChecker nfd = new NfdAliasChecker();

    @Test
    public void testNfdApprovesNfcRequestForNfdRealName()
    {
        // The macOS/APFS, ZFS normalization=formD case: NFD on disk, requested in NFC.
        assertTrue(nfd.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, NFD_URI)));
    }

    @Test
    public void testNfdDeniesTheReverseDirection()
    {
        // NFC on disk, requested in NFD: folding the already-NFD request to NFD cannot reach the NFC real.
        assertFalse(nfd.checkAlias("/dir/swedish-å.txt", aliasResource(NFD_URI, NFC_URI)));
    }

    @Test
    public void testNfcApprovesNfdRequestForNfcRealName()
    {
        assertTrue(nfc.checkAlias("/dir/swedish-å.txt", aliasResource(NFD_URI, NFC_URI)));
    }

    @Test
    public void testNfcDeniesTheReverseDirection()
    {
        assertFalse(nfc.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, NFD_URI)));
    }

    @Test
    public void testAsciiPercentEscapeAliasIsNotApproved()
    {
        // %27 vs ' is an ASCII encoding difference, not a normalization difference: must stay an alias.
        URI encoded = URI.create("file:///tmp/dir/foo%27s.txt");
        URI literal = URI.create("file:///tmp/dir/foo's.txt");
        assertFalse(nfc.checkAlias("/dir/foo's.txt", aliasResource(encoded, literal)));
        assertFalse(nfd.checkAlias("/dir/foo's.txt", aliasResource(encoded, literal)));
    }

    @Test
    public void testTrailingDotAliasIsNotApproved()
    {
        // The Windows trailing-dot case (aa./foo.txt vs aa/foo.txt) is a structural ASCII difference.
        URI requested = URI.create("file:///C:/temp/aa./foo.txt");
        URI real = URI.create("file:///C:/temp/aa/foo.txt");
        assertFalse(nfc.checkAlias("/aa./foo.txt", aliasResource(requested, real)));
        assertFalse(nfd.checkAlias("/aa./foo.txt", aliasResource(requested, real)));
    }

    @Test
    public void testGenuinelyDifferentNameIsNotApproved()
    {
        URI requested = URI.create("file:///tmp/dir/foo.txt");
        URI real = URI.create("file:///tmp/dir/bar.txt");
        assertFalse(nfc.checkAlias("/dir/foo.txt", aliasResource(requested, real)));
        assertFalse(nfd.checkAlias("/dir/foo.txt", aliasResource(requested, real)));
    }

    @Test
    public void testNullRealUriIsNotApproved()
    {
        assertFalse(nfc.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, null)));
        assertFalse(nfd.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, null)));
    }

    @Test
    public void testEqualUrisAreNotApproved()
    {
        // Not actually an alias in the normalization sense; nothing for these checkers to approve.
        assertFalse(nfc.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, NFC_URI)));
        assertFalse(nfd.checkAlias("/dir/swedish-å.txt", aliasResource(NFC_URI, NFC_URI)));
    }

    @Test
    public void testContextHandlerApprovesOnlyWhenNfdCheckerRegistered()
    {
        // As the serving path sees it: ContextHandler.checkAlias consults the registered checkers for an
        // aliased resource. With only the default checks a normalization alias is rejected; registering
        // NfdAliasChecker makes it approve.
        Resource resource = aliasResource(NFC_URI, NFD_URI);

        ContextHandler context = new ContextHandler();
        assertFalse(context.checkAlias("/dir/swedish-å.txt", resource),
            "Default alias checks do not approve a normalization alias");

        context.addAliasCheck(new NfdAliasChecker());
        assertTrue(context.checkAlias("/dir/swedish-å.txt", resource),
            "ContextHandler approves the alias once NfdAliasChecker is registered");
    }

    private static Resource aliasResource(URI uri, URI realUri)
    {
        return new Resource()
        {
            @Override
            public Path getPath()
            {
                return null;
            }

            @Override
            public boolean isDirectory()
            {
                return false;
            }

            @Override
            public boolean isReadable()
            {
                return true;
            }

            @Override
            public URI getURI()
            {
                return uri;
            }

            @Override
            public URI getRealURI()
            {
                return realUri;
            }

            @Override
            public boolean isAlias()
            {
                return true;
            }

            @Override
            public String getName()
            {
                return uri == null ? "" : uri.toASCIIString();
            }

            @Override
            public String getFileName()
            {
                return "";
            }

            @Override
            public Resource resolve(String subUriPath)
            {
                return null;
            }
        };
    }
}
