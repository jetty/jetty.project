//
//  ========================================================================
//  Copyright (c) 1995-2014 Mort Bay Consulting Pty. Ltd.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http;

/**
 * <p>
 * HttpStatusCode enum class, for status codes based on various HTTP RFCs. (see
 * table below)
 * </p>
 *
 * <table border="1" cellpadding="5">
 * <tr>
 * <th>Enum</th>
 * <th>Code</th>
 * <th>Message</th>
 * <th>
 * <a href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a></th>
 * <th>
 * <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a></th>
 * <th>
 * <a href="http://tools.ietf.org/html/rfc2518">RFC 2518 - WEBDAV</a></th>
 * </tr>
 *
 * <tr>
 * <td><strong><code>Informational - 1xx</code></strong></td>
 * <td colspan="5">{@link #isInformational(int)}</td>
 * </tr>
 *
 * <tr>
 * <td>{@link #CONTINUE_100}</td>
 * <td>100</td>
 * <td>Continue</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.1.1">Sec. 10.1.1</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #SWITCHING_PROTOCOLS_101}</td>
 * <td>101</td>
 * <td>Switching Protocols</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.1.2">Sec. 10.1.2</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #PROCESSING_102}</td>
 * <td>102</td>
 * <td>Processing</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.1">Sec. 10.1</a></td>
 * </tr>
 *
 * <tr>
 * <td><strong><code>Success - 2xx</code></strong></td>
 * <td colspan="5">{@link #isSuccess(int)}</td>
 * </tr>
 *
 * <tr>
 * <td>{@link #OK_200}</td>
 * <td>200</td>
 * <td>OK</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.2">Sec. 9.2</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.1">Sec. 10.2.1</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #CREATED_201}</td>
 * <td>201</td>
 * <td>Created</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.2">Sec. 9.2</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.2">Sec. 10.2.2</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #ACCEPTED_202}</td>
 * <td>202</td>
 * <td>Accepted</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.2">Sec. 9.2</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.3">Sec. 10.2.3</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NON_AUTHORITATIVE_INFORMATION_203}</td>
 * <td>203</td>
 * <td>Non Authoritative Information</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.4">Sec. 10.2.4</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NO_CONTENT_204}</td>
 * <td>204</td>
 * <td>No Content</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.2">Sec. 9.2</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.5">Sec. 10.2.5</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #RESET_CONTENT_205}</td>
 * <td>205</td>
 * <td>Reset Content</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.6">Sec. 10.2.6</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #PARTIAL_CONTENT_206}</td>
 * <td>206</td>
 * <td>Partial Content</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.2.7">Sec. 10.2.7</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #MULTI_STATUS_207}</td>
 * <td>207</td>
 * <td>Multi-Status</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.2">Sec. 10.2</a></td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>207</strike></td>
 * <td><strike>Partial Update OK</strike></td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://www.w3.org/Protocols/HTTP/1.1/draft-ietf-http-v11-spec-rev-01.txt"
 * >draft/01</a></td>
 * <td>&nbsp;</td>
 * </tr>
 *
 * <tr>
 * <td><strong><code>Redirection - 3xx</code></strong></td>
 * <td colspan="5">{@link #isRedirection(int)}</td>
 * </tr>
 *
 * <tr>
 * <td>{@link #MULTIPLE_CHOICES_300}</td>
 * <td>300</td>
 * <td>Multiple Choices</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.3">Sec. 9.3</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.1">Sec. 10.3.1</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #MOVED_PERMANENTLY_301}</td>
 * <td>301</td>
 * <td>Moved Permanently</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.3">Sec. 9.3</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.2">Sec. 10.3.2</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #MOVED_TEMPORARILY_302}</td>
 * <td>302</td>
 * <td>Moved Temporarily</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.3">Sec. 9.3</a></td>
 * <td>(now "<code>302 Found</code>")</td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #FOUND_302}</td>
 * <td>302</td>
 * <td>Found</td>
 * <td>(was "<code>302 Moved Temporarily</code>")</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.3">Sec. 10.3.3</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #SEE_OTHER_303}</td>
 * <td>303</td>
 * <td>See Other</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.4">Sec. 10.3.4</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NOT_MODIFIED_304}</td>
 * <td>304</td>
 * <td>Not Modified</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.3">Sec. 9.3</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.5">Sec. 10.3.5</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #USE_PROXY_305}</td>
 * <td>305</td>
 * <td>Use Proxy</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.6">Sec. 10.3.6</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td>306</td>
 * <td><em>(Unused)</em></td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.7">Sec. 10.3.7</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #TEMPORARY_REDIRECT_307}</td>
 * <td>307</td>
 * <td>Temporary Redirect</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.3.8">Sec. 10.3.8</a></td>
 * <td>&nbsp;</td>
 * </tr>
 *
 * <tr>
 * <td><strong><code>Client Error - 4xx</code></strong></td>
 * <td colspan="5">{@link #isClientError(int)}</td>
 * </tr>
 *
 * <tr>
 * <td>{@link #BAD_REQUEST_400}</td>
 * <td>400</td>
 * <td>Bad Request</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.4">Sec. 9.4</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.1">Sec. 10.4.1</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #UNAUTHORIZED_401}</td>
 * <td>401</td>
 * <td>Unauthorized</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.4">Sec. 9.4</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.2">Sec. 10.4.2</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #PAYMENT_REQUIRED_402}</td>
 * <td>402</td>
 * <td>Payment Required</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.4">Sec. 9.4</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.3">Sec. 10.4.3</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #FORBIDDEN_403}</td>
 * <td>403</td>
 * <td>Forbidden</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.4">Sec. 9.4</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.4">Sec. 10.4.4</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NOT_FOUND_404}</td>
 * <td>404</td>
 * <td>Not Found</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.4">Sec. 9.4</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.5">Sec. 10.4.5</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #METHOD_NOT_ALLOWED_405}</td>
 * <td>405</td>
 * <td>Method Not Allowed</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.6">Sec. 10.4.6</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NOT_ACCEPTABLE_406}</td>
 * <td>406</td>
 * <td>Not Acceptable</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.7">Sec. 10.4.7</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #PROXY_AUTHENTICATION_REQUIRED_407}</td>
 * <td>407</td>
 * <td>Proxy Authentication Required</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.8">Sec. 10.4.8</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #REQUEST_TIMEOUT_408}</td>
 * <td>408</td>
 * <td>Request Timeout</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.9">Sec. 10.4.9</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #CONFLICT_409}</td>
 * <td>409</td>
 * <td>Conflict</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.10">Sec. 10.4.10</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #GONE_410}</td>
 * <td>410</td>
 * <td>Gone</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.11">Sec. 10.4.11</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #LENGTH_REQUIRED_411}</td>
 * <td>411</td>
 * <td>Length Required</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.12">Sec. 10.4.12</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #PRECONDITION_FAILED_412}</td>
 * <td>412</td>
 * <td>Precondition Failed</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.13">Sec. 10.4.13</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #REQUEST_ENTITY_TOO_LARGE_413}</td>
 * <td>413</td>
 * <td>Request Entity Too Large</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.14">Sec. 10.4.14</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #REQUEST_URI_TOO_LONG_414}</td>
 * <td>414</td>
 * <td>Request-URI Too Long</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.15">Sec. 10.4.15</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #UNSUPPORTED_MEDIA_TYPE_415}</td>
 * <td>415</td>
 * <td>Unsupported Media Type</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.16">Sec. 10.4.16</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #REQUESTED_RANGE_NOT_SATISFIABLE_416}</td>
 * <td>416</td>
 * <td>Requested Range Not Satisfiable</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.17">Sec. 10.4.17</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #EXPECTATION_FAILED_417}</td>
 * <td>417</td>
 * <td>Expectation Failed</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.4.18">Sec. 10.4.18</a>
 * </td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>418</strike></td>
 * <td><strike>Reauthentication Required</strike></td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://tools.ietf.org/html/draft-ietf-http-v11-spec-rev-01#section-10.4.19"
 * >draft/01</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>418</strike></td>
 * <td><strike>Unprocessable Entity</strike></td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://tools.ietf.org/html/draft-ietf-webdav-protocol-05#section-10.3"
 * >draft/05</a></td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>419</strike></td>
 * <td><strike>Proxy Reauthentication Required</stike></td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://tools.ietf.org/html/draft-ietf-http-v11-spec-rev-01#section-10.4.20"
 * >draft/01</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>419</strike></td>
 * <td><strike>Insufficient Space on Resource</stike></td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://tools.ietf.org/html/draft-ietf-webdav-protocol-05#section-10.4"
 * >draft/05</a></td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td><strike>420</strike></td>
 * <td><strike>Method Failure</strike></td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href=
 * "http://tools.ietf.org/html/draft-ietf-webdav-protocol-05#section-10.5"
 * >draft/05</a></td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td>421</td>
 * <td><em>(Unused)</em></td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #UNPROCESSABLE_ENTITY_422}</td>
 * <td>422</td>
 * <td>Unprocessable Entity</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.3">Sec. 10.3</a></td>
 * </tr>
 * <tr>
 * <td>{@link #LOCKED_423}</td>
 * <td>423</td>
 * <td>Locked</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.4">Sec. 10.4</a></td>
 * </tr>
 * <tr>
 * <td>{@link #FAILED_DEPENDENCY_424}</td>
 * <td>424</td>
 * <td>Failed Dependency</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.5">Sec. 10.5</a></td>
 * </tr>
 *
 * <tr>
 * <td><strong><code>Server Error - 5xx</code></strong></td>
 * <td colspan="5">{@link #isServerError(int)}</td>
 * </tr>
 *
 * <tr>
 * <td>{@link #INTERNAL_SERVER_ERROR_500}</td>
 * <td>500</td>
 * <td>Internal Server Error</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.5">Sec. 9.5</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.1">Sec. 10.5.1</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #NOT_IMPLEMENTED_501}</td>
 * <td>501</td>
 * <td>Not Implemented</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.5">Sec. 9.5</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.2">Sec. 10.5.2</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #BAD_GATEWAY_502}</td>
 * <td>502</td>
 * <td>Bad Gateway</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.5">Sec. 9.5</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.3">Sec. 10.5.3</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #SERVICE_UNAVAILABLE_503}</td>
 * <td>503</td>
 * <td>Service Unavailable</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc1945#section-9.5">Sec. 9.5</a></td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.4">Sec. 10.5.4</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #GATEWAY_TIMEOUT_504}</td>
 * <td>504</td>
 * <td>Gateway Timeout</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.5">Sec. 10.5.5</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #HTTP_VERSION_NOT_SUPPORTED_505}</td>
 * <td>505</td>
 * <td>HTTP Version Not Supported</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2616#section-10.5.6">Sec. 10.5.6</a></td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>&nbsp;</td>
 * <td>506</td>
 * <td><em>(Unused)</em></td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * </tr>
 * <tr>
 * <td>{@link #INSUFFICIENT_STORAGE_507}</td>
 * <td>507</td>
 * <td>Insufficient Storage</td>
 * <td>&nbsp;</td>
 * <td>&nbsp;</td>
 * <td>
 * <a href="http://tools.ietf.org/html/rfc2518#section-10.6">Sec. 10.6</a></td>
 * </tr>
 *
 * </table>
 *
 * @version $Id$
 */
