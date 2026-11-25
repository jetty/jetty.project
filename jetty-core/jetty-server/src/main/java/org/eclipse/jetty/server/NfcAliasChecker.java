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
 * <p>Approves an alias where the real resource's name is in Unicode Normalization Form C and the
 * as-requested name is the same name in Form D (or any other form that folds to the same Form C).</p>
 *
 * <p>This is the mirror of {@link NfdAliasChecker}, for content whose on-disk names are composed (NFC)
 * while requests arrive decomposed (NFD): folding the request to NFC then matches the real URI, so the
 * alias is approved.</p>
 *
 * <p>The reverse (an NFD real name requested in NFC) is <em>not</em> approved by this checker; use
 * {@link NfdAliasChecker} for that direction. Neither checker is registered by default; register one on a
 * context whose filesystem needs it with {@link org.eclipse.jetty.server.handler.ContextHandler#addAliasCheck}.</p>
 */
public class NfcAliasChecker extends AbstractNormalizationAliasChecker
{
    public NfcAliasChecker()
    {
        super(Normalizer.Form.NFC);
    }
}
