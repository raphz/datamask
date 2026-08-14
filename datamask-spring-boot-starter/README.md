# datamask-spring-boot-starter

**The dependency a Spring Boot application adds to get DataMask configured.**

```groovy
implementation 'ch.raph.datamask:datamask-spring-boot-starter'
```

```yaml
datamask:
  secret: ${DATAMASK_SECRET}
```

A `DataMask` bean now exists, built from those properties, and every DataMask module on the classpath
is wired to it. What that means per module is in
[`datamask-spring-boot-autoconfigure`](../datamask-spring-boot-autoconfigure/README.md), which is
where the properties are documented too.

**Without `datamask.secret` the context will not start.** That is deliberate and it is the one
decision in this library that is not negotiable — see the auto-configuration's README for why a
default key would be worse than a failed deployment.

## What is in it

`datamask-core` and `datamask-spring-boot-autoconfigure`. Nothing else.

## Why the integrations are not in it

A starter that pulled them all in would decide two things it has no business deciding.

`datamask-logback` and `datamask-log4j2` carry **mutually exclusive** logging backends. A starter
bringing one would break every application that chose the other, and a Boot application that
deliberately swapped to Log4j2 would find Logback back on its classpath because it wanted PII
masking.

`datamask-jackson` brings `jackson-databind`, which a non-web Boot application does not otherwise
have; `datamask-jdbc` and `datamask-kafka` are jars an application with no database and no broker
would carry for nothing.

So the channel is the application's choice and the wiring is free:

```groovy
implementation 'ch.raph.datamask:datamask-spring-boot-starter'
implementation 'ch.raph.datamask:datamask-jackson'    // JSON responses and payloads
implementation 'ch.raph.datamask:datamask-logback'    // log events
implementation 'ch.raph.datamask:datamask-jdbc'       // database errors and bind parameters
implementation 'ch.raph.datamask:datamask-kafka'      // records and headers
```

Each line is picked up on sight. Nothing else changes, and any of them can be switched off again with
`datamask.<module>.enabled=false`.

Use [`datamask-bom`](../datamask-bom/README.md) so the versions stay aligned.