public class HttpStatus
{
    public final static int CONTINUE_100 = 100;
    public final static int SWITCHING_PROTOCOLS_101 = 101;
    public final static int PROCESSING_102 = 102;

    public final static int OK_200 = 200;
    public final static int CREATED_201 = 201;
    public final static int ACCEPTED_202 = 202;
    public final static int NON_AUTHORITATIVE_INFORMATION_203 = 203;
    public final static int NO_CONTENT_204 = 204;
    public final static int RESET_CONTENT_205 = 205;
    public final static int PARTIAL_CONTENT_206 = 206;
    public final static int MULTI_STATUS_207 = 207;

    public final static int MULTIPLE_CHOICES_300 = 300;
    public final static int MOVED_PERMANENTLY_301 = 301;
    public final static int MOVED_TEMPORARILY_302 = 302;
    public final static int FOUND_302 = 302;
    public final static int SEE_OTHER_303 = 303;
    public final static int NOT_MODIFIED_304 = 304;
    public final static int USE_PROXY_305 = 305;
    public final static int TEMPORARY_REDIRECT_307 = 307;

    public final static int BAD_REQUEST_400 = 400;
    public final static int UNAUTHORIZED_401 = 401;
    public final static int PAYMENT_REQUIRED_402 = 402;
    public final static int FORBIDDEN_403 = 403;
    public final static int NOT_FOUND_404 = 404;
    public final static int METHOD_NOT_ALLOWED_405 = 405;
    public final static int NOT_ACCEPTABLE_406 = 406;
    public final static int PROXY_AUTHENTICATION_REQUIRED_407 = 407;
    public final static int REQUEST_TIMEOUT_408 = 408;
    public final static int CONFLICT_409 = 409;
    public final static int GONE_410 = 410;
    public final static int LENGTH_REQUIRED_411 = 411;
    public final static int PRECONDITION_FAILED_412 = 412;
    public final static int REQUEST_ENTITY_TOO_LARGE_413 = 413;
    public final static int REQUEST_URI_TOO_LONG_414 = 414;
    public final static int UNSUPPORTED_MEDIA_TYPE_415 = 415;
    public final static int REQUESTED_RANGE_NOT_SATISFIABLE_416 = 416;
    public final static int EXPECTATION_FAILED_417 = 417;
    public final static int UNPROCESSABLE_ENTITY_422 = 422;
    public final static int LOCKED_423 = 423;
    public final static int FAILED_DEPENDENCY_424 = 424;

