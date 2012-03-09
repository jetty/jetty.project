/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.eclipse.jetty.spdy;

import org.eclipse.jetty.spdy.api.SPDY;

public class CompressionDictionary
{
    private static final byte[] DICTIONARY_V2 = ("" +
            "optionsgetheadpostputdeletetraceacceptaccept-charsetaccept-encodingaccept-" +
            "languageauthorizationexpectfromhostif-modified-sinceif-matchif-none-matchi" +
            "f-rangeif-unmodifiedsincemax-forwardsproxy-authorizationrangerefererteuser" +
            "-agent10010120020120220320420520630030130230330430530630740040140240340440" +
            "5406407408409410411412413414415416417500501502503504505accept-rangesageeta" +
            "glocationproxy-authenticatepublicretry-afterservervarywarningwww-authentic" +
            "ateallowcontent-basecontent-encodingcache-controlconnectiondatetrailertran" +
            "sfer-encodingupgradeviawarningcontent-languagecontent-lengthcontent-locati" +
            "oncontent-md5content-rangecontent-typeetagexpireslast-modifiedset-cookieMo" +
            "ndayTuesdayWednesdayThursdayFridaySaturdaySundayJanFebMarAprMayJunJulAugSe" +
            "pOctNovDecchunkedtext/htmlimage/pngimage/jpgimage/gifapplication/xmlapplic" +
            "ation/xhtmltext/plainpublicmax-agecharset=iso-8859-1utf-8gzipdeflateHTTP/1" +
            ".1statusversionurl" +
            // Must be NUL terminated
            "\u0000").getBytes();

    private static final byte[] DICTIONARY_V3 = ("" +
            "\u0000\u0000\u0000\u0007options\u0000\u0000\u0000\u0004head\u0000\u0000\u0000\u0004post" +
            "\u0000\u0000\u0000\u0003put\u0000\u0000\u0000\u0006delete\u0000\u0000\u0000\u0005trace" +
            "\u0000\u0000\u0000\u0006accept\u0000\u0000\u0000\u000Eaccept-charset" +
            "\u0000\u0000\u0000\u000Faccept-encoding\u0000\u0000\u0000\u000Faccept-language" +
            "\u0000\u0000\u0000\raccept-ranges\u0000\u0000\u0000\u0003age\u0000\u0000\u0000\u0005allow" +
            "\u0000\u0000\u0000\rauthorization\u0000\u0000\u0000\rcache-control" +
            "\u0000\u0000\u0000\nconnection\u0000\u0000\u0000\fcontent-base\u0000\u0000\u0000\u0010content-encoding" +
            "\u0000\u0000\u0000\u0010content-language\u0000\u0000\u0000\u000Econtent-length" +
            "\u0000\u0000\u0000\u0010content-location\u0000\u0000\u0000\u000Bcontent-md5" +
            "\u0000\u0000\u0000\rcontent-range\u0000\u0000\u0000\fcontent-type\u0000\u0000\u0000\u0004date" +
            "\u0000\u0000\u0000\u0004etag\u0000\u0000\u0000\u0006expect\u0000\u0000\u0000\u0007expires" +
            "\u0000\u0000\u0000\u0004from\u0000\u0000\u0000\u0004host\u0000\u0000\u0000\bif-match" +
            "\u0000\u0000\u0000\u0011if-modified-since\u0000\u0000\u0000\rif-none-match\u0000\u0000\u0000\bif-range" +
            "\u0000\u0000\u0000\u0013if-unmodified-since\u0000\u0000\u0000\rlast-modified" +
            "\u0000\u0000\u0000\blocation\u0000\u0000\u0000\fmax-forwards\u0000\u0000\u0000\u0006pragma" +
            "\u0000\u0000\u0000\u0012proxy-authenticate\u0000\u0000\u0000\u0013proxy-authorization" +
            "\u0000\u0000\u0000\u0005range\u0000\u0000\u0000\u0007referer\u0000\u0000\u0000\u000Bretry-after" +
            "\u0000\u0000\u0000\u0006server\u0000\u0000\u0000\u0002te\u0000\u0000\u0000\u0007trailer" +
            "\u0000\u0000\u0000\u0011transfer-encoding\u0000\u0000\u0000\u0007upgrade\u0000\u0000\u0000\nuser-agent" +
            "\u0000\u0000\u0000\u0004vary\u0000\u0000\u0000\u0003via\u0000\u0000\u0000\u0007warning" +
            "\u0000\u0000\u0000\u0010www-authenticate\u0000\u0000\u0000\u0006method\u0000\u0000\u0000\u0003get" +
            "\u0000\u0000\u0000\u0006status\u0000\u0000\u0000\u0006200 OK\u0000\u0000\u0000\u0007version" +
            "\u0000\u0000\u0000\bHTTP/1.1\u0000\u0000\u0000\u0003url\u0000\u0000\u0000\u0006public" +
            "\u0000\u0000\u0000\nset-cookie\u0000\u0000\u0000\nkeep-alive\u0000\u0000\u0000\u0006origin" +
            "100101201202205206300302303304305306307402405406407408409410411412413414415416417502504505" +
            "203 Non-Authoritative Information204 No Content301 Moved Permanently400 Bad Request401 Unauthorized" +
            "403 Forbidden404 Not Found500 Internal Server Error501 Not Implemented503 Service Unavailable" +
            "Jan Feb Mar Apr May Jun Jul Aug Sept Oct Nov Dec 00:00:00 Mon, Tue, Wed, Thu, Fri, Sat, Sun, GMT" +
            "chunked,text/html,image/png,image/jpg,image/gif,application/xml,application/xhtml+xml,text/plain," +
            "text/javascript,publicprivatemax-age=gzip,deflate,sdchcharset=utf-8charset=iso-8859-1,utf-,*,enq=0.")
            .getBytes();

    public static byte[] get(short version)
    {
        switch (version)
        {
            case SPDY.V2:
                return DICTIONARY_V2;
            case SPDY.V3:
                return DICTIONARY_V3;
            default:
                throw new IllegalStateException();
        }
    }
}
