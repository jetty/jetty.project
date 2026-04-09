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

package org.eclipse.jetty.ee11.servlet;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jetty.server.AllowedResourceAliasChecker;
import org.eclipse.jetty.server.SymlinkAllowedResourceAliasChecker;
import org.eclipse.jetty.util.resource.Resource;

/**
 * <p>A Servlet that handles static resources.</p>
 * <p>The following init parameters are supported:</p>
 * <dl>
 *   <dt>acceptRanges</dt>
 *   <dd>
 *     Use {@code true} to accept range requests, defaults to {@code true}.
 *   </dd>
 *   <dt>baseResource</dt>
 *   <dd>
 *     The root directory to look for static resources. Defaults to the context's baseResource. Relative URI
 *     are {@link Resource#resolve(String) resolved} against the context's {@link ServletContextHandler#getBaseResource()}
 *     base resource, all other values are resolved using {@link ServletContextHandler#newResource(String)}.
 *   </dd>
 *   <dt>cacheControl</dt>
 *   <dd>
 *     The value of the {@code Cache-Control} header.
 *     If omitted, no {@code Cache-Control} header is generated in responses.
 *     By default is omitted.
 *   </dd>
 *   <dt>cacheValidationTime</dt>
 *   <dd>
 *     How long in milliseconds a resource is cached.
 *     If omitted, defaults to {@code 1000} ms.
 *     Use {@code -1} to cache forever or {@code 0} to not cache.
 *   </dd>
 *   <dt>dirAllowed</dt>
 *   <dd>
 *     Use {@code true} to serve directory listing if no welcome file is found.
 *     Otherwise responds with {@code 403 Forbidden}.
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>encodingHeaderCacheSize</dt>
 *   <dd>
 *     Max number of cached {@code Accept-Encoding} entries.
 *     Use {@code -1} for the default value (100), {@code 0} for no cache.
 *   </dd>
 *   <dt>etags</dt>
 *   <dd>
 *     Use {@code true} to generate ETags in responses.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>installAllowedResourceAliasChecker</dt>
 *   <dd>
 *     <em>Deprecated</em> use {@code allowAliases} instead.
 *   </dd>
 *   <dt>allowAliases</dt>
 *   <dd>
 *     Allow resource aliases via the {@link AllowedResourceAliasChecker}
 *     on the context (if one does not already exist) for this baseResource.
 *     This is especially useful if you have a FileSystem that is not
 *     case sensitive. (Such as on Windows with FAT or NTFS)
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>allowSymlinks</dt>
 *   <dd>
 *     Allow resources that are symlinks pointing to other locations via
 *     the {@link SymlinkAllowedResourceAliasChecker} on the context (if one
 *     does not already exist) for this baseResource.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>maxCachedFiles</dt>
 *   <dd>
 *     The max number of cached static resources.
 *     Use {@code -1} for the default value (2048) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCachedFileSize</dt>
 *   <dd>
 *     The max size in bytes of a single cached static resource.
 *     Use {@code -1} for the default value (128 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>maxCacheSize</dt>
 *   <dd>
 *     The max size in bytes of the cache for static resources.
 *     Use {@code -1} for the default value (256 MiB) or {@code 0} for no cache.
 *   </dd>
 *   <dt>otherGzipFileExtensions</dt>
 *   <dd>
 *     A comma-separated list of extensions of files whose content is implicitly
 *     gzipped.
 *     Defaults to {@code .svgz}.
 *   </dd>
 *   <dt>pathInfoOnly</dt>
 *   <dd>
 *     Use {@code true} to use only the pathInfo portion of a PATH (aka prefix) match
 *     as obtained from {@link HttpServletRequest#getPathInfo()}.
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>precompressed</dt>
 *   <dd>
 *     Omitted by default, so that no pre-compressed content will be served.
 *     If set to {@code true}, the default set of pre-compressed formats will be used.
 *     Otherwise can be set to a comma-separated list of {@code encoding=extension} pairs,
 *     such as: {@code br=.br,gzip=.gz,bzip2=.bz}, where {@code encoding} is used as the
 *     value for the {@code Content-Encoding} header.
 *   </dd>
 *   <dt>redirectWelcome</dt>
 *   <dd>
 *     Use {@code true} to redirect welcome files, otherwise they are forwarded.
 *     Defaults to {@code false}.
 *   </dd>
 *   <dt>resourceBase</dt>
 *   <dd>
 *     <em>Deprecated</em> use {@code baseResource} instead.
 *   </dd>
 *   <dt>stylesheet</dt>
 *   <dd>
 *     Defaults to the {@code Server}'s default stylesheet, {@code jetty-dir.css}.
 *     The path of a custom stylesheet to style the directory listing HTML.
 *   </dd>
 *   <dt>byteBufferSize</dt>
 *   <dd>
 *     The size of the buffers to use to serve static resources.
 *     Defaults to {@code 32 KiB}.
 *   </dd>
 *   <dt>useDirectByteBuffers</dt>
 *   <dd>
 *     Use {@code true} to use direct byte buffers to serve static resources.
 *     Defaults to {@code true}.
 *   </dd>
 *   <dt>minMappedFileSize</dt>
 *   <dd>
 *     The minimum size in bytes of a file that will used with file mapping; or {@code 0} for
 *     no file mapping; or {@code -1} (or net set) for a default size of 1MB.
 *   </dd>
 *   <dt>maxMappedFileSize</dt>
 *   <dd>
 *     The maximum size in bytes of a file that will used with file mapping;
 *     or {@code -1} (or not set) for a default size of {@link Integer#MAX_VALUE}.
 *   </dd>
 *   <dt>welcomeServlets</dt>
 *   <dd>
 *     Use {@code false} to only serve welcome resources when they exist on the file system.
 *     If they also map to a Servlet, then the servlet will be used to generate the response.
 *     Use {@code true} to dispatch welcome resources to a matching Servlet
 *     (for example mapped to {@code *.welcome}), even if the welcome resources
 *     does not exist on file system.
 *     Use {@code exact} to dispatch welcome resource to a Servlet when the resource does not
 *     exist on the file system, but only if the its mapping is exactly the same as the welcome
 *     resource (for example {@code /index.welcome})
 *     Defaults to {@code false}.
 *   </dd>
 * </dl>
 */
public class ResourceServlet extends org.eclipse.jetty.ee.servlet.ResourceServlet
{
}
