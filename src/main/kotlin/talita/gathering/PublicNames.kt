package talita.gathering

/**
 * What the registrations and the teachers tell the gathering about names (TALITA-16, criteria 12-16): whether a text that goes on the public page holds the first and last
 * name of a child or of a teacher that the application knows. The gathering asks, and `talita.registration` answers (the same shape as [RegistrationUsage]): this package never reads
 * the tables of the children, and does not depend on that package.
 *
 * It is **one** query for all the texts of a form, and it answers only yes or no for each: the names stay in the database, they are never read by the application, saved, kept
 * between two requests or written in the log, and neither is the text.
 */
interface PublicNames {

    /**
     * For each of [texts], in the same order: whether it holds the first name and the last name of the same person, in either order, as whole words with only spaces between
     * them, compared without capitals and accents. A name alone, or the two parts apart («Luca, Rossi»), do not count.
     */
    fun contain(texts: List<String>): List<Boolean>
}