    public final static int INTERNAL_SERVER_ERROR_500 = 500;
    public final static int NOT_IMPLEMENTED_501 = 501;
    public final static int BAD_GATEWAY_502 = 502;
    public final static int SERVICE_UNAVAILABLE_503 = 503;
    public final static int GATEWAY_TIMEOUT_504 = 504;
    public final static int HTTP_VERSION_NOT_SUPPORTED_505 = 505;
    public final static int INSUFFICIENT_STORAGE_507 = 507;

    public static final int MAX_CODE = 507;


    private static final Code[] codeMap = new Code[MAX_CODE+1];

    static
    {
        for (Code code : Code.values())
        {
            codeMap[code._code] = code;
        }
    }


    public enum Code
    {
        /*
         * --------------------------------------------------------------------
         * Informational messages in 1xx series. As defined by ... RFC 1945 -
         * HTTP/1.0 RFC 2616 - HTTP/1.1 RFC 2518 - WebDAV
         */

        /** <code>100 Continue</code> */
        CONTINUE(CONTINUE_100, "Continue"),
        /** <code>101 Switching Protocols</code> */
        SWITCHING_PROTOCOLS(SWITCHING_PROTOCOLS_101, "Switching Protocols"),
        /** <code>102 Processing</code> */
        PROCESSING(PROCESSING_102, "Processing"),

