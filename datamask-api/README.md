# datamask-api

**The annotations and the SPI. Zero dependencies, and it must stay that way.**

A domain module can depend on this alone to declare what its data *is*, without taking on the masking
engine, a reflection library, or a logging framework:

```java
public record Customer(
        @PII Email email,
        @PII(strategy = HASH) String iban,
        @NoMask(justification = "ISO currency code identifies no one") String currency) { }
```

**Adding a dependency here is a breaking design change.** Raise it rather than doing it.

## `@PII`

| Attribute | Default | Meaning |
|---|---|---|
| `category` | `UNSPECIFIED` | What the value is. The attribute worth setting. |
| `strategy` | `AUTO` | How to mask it. Resolves from the category, the declared type, then content. |
| `sensitivity` | `HIGH` | Which policy thresholds it survives. |
| `keep` | `-1` | Characters left visible; `-1` means the category's own default. |
| `padding` | `'*'` | The masking character. |
| `replacement` | `""` | A fixed replacement, instead of masking. |
| `masker` | `Masker.class` | A custom implementation for a format the library does not know. |
| `purpose` | `""` | Why this field holds personal data — for an audit trail, not for the engine. |

Annotating the **category** and leaving the strategy on `AUTO` is the recommended style: the category
is what a developer can state confidently, while the strategy is a policy decision that may differ
between environments.

An annotation on a **type** applies to every bare `@PII` use of it, which is what makes
`@PII Email email` produce email-shaped masking with no further configuration.

## `@NoMask`

```java
@NoMask(justification = "ISO currency code identifies no one")
String currency
```

The justification is **mandatory**. An unexplained exemption on a PII-bearing type is exactly the
change that should not pass review unnoticed. `@NoMask` also keeps a value out of content scanning —
an exemption a detector could still rewrite is not an exemption.

## Custom maskers

```java
public final class ContractReferenceMasker implements Masker {
    @Override
    public Object mask(Object value, MaskContext context) {
        return context.pseudonymize(value.toString());
    }
}
```

```java
@PII(masker = ContractReferenceMasker.class) String reference
```

`MaskContext` hands the masker the engine's keyed pseudonymisation and tokenisation —
`pseudonymize(String)` and `tokenize(String)` — alongside the resolved `category()`, `strategy()`,
`keep()`, `padding()`, `replacement()`, `path()`, `declaredType()` and `redactionPlaceholder()`. That
is precisely so a custom masker never needs `datamask-core`.

Implementations need a public no-argument constructor, or must be registered explicitly:

```java
DataMask.builder().masker(new ContractReferenceMasker(dependency)).build();
```

`supports(Class<?>)` defaults to `true`; override it to decline types the masker cannot handle.

A masker that throws yields the redaction placeholder, never the value it failed to mask.

## Replacing a built-in strategy

```java
DataMask.builder().masker(MaskStrategy.IBAN, institutionSpecificMasker).build();
```

## Contents

`PII` · `NoMask` · `MaskStrategy` · `PiiCategory` · `Sensitivity` · `Masker` · `MaskContext`
