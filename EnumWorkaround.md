# Introduction #

As Groovy 1.1-beta-2 and Grails 0.6 have not support Enumeration yet, we need to have workaround a bit for this.

# How to #

  * Create a domain class for the enum
  * Add the simple field, **value** as String
  * In `conf/BootStrap.groovy`, use the domain class's .count() method to check if enum values don't exist. So we can initialise them.

# Example #
```
class AccountType {
  String value
}
```

initialising enum values in the init closure, in `conf/BootStrap.groovy`:
```
 if(AccountType.count() == 0) {
    new AccountType(value:"GL").save()
    new AccountType(value:"PROJECT").save()
 }
```