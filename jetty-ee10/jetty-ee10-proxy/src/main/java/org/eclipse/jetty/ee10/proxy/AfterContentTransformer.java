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

package org.eclipse.jetty.ee10.proxy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;

/**
 * <p>A specialized transformer for {@link AsyncMiddleManServlet} that performs
 * the transformation when the whole content has been received.</p>
 * <p>The content is buffered in memory up to a configurable {@link #getMaxInputBufferSize() maximum size},
 * after which it is overflown to a file on disk. The overflow file is saved
 * in the {@link #getOverflowDirectory() overflow directory} as a
 * {@link Files#createTempFile(Path, String, String, FileAttribute[]) temporary file}
 * with a name starting with the {@link #getInputFilePrefix() input prefix}
 * and default suffix.</p>
 * <p>Application must implement the {@link #transform(Source, Sink) transformation method}
 * to transform the content.</p>
 * <p>The transformed content is buffered in memory up to a configurable {@link #getMaxOutputBufferSize() maximum size}
 * after which it is overflown to a file on disk. The overflow file is saved
 * in the {@link #getOverflowDirectory() overflow directory} as a
 * {@link Files#createTempFile(Path, String, String, FileAttribute[]) temporary file}
 * with a name starting with the {@link #getOutputFilePrefix()} output prefix}
 * and default suffix.</p>
 */
public abstract class AfterContentTransformer extends org.eclipse.jetty.ee.proxy.AfterContentTransformer
{
}
