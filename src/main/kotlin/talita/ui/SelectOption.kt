package talita.ui

/** An option of a `select` (`components/selectField.jte`): what it sends, what it says, and whether it is the one that is chosen. */
data class SelectOption(val value: String, val label: String, val selected: Boolean)
