package de.klackwerk.pneumatik.backup

import grails.validation.Validateable

class RetentionPolicyCommand implements Validateable {

    Integer keepCount
    Integer keepDays
    Boolean enabled = true

    static constraints = {
        keepCount nullable: true, min: 1
        keepDays nullable: true, min: 1, validator: { Integer val, RetentionPolicyCommand obj ->
            if (val == null && obj.keepCount == null) {
                return ['atLeastOneLimit']
            }
        }
        enabled nullable: true
    }
}
