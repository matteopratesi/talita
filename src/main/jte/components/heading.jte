@param int level
@param String classes
@param String text
@param String id = null
<%-- A heading whose level is a parameter: the content of the information page is one component for the public page (the title is the h1) and for the
     preview of the teachers (the h1 is «Anteprima della pagina», the title is an h2 of the same size, the cards h3). Level 1 can take the focus. --%>
@if(level == 1)<h1 id="${id}" tabindex="-1" class="${classes}">${text}</h1>@elseif(level == 2)<h2 id="${id}" class="${classes}">${text}</h2>@else<h3 id="${id}" class="${classes}">${text}</h3>@endif
