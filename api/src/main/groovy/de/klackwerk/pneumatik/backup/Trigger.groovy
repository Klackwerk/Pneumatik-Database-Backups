package de.klackwerk.pneumatik.backup

enum Trigger {
    TRIGGER_HOURLY('Hourly'),
    TRIGGER_4HOURLY('4-Hourly'),
    TRIGGER_12HOURLY('12-Hourly'),
    TRIGGER_DAILY('Daily'),
    TRIGGER_MANUAL('Manual')

    final String displayName

    private Trigger(String displayName) {
        this.displayName = displayName
    }
}
