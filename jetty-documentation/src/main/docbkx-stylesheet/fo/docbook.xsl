<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="1.0"
  xmlns:jfetch="java:org.eclipse.jetty.xslt.tools.JavaSourceFetchExtension"
  xmlns:fetch="java:org.eclipse.jetty.xslt.tools.SourceFetchExtension"
  xmlns:d="http://docbook.org/ns/docbook"
  xmlns:fo="http://www.w3.org/1999/XSL/Format"
  xmlns:sverb="http://nwalsh.com/xslt/ext/com.nwalsh.saxon.Verbatim"
  xmlns:xverb="com.nwalsh.xalan.Verbatim"
  xmlns:lxslt="http://xml.apache.org/xslt"
  xmlns:exsl="http://exslt.org/common"
  xmlns:xl="http://www.w3.org/1999/xlink"
  exclude-result-prefixes="sverb xverb lxslt exsl d"
>

  <!-- imports the original docbook stylesheet -->
  <xsl:import href="urn:docbkx:stylesheet"/>

  <!-- set bellow all your custom xsl configuration -->
  <xsl:import href="urn:docbkx:stylesheet/highlight.xsl"/>
  <xsl:param name="highlight.source" select="1"/>

  <xsl:param name="img.src.path">target/docbkx/pdf/jetty/</xsl:param>
  <xsl:param name="keep.relative.image.uris">false</xsl:param>
  
  <xsl:param name="admon.graphics.extension">.svg</xsl:param>

  <xsl:attribute-set name="table.table.properties">
    <xsl:attribute name="hyphenate">true</xsl:attribute>
  </xsl:attribute-set>

  <xsl:param name="hyphenate.verbatim" select="0"></xsl:param>
  <xsl:param name="hyphenate.verbatim.characters">.,: </xsl:param>

  <xsl:attribute-set name="monospace.verbatim.properties" use-attribute-sets="verbatim.properties monospace.properties">
    <xsl:attribute name="wrap-option">wrap</xsl:attribute>
    <xsl:attribute name="hyphenation-character">\</xsl:attribute>
    <xsl:attribute name="font-size">
      <xsl:choose>
        <xsl:when test="processing-instruction('db-font-size')">
          <xsl:value-of
           select="processing-instruction('db-font-size')"/>
        </xsl:when>
        <xsl:otherwise>75%</xsl:otherwise>
      </xsl:choose>
    </xsl:attribute>
  </xsl:attribute-set>

  <xsl:param name="shade.verbatim" select="1"/>

  <xsl:attribute-set name="shade.verbatim.style">
    <xsl:attribute name="background-color">#E0E0E0</xsl:attribute>
    <xsl:attribute name="border-width">0.5pt</xsl:attribute>
    <xsl:attribute name="border-style">solid</xsl:attribute>
    <xsl:attribute name="border-color">#575757</xsl:attribute>
    <xsl:attribute name="padding">3pt</xsl:attribute>
  </xsl:attribute-set>

 <xsl:template match="d:programlisting">
        <fo:block language="en" 
            wrap-option="wrap" 
            linefeed-treatment="preserve"
            font-family="monospace" 
            white-space-collapse="false" 
            white-space-treatment="preserve">
            <xsl:apply-templates></xsl:apply-templates>
        </fo:block>
    </xsl:template>

  <!--
  this settings lets our variable lists render out with term stacking on top of the listitems
  -->
  <xsl:param name="variablelist.as.blocks">1</xsl:param>

  <!--
  Override the default term presentation to make terms bold
  -->
<xsl:template match="d:varlistentry" mode="vl.as.blocks">
  <xsl:variable name="id"><xsl:call-template name="object.id"/></xsl:variable>

  <fo:block id="{$id}" xsl:use-attribute-sets="list.item.spacing"  
      keep-together.within-column="always" 
      keep-with-next.within-column="always">
      <fo:inline font-weight="bold">
        <xsl:apply-templates select="d:term"/>
      </fo:inline>
  </fo:block>

  <fo:block margin-left="0.25in">
    <xsl:apply-templates select="d:listitem"/>
  </fo:block>
</xsl:template>

<xsl:attribute-set name="formal.object.properties">
<xsl:attribute name="keep-together.within-column">auto</xsl:attribute>
</xsl:attribute-set>

<!-- tweak the generation of toc generation -->

  <xsl:param name="generate.section.toc.level" select="1"/>
  <xsl:param name="toc.section.depth" select="1"/>
  <xsl:param name="generate.toc">
    appendix  toc,title
    article/appendix  nop
    article   toc,title
    book      toc,title,figure,table,example,equation
    chapter   toc,title
    part      toc,title
    preface   toc,title
    qandadiv  toc
    qandaset  toc
    reference toc,title
    sect1     toc
    sect2     toc
    sect3     toc
    sect4     toc
    sect5     toc
    section   toc
    set       toc,title
  </xsl:param>

<xsl:param name="ulink.hyphenate.chars">., </xsl:param>
<xsl:param name="ulink.hyphenate">&#x200B;</xsl:param>

<xsl:template match="entry//text()">
 <xsl:call-template name="hyphenate-url">
   <xsl:with-param name="url" select="."/>
 </xsl:call-template>
</xsl:template>

<xsl:template match="programlisting//text()">
 <xsl:call-template name="hyphenate-url">
   <xsl:with-param name="url" select="."/>
 </xsl:call-template>
</xsl:template>

<xsl:template match="screen//text()">
 <xsl:call-template name="hyphenate-url">
   <xsl:with-param name="url" select="."/>
 </xsl:call-template>
</xsl:template>


<xsl:template name="book.titlepage.recto">
  

