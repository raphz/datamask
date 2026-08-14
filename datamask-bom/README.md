# datamask-bom

**A platform pinning every DataMask module to one version.**

```groovy
dependencies {
    implementation platform('ch.raph.datamask:datamask-bom:0.1.0')

    implementation 'ch.raph.datamask:datamask-core'
    implementation 'ch.raph.datamask:datamask-jdbc'
}
```

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>ch.raph.datamask</groupId>
      <artifactId>datamask-bom</artifactId>
      <version>0.1.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
```

Import it and name modules without versions. Mixing versions of the engine and an integration module
is the kind of mismatch that shows up as a `NoSuchMethodError` at runtime rather than at build time.

The BOM pins DataMask's own modules only. Third-party versions are deliberately left to the
application, and DataMask's are aligned to what Spring Boot manages so a Boot application's dependency
management wins without conflict.
