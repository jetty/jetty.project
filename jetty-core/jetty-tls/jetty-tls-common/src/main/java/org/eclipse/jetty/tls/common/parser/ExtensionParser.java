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

package org.eclipse.jetty.tls.common.parser;

import org.eclipse.jetty.io.RetainableByteBuffer;
import org.eclipse.jetty.tls.ext.Extension;

/// Parser for TLS [Extension]s.
///
/// Since TLS extensions are only a part of some TLS messages,
/// parsing requires two return values: the extension object
/// and how many bytes have been parsed for that extension.
public interface ExtensionParser
{
    /// @return the TLS extension code
    int type();

    /// Parses the given buffer for one TLS [Extension].
    ///
    /// The parsing may not have enough bytes, in which case
    /// a negative value is returned by this method.
    ///
    /// Otherwise, enough bytes could be parsed to decode
    /// a TLS extension, which is notified to a [Listener]
    /// and this method returns with the number of bytes
    /// consumed to parse the TLS extension.
    /// The buffer may not be fully consumed if it contains
    /// multiple TLS extensions.
    ///
    /// @param buffer the buffer to parse
    /// @return the number of bytes consumed to decode a
    /// TLS extension, or a negative value if not enough
    /// bytes were available
    int parse(RetainableByteBuffer buffer);

    /// The listener to notify of a successfully parsed
    /// TLS [Extension].
    interface Listener
    {
        /// The method invoked when a TLS extension is
        /// successfully parsed.
        ///
        /// @param extension the TLS extension that was parsed
        void onExtension(Extension extension);
    }
}
