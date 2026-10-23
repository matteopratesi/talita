@param String title
@param String lead = null
<div class="mb-6 lg:mb-8">
  <h1 tabindex="-1" class="text-2xl font-semibold tracking-tight wrap-anywhere lg:text-3xl">${title}</h1>
  @if(lead != null)
    <p class="mt-2 text-ink-muted wrap-anywhere">${lead}</p>
  @endif
</div>