        /*
         * --------------------------------------------------------------------
         * Success messages in 2xx series. As defined by ... RFC 1945 - HTTP/1.0
         * RFC 2616 - HTTP/1.1 RFC 2518 - WebDAV
         */

        /** <code>200 OK</code> */
        OK(OK_200, "OK"),
        /** <code>201 Created</code> */
        CREATED(CREATED_201, "Created"),
        /** <code>202 Accepted</code> */
        ACCEPTED(ACCEPTED_202, "Accepted"),
        /** <code>203 Non Authoritative Information</code> */
        NON_AUTHORITATIVE_INFORMATION(NON_AUTHORITATIVE_INFORMATION_203, "Non Authoritative Information"),
        /** <code>204 No Content</code> */
        NO_CONTENT(NO_CONTENT_204, "No Content"),
        /** <code>205 Reset Content</code> */
        RESET_CONTENT(RESET_CONTENT_205, "Reset Content"),
        /** <code>206 Partial Content</code> */
        PARTIAL_CONTENT(PARTIAL_CONTENT_206, "Partial Content"),
        /** <code>207 Multi-Status</code> */
        MULTI_STATUS(MULTI_STATUS_207, "Multi-Status"),

        /*
         * --------------------------------------------------------------------
         * Redirection messages in 3xx series. As defined by ... RFC 1945 -
         * HTTP/1.0 RFC 2616 - HTTP/1.1
         */

