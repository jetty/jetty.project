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

/**
 * Jetty dynamic compression handler. This abstraction allows for different compression and decompression
 * methods to be plugged in via {@link java.util.ServiceLoader} mechanism.
 */
package org.eclipse.jetty.server.handler.compression;