<fo:block text-align="center"><fo:external-graphic src="url(target/docbkx/pdf/jetty/images/jetty-logo-shadow.png)" width="540px" height="auto" content-width="scale-to-fit" content-height="scale-to-fit" content-type="content-type:image/png" text-align="center"/></fo:block>
<fo:block text-align="center" font-size="24pt" border-width="10mm">The Definitive Reference</fo:block>


  <xsl:apply-templates mode="book.titlepage.recto.auto.mode" select="d:bookinfo/d:authorgroup"/>
  <xsl:apply-templates mode="book.titlepage.recto.auto.mode" select="d:info/d:authorgroup"/>
  
  <xsl:apply-templates mode="book.titlepage.mode" select="d:info/d:revhistory"/>

</xsl:template>

<!--
<xsl:param name="page.height.portrait">9in</xsl:param>
<xsl:param name="page.width.portrait">7in</xsl:param>
<xsl:param name="page.margin.inner">0.75in</xsl:param>
<xsl:param name="page.margin.outer">0.50in</xsl:param>
<xsl:param name="page.margin.top">0.17in</xsl:param>
<xsl:param name="page.margin.bottom">0.50in</xsl:param>
-->
<xsl:template name="book.titlepage.before.verso"/>
<xsl:template name="book.titlepage.verso"/>

  <xsl:template match="d:programlisting">
    <xsl:variable name="id">
      <xsl:call-template name="object.id"/>
    </xsl:variable>

    <xsl:variable name="content">
      <xsl:choose>
        <xsl:when test="@language='rjava'">
          <xsl:variable name="filename" select="./d:filename"/>
          <xsl:variable name="methodname" select="./d:methodname"/>
          <xsl:value-of select="jfetch:fetch($filename,$methodname)"/>
        </xsl:when>
        <xsl:when test="@language='rxml'">
          <xsl:variable name="filename" select="./d:filename"/>
          <xsl:value-of select="fetch:fetch($filename)"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:call-template name="apply-highlighting"/>
        </xsl:otherwise>
      </xsl:choose>
    </xsl:variable>

    <fo:block id="{$id}"
                xsl:use-attribute-sets="monospace.verbatim.properties shade.verbatim.style">
      <xsl:choose>
        <xsl:when test="$hyphenate.verbatim != 0 and function-available('exsl:node-set')">
          <xsl:apply-templates select="exsl:node-set($content)" mode="hyphenate.verbatim"/>
        </xsl:when>
        <xsl:otherwise>
          <xsl:copy-of select="$content"/>
        </xsl:otherwise>
      </xsl:choose>
    </fo:block>
  </xsl:template>

<xsl:template match="d:revhistory" mode="book.titlepage.mode">

  <xsl:variable name="explicit.table.width">
    <xsl:call-template name="pi.dbfo_table-width"/>
  </xsl:variable>

  <xsl:variable name="table.width">
    <xsl:choose>
      <xsl:when test="$explicit.table.width != ''">
        <xsl:value-of select="$explicit.table.width"/>
      </xsl:when>
      <xsl:when test="$default.table.width = ''">
        <xsl:text>100%</xsl:text>
      </xsl:when>
      <xsl:otherwise>
        <xsl:value-of select="$default.table.width"/>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:variable>

  <fo:table table-layout="fixed" width="{$table.width}" margin-top="40mm" xsl:use-attribute-sets="revhistory.table.properties">
    <fo:table-column column-number="1" column-width="proportional-column-width(1)"/>
    <fo:table-column column-number="2" column-width="proportional-column-width(1)"/>
    <fo:table-column column-number="3" column-width="proportional-column-width(1)"/>
    <fo:table-body start-indent="0pt" end-indent="0pt">
      <fo:table-row>
        <fo:table-cell number-columns-spanned="3" xsl:use-attribute-sets="revhistory.table.cell.properties">
          <fo:block xsl:use-attribute-sets="revhistory.title.properties">
            <xsl:call-template name="gentext">
              <xsl:with-param name="key" select="'RevHistory'"/>
            </xsl:call-template>
          </fo:block>
        </fo:table-cell>
      </fo:table-row>
      <xsl:apply-templates mode="titlepage.mode"/>
    </fo:table-body>
  </fo:table>
</xsl:template>

<xsl:template match="d:editor[1]" priority="2" mode="titlepage.mode">
   <fo:block text-align="center"  margin-top="5mm"></fo:block>
  <xsl:call-template name="gentext.edited.by"/>
  <xsl:call-template name="gentext.space"/>

  <xsl:call-template name="person.name.list">
    <xsl:with-param name="person.list" select="../d:editor"/>
  </xsl:call-template>
</xsl:template>

  <xsl:template match="d:link|d:term">
    <xsl:choose>
      <xsl:when test="@linkend">
        <fo:basic-link internal-destination="{@linkend}"
                xsl:use-attribute-sets="xref.properties"
                color="blue">
            <xsl:choose>
                <xsl:when test="count(child::node())=0">
                    <xsl:value-of select="@linkend"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:apply-templates/>
                </xsl:otherwise>
            </xsl:choose>
        </fo:basic-link>
      </xsl:when>
      <xsl:when test="@xl:href">
        <fo:basic-link external-destination="{@xl:href}"
                xsl:use-attribute-sets="xref.properties"
                text-decoration="underline"
                color="blue">
            <xsl:choose>
                <xsl:when test="count(child::node())=0">
                    <xsl:value-of select="@linkend"/>
                </xsl:when>
                <xsl:otherwise>
                    <xsl:apply-templates/>
                </xsl:otherwise>
            </xsl:choose>
        </fo:basic-link>
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates/>
      </xsl:otherwise>
    </xsl:choose>
    </xsl:template>

</xsl:stylesheet>
