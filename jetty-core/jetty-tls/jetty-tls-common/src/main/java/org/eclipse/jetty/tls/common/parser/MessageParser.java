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
import org.eclipse.jetty.tls.Message;

/// Parser for TLS [Message]s.
///
/// TLS messages are delivered in a stream of bytes,
/// as CRYPTO frames are similar to STREAM frames.
/// As such, parsing just continues until there are
/// no more bytes, and returns TLS messages as they
/// are parsed, possibly not fully consuming the
/// buffer, which may contain bytes from the next
/// TLS message.
public interface MessageParser
{
    Message parse(int messageLength, RetainableByteBuffer buffer);
}