        /** <code>300 Mutliple Choices</code> */
        MULTIPLE_CHOICES(MULTIPLE_CHOICES_300, "Multiple Choices"),
        /** <code>301 Moved Permanently</code> */
        MOVED_PERMANENTLY(MOVED_PERMANENTLY_301, "Moved Permanently"),
        /** <code>302 Moved Temporarily</code> */
        MOVED_TEMPORARILY(MOVED_TEMPORARILY_302, "Moved Temporarily"),
        /** <code>302 Found</code> */
        FOUND(FOUND_302, "Found"),
        /** <code>303 See Other</code> */
        SEE_OTHER(SEE_OTHER_303, "See Other"),
        /** <code>304 Not Modified</code> */
        NOT_MODIFIED(NOT_MODIFIED_304, "Not Modified"),
        /** <code>305 Use Proxy</code> */
        USE_PROXY(USE_PROXY_305, "Use Proxy"),
        /** <code>307 Temporary Redirect</code> */
        TEMPORARY_REDIRECT(TEMPORARY_REDIRECT_307, "Temporary Redirect"),

        /*
         * --------------------------------------------------------------------
         * Client Error messages in 4xx series. As defined by ... RFC 1945 -
         * HTTP/1.0 RFC 2616 - HTTP/1.1 RFC 2518 - WebDAV
         */

        /** <code>400 Bad Request</code> */
        BAD_REQUEST(BAD_REQUEST_400, "Bad Request"),
        /** <code>401 Unauthorized</code> */
        UNAUTHORIZED(UNAUTHORIZED_401, "Unauthorized"),
        /** <code>402 Payment Required</code> */
        PAYMENT_REQUIRED(PAYMENT_REQUIRED_402, "Payment Required"),
        /** <code>403 Forbidden</code> */
        FORBIDDEN(FORBIDDEN_403, "Forbidden"),
        /** <code>404 Not Found</code> */
        NOT_FOUND(NOT_FOUND_404, "Not Found"),
        /** <code>405 Method Not Allowed</code> */
        METHOD_NOT_ALLOWED(METHOD_NOT_ALLOWED_405, "Method Not Allowed"),
        /** <code>406 Not Acceptable</code> */
        NOT_ACCEPTABLE(NOT_ACCEPTABLE_406, "Not Acceptable"),
        /** <code>407 Proxy Authentication Required</code> */
        PROXY_AUTHENTICATION_REQUIRED(PROXY_AUTHENTICATION_REQUIRED_407, "Proxy Authentication Required"),
        /** <code>408 Request Timeout</code> */
        REQUEST_TIMEOUT(REQUEST_TIMEOUT_408, "Request Timeout"),
        /** <code>409 Conflict</code> */
        CONFLICT(CONFLICT_409, "Conflict"),
        /** <code>410 Gone</code> */
        GONE(GONE_410, "Gone"),
        /** <code>411 Length Required</code> */
        LENGTH_REQUIRED(LENGTH_REQUIRED_411, "Length Required"),
        /** <code>412 Precondition Failed</code> */
        PRECONDITION_FAILED(PRECONDITION_FAILED_412, "Precondition Failed"),
        /** <code>413 Request Entity Too Large</code> */
        REQUEST_ENTITY_TOO_LARGE(REQUEST_ENTITY_TOO_LARGE_413, "Request Entity Too Large"),
        /** <code>414 Request-URI Too Long</code> */
        REQUEST_URI_TOO_LONG(REQUEST_URI_TOO_LONG_414, "Request-URI Too Long"),
        /** <code>415 Unsupported Media Type</code> */
        UNSUPPORTED_MEDIA_TYPE(UNSUPPORTED_MEDIA_TYPE_415, "Unsupported Media Type"),
        /** <code>416 Requested Range Not Satisfiable</code> */
        REQUESTED_RANGE_NOT_SATISFIABLE(REQUESTED_RANGE_NOT_SATISFIABLE_416, "Requested Range Not Satisfiable"),
        /** <code>417 Expectation Failed</code> */
        EXPECTATION_FAILED(EXPECTATION_FAILED_417, "Expectation Failed"),
        /** <code>422 Unprocessable Entity</code> */
        UNPROCESSABLE_ENTITY(UNPROCESSABLE_ENTITY_422, "Unprocessable Entity"),
        /** <code>423 Locked</code> */
        LOCKED(LOCKED_423, "Locked"),
        /** <code>424 Failed Dependency</code> */
        FAILED_DEPENDENCY(FAILED_DEPENDENCY_424, "Failed Dependency"),

