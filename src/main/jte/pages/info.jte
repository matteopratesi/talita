@import talita.gathering.PublicInfo
@import talita.ui.PublicShell
@param PublicShell shell
@param PublicInfo info
<%-- The information page for the parents (criteria 24, 26, 27): in the public shell, no script, nothing personal. --%>

@template.layout.public(shell = shell, content = @`
  @template.gathering.infoContent(info = info)
`)
