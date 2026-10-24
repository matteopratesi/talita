@import talita.checkin.NameBlock
@import talita.checkin.NameRow
@param NameBlock block
<%-- A table of names, surname and name and nothing else (the list for the venue): one sheet of paper, with its header, and the blank rows under the names where the
     adults who are not on the list are written by hand. --%>
<table class="tabella-stampa tabella-stampa--elenco">
  @if(block.getCaption() != null)
    <caption>${block.getCaption()}</caption>
  @endif
  <thead>
    <tr><th class="col-cognome" scope="col">Cognome</th><th scope="col">Nome</th></tr>
  </thead>
  <tbody>
    @for(NameRow row : block.getRows())
      <tr><td class="nome">${row.getLast()}</td><td>${row.getFirst()}</td></tr>
    @endfor
    @for(int blank = 0; blank < block.getBlank(); blank++)
      <tr class="riga-vuota"><td></td><td></td></tr>
    @endfor
  </tbody>
</table>
