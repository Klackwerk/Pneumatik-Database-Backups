package de.klackwerk.pneumatik.security

import de.klackwerk.pneumatik.inventory.Database
import grails.validation.Validateable

class ApiKeyCommand implements Validateable {

    String comment
    Date validUntil
    /** databases this key may back up; empty means all of them */
    List<String> databaseIds

    static constraints = {
        comment nullable: true, blank: false
        validUntil nullable: true
        databaseIds nullable: true, validator: { List<String> ids ->
            if (ids?.any { it && !Database.exists(it) }) {
                return ['notFound']
            }
        }
    }
}
