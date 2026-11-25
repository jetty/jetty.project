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

import java.text.Normalizer;

/**
 * <p>Approves an alias where the real resource's name is in Unicode Normalization Form D and the
 * as-requested name is the same name in Form C (or any other form that folds to the same Form D).</p>
 *
 * <p>This is the case on a filesystem that stores or returns decomposed names (macOS APFS/HFS+, ZFS
 * {@code normalization=formD}): a file whose on-disk name is NFD is requested with its NFC form, both
 * resolve to the same file, and the real URI comes back NFD while the request is NFC. Folding the request
 * to NFD then matches the real URI, so the alias is approved.</p>
 *
 * <p>The reverse (an NFC real name requested in NFD) is <em>not</em> approved by this checker; use
 * {@link NfcAliasChecker} for that direction. Neither checker is registered by default; register one on a
 * context whose filesystem needs it with {@link org.eclipse.jetty.server.handler.ContextHandler#addAliasCheck}.</p>
 */
public class NfdAliasChecker extends AbstractNormalizationAliasChecker
{
    public NfdAliasChecker()
    {
        super(Normalizer.Form.NFD);
    }
}