        /*
         * --------------------------------------------------------------------
         * Server Error messages in 5xx series. As defined by ... RFC 1945 -
         * HTTP/1.0 RFC 2616 - HTTP/1.1 RFC 2518 - WebDAV
         */

        /** <code>500 Server Error</code> */
        INTERNAL_SERVER_ERROR(INTERNAL_SERVER_ERROR_500, "Server Error"),
        /** <code>501 Not Implemented</code> */
        NOT_IMPLEMENTED(NOT_IMPLEMENTED_501, "Not Implemented"),
        /** <code>502 Bad Gateway</code> */
        BAD_GATEWAY(BAD_GATEWAY_502, "Bad Gateway"),
        /** <code>503 Service Unavailable</code> */
        SERVICE_UNAVAILABLE(SERVICE_UNAVAILABLE_503, "Service Unavailable"),
        /** <code>504 Gateway Timeout</code> */
        GATEWAY_TIMEOUT(GATEWAY_TIMEOUT_504, "Gateway Timeout"),
        /** <code>505 HTTP Version Not Supported</code> */
        HTTP_VERSION_NOT_SUPPORTED(HTTP_VERSION_NOT_SUPPORTED_505, "HTTP Version Not Supported"),
        /** <code>507 Insufficient Storage</code> */
        INSUFFICIENT_STORAGE(INSUFFICIENT_STORAGE_507, "Insufficient Storage");

        private final int _code;
        private final String _message;

        private Code(int code, String message)
        {
            this._code = code;
            _message=message;
        }

        public int getCode()
        {
            return _code;
        }

        public String getMessage()
        {
            return _message;
        }


        public boolean equals(int code)
        {
            return (this._code == code);
        }

        @Override
        public String toString()
        {
            return String.format("[%03d %s]",this._code,this.getMessage());
        }

        /**
         * Simple test against an code to determine if it falls into the
         * <code>Informational</code> message category as defined in the <a
         * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>,
         * and <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 -
         * HTTP/1.1</a>.
         *
         * @return true if within range of codes that belongs to
         *         <code>Informational</code> messages.
         */
        public boolean isInformational()
        {
            return HttpStatus.isInformational(this._code);
        }

        /**
         * Simple test against an code to determine if it falls into the
         * <code>Success</code> message category as defined in the <a
         * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>,
         * and <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 -
         * HTTP/1.1</a>.
         *
         * @return true if within range of codes that belongs to
         *         <code>Success</code> messages.
         */
        public boolean isSuccess()
        {
            return HttpStatus.isSuccess(this._code);
        }

