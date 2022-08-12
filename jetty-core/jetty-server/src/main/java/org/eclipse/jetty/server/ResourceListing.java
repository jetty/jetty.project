//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jetty.util.Fields;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.URIUtil;
import org.eclipse.jetty.util.UrlEncoded;
import org.eclipse.jetty.util.resource.PathCollators;
import org.eclipse.jetty.util.resource.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility methods to generate a List of paths.
 *
 * TODO: add XML and JSON versions?
 */
public class ResourceListing
{
    public static final Logger LOG = LoggerFactory.getLogger(ResourceListing.class);

    /**
     * Convert the Resource directory into an HTML directory listing.
     *
     * @param resource the resource to build the listing from
     * @param base The base URL
     * @param parent True if the parent directory should be included
     * @param query query params
     * @return the HTML as String
     */
    public static String getAsHTML(Resource resource, String base, boolean parent, String query)
    {
        // This method doesn't check aliases, so it is OK to canonicalize here.
        base = URIUtil.normalizePath(base);
        if (base == null || !resource.isDirectory())
            return null;
        Path path = resource.getPath();
        if (path == null) // Should never happen, as new Resource contract is that all Resources are a Path.
            return null;

        List<Path> listing = null;
        try (Stream<Path> listStream = Files.list(resource.getPath()))
        {

            listing = listStream.collect(Collectors.toCollection(ArrayList::new));
        }
        catch (IOException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("Unable to get Directory Listing for: {}", resource, e);
        }

        if (listing == null)
        {
            return null;
        }

        boolean sortOrderAscending = true;
        String sortColumn = "N"; // name (or "M" for Last Modified, or "S" for Size)

        // check for query
        if (query != null)
        {
            Fields params = new Fields();
            UrlEncoded.decodeUtf8To(query, 0, query.length(), params);

            String paramO = params.getValue("O");
            String paramC = params.getValue("C");
            if (StringUtil.isNotBlank(paramO))
            {
                switch (paramO)
                {
                    case "A" -> sortOrderAscending = true;
                    case "D" -> sortOrderAscending = false;
                }
            }
            if (StringUtil.isNotBlank(paramC))
            {
                if (paramC.equals("N") || paramC.equals("M") || paramC.equals("S"))
                {
                    sortColumn = paramC;
                }
            }
        }

        // Perform sort
        if (sortColumn.equals("M"))
        {
            listing.sort(PathCollators.byLastModified(sortOrderAscending));
        }
        else if (sortColumn.equals("S"))
        {
            listing.sort(PathCollators.bySize(sortOrderAscending));
        }
        else
        {
            listing.sort(PathCollators.byName(sortOrderAscending));
        }

        String decodedBase = URIUtil.decodePath(base);
        String title = "Directory: " + deTag(decodedBase);

        StringBuilder buf = new StringBuilder(4096);

        // Doctype Declaration (HTML5)
        buf.append("<!DOCTYPE html>\n");
        buf.append("<html lang=\"en\">\n");

        // HTML Header
        buf.append("<head>\n");
        buf.append("<meta charset=\"utf-8\">\n");
        buf.append("<link href=\"jetty-dir.css\" rel=\"stylesheet\" />\n");
        buf.append("<title>");
        buf.append(title);
        buf.append("</title>\n");
        buf.append("</head>\n");

        // HTML Body
        buf.append("<body>\n");
        buf.append("<h1 class=\"title\">").append(title).append("</h1>\n");

        // HTML Table
        final String ARROW_DOWN = "&nbsp; &#8681;";
        final String ARROW_UP = "&nbsp; &#8679;";

        buf.append("<table class=\"listing\">\n");
        buf.append("<thead>\n");

        String arrow = "";
        String order = "A";
        if (sortColumn.equals("N"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<tr><th class=\"name\"><a href=\"?C=N&O=").append(order).append("\">");
        buf.append("Name").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("M"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }

        buf.append("<th class=\"lastmodified\"><a href=\"?C=M&O=").append(order).append("\">");
        buf.append("Last Modified").append(arrow);
        buf.append("</a></th>");

        arrow = "";
        order = "A";
        if (sortColumn.equals("S"))
        {
            if (sortOrderAscending)
            {
                order = "D";
                arrow = ARROW_UP;
            }
            else
            {
                order = "A";
                arrow = ARROW_DOWN;
            }
        }
        buf.append("<th class=\"size\"><a href=\"?C=S&O=").append(order).append("\">");
        buf.append("Size").append(arrow);
        buf.append("</a></th></tr>\n");
        buf.append("</thead>\n");

        buf.append("<tbody>\n");

        String encodedBase = hrefEncodeURI(base);

        if (parent)
        {
            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");
            buf.append(URIUtil.addPaths(encodedBase, "../"));
            buf.append("\">Parent Directory</a></td>");
            // Last Modified
            buf.append("<td class=\"lastmodified\">-</td>");
            // Size
            buf.append("<td>-</td>");
            buf.append("</tr>\n");
        }

        DateFormat dfmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM);

        for (Path item : listing)
        {
            Path fileName = item.getFileName();
            if (fileName == null)
            {
                continue; // skip
            }

            String name = fileName.toString();
            if (StringUtil.isBlank(name))
            {
                return null;
            }

            if (Files.isDirectory(item))
            {
                name += URIUtil.SLASH;
            }

            // Name
            buf.append("<tr><td class=\"name\"><a href=\"");

            String href = URIUtil.addEncodedPaths(encodedBase, URIUtil.encodePath(name));
            buf.append(href);
            buf.append("\">");
            buf.append(deTag(name));
            buf.append("&nbsp;");
            buf.append("</a></td>");

            // Last Modified
            buf.append("<td class=\"lastmodified\">");

            try
            {
                FileTime lastModified = Files.getLastModifiedTime(item, LinkOption.NOFOLLOW_LINKS);
                buf.append(dfmt.format(new Date(lastModified.toMillis())));
            }
            catch (IOException ignore)
            {
                // do nothing (lastModifiedTime not supported by this file system)
            }
            buf.append("&nbsp;</td>");

            // Size
            buf.append("<td class=\"size\">");

            try
            {
                long length = Files.size(item);
                if (length >= 0)
                {
                    buf.append(String.format("%,d bytes", length));
                }
            }
            catch (IOException ignore)
            {
                // do nothing (size not supported by this file system)
            }
            buf.append("&nbsp;</td></tr>\n");
        }
        buf.append("</tbody>\n");
        buf.append("</table>\n");
        buf.append("</body></html>\n");

        return buf.toString();
    }

    /**
     * Encode any characters that could break the URI string in an HREF.
     * Such as <a href="/path/to;<script>Window.alert("XSS"+'%20'+"here");</script>">Link</a>
     *
     * The above example would parse incorrectly on various browsers as the "<" or '"' characters
     * would end the href attribute value string prematurely.
     *
     * @param raw the raw text to encode.
     * @return the defanged text.
     */
    private static String hrefEncodeURI(String raw)
    {
        StringBuffer buf = null;

        loop:
        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '\'':
                case '"':
                case '<':
                case '>':
                    buf = new StringBuffer(raw.length() << 1);
                    break loop;
                default:
                    break;
            }
        }
        if (buf == null)
            return raw;

        for (int i = 0; i < raw.length(); i++)
        {
            char c = raw.charAt(i);
            switch (c)
            {
                case '"' -> buf.append("%22");
                case '\'' -> buf.append("%27");
                case '<' -> buf.append("%3C");
                case '>' -> buf.append("%3E");
                default -> buf.append(c);
            }
        }

        return buf.toString();
    }

    private static String deTag(String raw)
    {
        return StringUtil.sanitizeXmlString(raw);
    }
}
