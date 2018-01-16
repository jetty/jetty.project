<%@ page contentType="text/plain; charset=UTF-8" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/core" prefix="c" %>
<%@ taglib uri="http://java.sun.com/jsp/jstl/fmt" prefix="fmt" %>
Title: JSTL c:catch test

<c:catch var ="catchException">
  <fmt:parseNumber var="parsedNum" value="aaa" />
</c:catch>

<c:if test = "${catchException != null}">
[c:catch] exception : ${catchException}
[c:catch] exception.message : ${catchException.message}
</c:if>
<c:if test = "${catchException == null}">
[c:catch] exception is null
</c:if>
