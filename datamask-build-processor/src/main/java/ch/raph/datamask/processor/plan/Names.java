package ch.raph.datamask.processor.plan;

/**
 * Every {@code datamask-core} type the generated source refers to, written out once.
 *
 * <p>This processor deliberately does not depend on {@code datamask-core} — see the module's
 * {@code build.gradle} — so these names are text rather than class literals and the compiler cannot
 * catch a typo in them. Keeping them in one place is half the answer; the other half is
 * {@code GeneratedSourceTest}, which compiles generated output against the real core and therefore
 * fails on any name that has drifted.
 */
final class Names {

    static final String MASK_PLAN = "ch.raph.datamask.domain.MaskPlan";
    static final String MEMBER_PLAN = "ch.raph.datamask.domain.MemberPlan";
    static final String MASK_ACTION = "ch.raph.datamask.domain.MaskAction";
    static final String PII_DESCRIPTOR = "ch.raph.datamask.domain.PiiDescriptor";
    static final String GENERATED_MASK_PLAN = "ch.raph.datamask.infrastructure.generated.GeneratedMaskPlan";

    static final String MASK_STRATEGY = "ch.raph.datamask.api.MaskStrategy";
    static final String PII_CATEGORY = "ch.raph.datamask.api.PiiCategory";
    static final String SENSITIVITY = "ch.raph.datamask.api.Sensitivity";
    static final String MASKER = "ch.raph.datamask.api.Masker";

    private Names() {}
}
