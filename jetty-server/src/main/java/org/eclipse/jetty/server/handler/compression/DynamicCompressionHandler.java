//
// ========================================================================
// Copyright (c) 1995-2023 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.handler.compression;

import org.eclipse.jetty.server.handler.HandlerWrapper;

/**
 * a generic parent class for all compression handler, including:
 * <ul>
 *     <li>{@link org.eclipse.jetty.server.handler.gzip.GzipHandler}</li>
 * </ul>
 */
public abstract class DynamicCompressionHandler extends HandlerWrapper {
}
