<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
<xsl:output method="html"/>
<xsl:param name="reportpath"/>
<xsl:key name="kClass" match="difference" use="@class"/>

<xsl:template match="/*">
  <div class="project">
    <div class="name">
      <a>
        <xsl:attribute name="href">
          <xsl:text>../../</xsl:text>
          <xsl:value-of select="$reportpath"/>
          <xsl:text>/target/site/clirr-report.html</xsl:text>
        </xsl:attribute>
        <xsl:value-of select="$reportpath"/>
      </a>
    </div>
    <xsl:apply-templates mode="by_class" select=
      "difference[generate-id() = generate-id(key('kClass', @class)[1])]">
      <xsl:sort select="@class"/>
    </xsl:apply-templates>
  </div>
</xsl:template>

<xsl:template mode="by_class" match="difference">
  <div class="classname">
    <div class="name"><xsl:value-of select="@class"/></div>
    <xsl:call-template name="by_severity">
      <xsl:with-param name="rawseverity">ERROR</xsl:with-param>
    </xsl:call-template>
    <xsl:call-template name="by_severity">
      <xsl:with-param name="rawseverity">WARNING</xsl:with-param>
    </xsl:call-template>
    <xsl:call-template name="by_severity">
      <xsl:with-param name="rawseverity">INFO</xsl:with-param>
    </xsl:call-template>
  </div>
</xsl:template>

<xsl:template name="by_severity">
  <xsl:param name="rawseverity"/>
  <xsl:if test="count(key('kClass', @class)[@binseverity=$rawseverity]) &gt; 0">
    <div class="severity">
      <div>
        <xsl:attribute name="class">
          <xsl:text>type </xsl:text>
          <xsl:call-template name="get_severity">
            <xsl:with-param name="rawseverity" select="$rawseverity"/>
          </xsl:call-template>
        </xsl:attribute>
        <xsl:call-template name="get_severity">
          <xsl:with-param name="rawseverity" select="$rawseverity"/>
        </xsl:call-template>
      </div>
      <ol class="difference">
        <xsl:apply-templates mode="by_difference" 
             select="key('kClass', @class)[@binseverity=$rawseverity]"/>
      </ol>
    </div>
  </xsl:if>
</xsl:template>

<xsl:template mode="by_difference" match="difference">
  <li><xsl:value-of select="text()"/></li>
</xsl:template>

<xsl:template name="get_severity">
  <xsl:param name="rawseverity" />
  <xsl:choose>
    <xsl:when test="$rawseverity='ERROR'">
      <xsl:text>error</xsl:text>
    </xsl:when>
    <xsl:when test="$rawseverity='WARNING'">
      <xsl:text>warning</xsl:text>
    </xsl:when>
    <xsl:when test="$rawseverity='INFO'">
      <xsl:text>info</xsl:text>
    </xsl:when>
  </xsl:choose>
</xsl:template>

</xsl:stylesheet>