        /**
         * Simple test against an code to determine if it falls into the
         * <code>Redirection</code> message category as defined in the <a
         * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>,
         * and <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 -
         * HTTP/1.1</a>.
         *
         * @return true if within range of codes that belongs to
         *         <code>Redirection</code> messages.
         */
        public boolean isRedirection()
        {
            return HttpStatus.isRedirection(this._code);
        }

        /**
         * Simple test against an code to determine if it falls into the
         * <code>Client Error</code> message category as defined in the <a
         * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>,
         * and <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 -
         * HTTP/1.1</a>.
         *
         * @return true if within range of codes that belongs to
         *         <code>Client Error</code> messages.
         */
        public boolean isClientError()
        {
            return HttpStatus.isClientError(this._code);
        }

        /**
         * Simple test against an code to determine if it falls into the
         * <code>Server Error</code> message category as defined in the <a
         * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>,
         * and <a href="http://tools.ietf.org/html/rfc2616">RFC 2616 -
         * HTTP/1.1</a>.
         *
         * @return true if within range of codes that belongs to
         *         <code>Server Error</code> messages.
         */
        public boolean isServerError()
        {
            return HttpStatus.isServerError(this._code);
        }
    }


    /**
     * Get the HttpStatusCode for a specific code
     *
     * @param code
     *            the code to lookup.
     * @return the {@link HttpStatus} if found, or null if not found.
     */
    public static Code getCode(int code)
    {
        if (code <= MAX_CODE)
        {
            return codeMap[code];
        }
        return null;
    }

    /**
     * Get the status message for a specific code.
     *
     * @param code
     *            the code to look up
     * @return the specific message, or the code number itself if code
     *         does not match known list.
     */
    public static String getMessage(int code)
    {
        Code codeEnum = getCode(code);
        if (codeEnum != null)
        {
            return codeEnum.getMessage();
        }
        else
        {
            return Integer.toString(code);
        }
    }

    /**
     * Simple test against an code to determine if it falls into the
     * <code>Informational</code> message category as defined in the <a
     * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>, and <a
     * href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a>.
     *
     * @param code
     *            the code to test.
     * @return true if within range of codes that belongs to
     *         <code>Informational</code> messages.
     */
    public static boolean isInformational(int code)
    {
        return ((100 <= code) && (code <= 199));
    }

    /**
     * Simple test against an code to determine if it falls into the
     * <code>Success</code> message category as defined in the <a
     * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>, and <a
     * href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a>.
     *
     * @param code
     *            the code to test.
     * @return true if within range of codes that belongs to
     *         <code>Success</code> messages.
     */
    public static boolean isSuccess(int code)
    {
        return ((200 <= code) && (code <= 299));
    }

    /**
     * Simple test against an code to determine if it falls into the
     * <code>Redirection</code> message category as defined in the <a
     * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>, and <a
     * href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a>.
     *
     * @param code
     *            the code to test.
     * @return true if within range of codes that belongs to
     *         <code>Redirection</code> messages.
     */
    public static boolean isRedirection(int code)
    {
        return ((300 <= code) && (code <= 399));
    }

    /**
     * Simple test against an code to determine if it falls into the
     * <code>Client Error</code> message category as defined in the <a
     * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>, and <a
     * href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a>.
     *
     * @param code
     *            the code to test.
     * @return true if within range of codes that belongs to
     *         <code>Client Error</code> messages.
     */
    public static boolean isClientError(int code)
    {
        return ((400 <= code) && (code <= 499));
    }

    /**
     * Simple test against an code to determine if it falls into the
     * <code>Server Error</code> message category as defined in the <a
     * href="http://tools.ietf.org/html/rfc1945">RFC 1945 - HTTP/1.0</a>, and <a
     * href="http://tools.ietf.org/html/rfc2616">RFC 2616 - HTTP/1.1</a>.
     *
     * @param code
     *            the code to test.
     * @return true if within range of codes that belongs to
     *         <code>Server Error</code> messages.
     */
    public static boolean isServerError(int code)
    {
        return ((500 <= code) && (code <= 599));
    }
}
