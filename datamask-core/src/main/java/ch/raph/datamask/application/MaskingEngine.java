package ch.raph.datamask.application;

import ch.raph.datamask.api.MaskStrategy;
import ch.raph.datamask.api.Masker;
import ch.raph.datamask.domain.MaskAction;
import ch.raph.datamask.domain.MaskPlan;
import ch.raph.datamask.domain.MaskingException;
import ch.raph.datamask.domain.MaskingObserver;
import ch.raph.datamask.domain.MaskingPolicy;
import ch.raph.datamask.domain.MemberPlan;
import ch.raph.datamask.domain.PiiDescriptor;
import ch.raph.datamask.infrastructure.reflect.Types;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Walks an object graph and produces a masked copy of it.
 *
 * <p>Two invariants hold everywhere in here. The original is never mutated, because the caller is
 * still using it. And every failure path produces less information than it started with, never
 * more — a bug in a masker must not be a way to disclose the value it failed to mask.
 */
public final class MaskingEngine {

    private final MaskPlanCompiler compiler;
    private final MaskerRegistry maskers;
    private final MaskingPolicy policy;
    private final TextSanitizer sanitizer;
    private final MaskContextFactory contexts;
    private final MaskingObserver observer;

    public MaskingEngine(
            MaskPlanCompiler compiler,
            MaskerRegistry maskers,
            MaskingPolicy policy,
            TextSanitizer sanitizer,
            MaskContextFactory contexts,
            MaskingObserver observer) {
        this.compiler = compiler;
        this.maskers = maskers;
        this.policy = policy;
        this.sanitizer = sanitizer;
        this.contexts = contexts;
        this.observer = observer;
    }

    /** Returns a masked copy of the graph rooted at {@code value}. */
    public Object mask(Object value) {
        return descend(value, "", 0, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** Masks the PII inside a string, leaving the rest of the text intact. */
    public String maskText(CharSequence text, String path) {
        return sanitizer.sanitize(text, path);
    }

    /** Masks a single value against an explicit declaration, for integrations that already know it. */
    public Object maskDeclared(Object value, PiiDescriptor descriptor, Class<?> declaredType, String path) {
        return maskLeaf(value, descriptor, declaredType, path);
    }

    public MaskingPolicy policy() {
        return policy;
    }

    public TextSanitizer sanitizer() {
        return sanitizer;
    }

    public MaskPlanCompiler compiler() {
        return compiler;
    }

    public MaskingObserver observer() {
        return observer;
    }

    private Object descend(Object value, String path, int depth, Set<Object> inProgress) {
        if (value == null) {
            return null;
        }
        if (depth > policy.maxDepth()) {
            observer.onDepthLimitExceeded(path);
            return null;
        }

        return switch (value) {
            case CharSequence text -> policy.scanUnannotatedText() ? sanitizer.sanitize(text, path) : value;
            case Collection<?> collection -> descendCollection(collection, path, depth, inProgress);
            case Map<?, ?> map -> descendMap(map, path, depth, inProgress);
            case Optional<?> optional ->
                optional.isPresent() ? Optional.ofNullable(descend(optional.get(), path, depth, inProgress)) : optional;
            default -> {
                Class<?> runtime = value.getClass();
                if (runtime.isArray()) {
                    yield descendArray(value, path, depth, inProgress);
                }
                yield Types.isLeaf(runtime) ? value : descendObject(value, runtime, path, depth, inProgress);
            }
        };
    }

    private Object descendObject(Object value, Class<?> runtime, String path, int depth, Set<Object> inProgress) {
        // A graph that points back at itself cannot be reproduced as a copy. The back-reference
        // becomes null: the original instance must not be planted inside the masked graph, because
        // its members were masked into the copy, never in place — the original still carries raw PII.
        if (!inProgress.add(value)) {
            return null;
        }
        try {
            MaskPlan plan = compiler.planFor(runtime);
            if (plan.isFailed()) {
                // The type's members could not even be read. Nothing proved the value is PII-free,
                // so passing it through is not an option; the failure policy decides instead.
                return onStructuralFailure(
                        value,
                        path,
                        new IllegalStateException("cannot mask " + runtime.getName() + ": " + plan.failure()));
            }
            if (plan.isOpaque()) {
                return value;
            }

            List<MemberPlan> members = plan.members();
            Object[] masked = new Object[members.size()];
            boolean changed = false;

            for (int i = 0; i < members.size(); i++) {
                MemberPlan member = members.get(i);
                String memberPath =
                        path.isEmpty() ? runtime.getSimpleName() + "." + member.name() : path + "." + member.name();

                Object raw = member.accessor().get(value);
                Object result =
                        switch (member.action()) {
                            case MaskAction.Mask(PiiDescriptor descriptor) ->
                                maskLeaf(raw, descriptor, member.declaredType(), memberPath);
                            case MaskAction.Descend _ -> descend(raw, memberPath, depth + 1, inProgress);
                            case MaskAction.Keep _ -> raw;
                            case MaskAction.Drop _ -> null;
                        };
                result = Coercion.toDeclaredType(result, member.declaredType());
                masked[i] = result;
                changed |= result != raw;
            }

            // Nothing was masked, so the original is already safe. Skipping the rebuild avoids an
            // allocation on every PII-free object, and avoids needing a usable constructor for one.
            return changed ? plan.rebuilder().rebuild(value, masked) : value;
        } catch (MaskingException deliberate) {
            // Raised by the failure policy itself a frame below. Treating it as a structural
            // failure here would quietly undo FailureMode.THROW for every nested field.
            throw deliberate;
        } catch (Throwable failure) {
            return onStructuralFailure(value, path, failure);
        } finally {
            inProgress.remove(value);
        }
    }

    private Object descendCollection(Collection<?> collection, String path, int depth, Set<Object> inProgress) {
        Collection<Object> copy = newCollectionLike(collection);
        int index = 0;
        boolean changed = false;
        for (Object element : collection) {
            if (index >= policy.maxCollectionElements()) {
                // Bounded on purpose: a runaway collection must not turn a log statement into an
                // outage. Dropping the tail discloses nothing, unlike passing it through unmasked.
                observer.onDepthLimitExceeded(path + "[" + index + "]");
                changed = true;
                break;
            }
            Object masked = descend(element, path + "[" + index + "]", depth + 1, inProgress);
            changed |= masked != element;
            copy.add(masked);
            index++;
        }
        return changed ? copy : collection;
    }

    private Object descendMap(Map<?, ?> map, String path, int depth, Set<Object> inProgress) {
        Map<Object, Object> copy = newMapLike(map);
        int index = 0;
        boolean changed = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= policy.maxCollectionElements()) {
                observer.onDepthLimitExceeded(path + "{" + index + "}");
                changed = true;
                break;
            }
            // The path is positional on purpose. A map is often keyed by exactly the PII this
            // library exists to hide, and the path reaches observers and exception messages —
            // embedding the key would leak it through the reporting channel.
            String entryPath = path + "{" + index + "}";
            // Keys carry PII more often than people expect — a map keyed by email address is a
            // common shape — but masking them changes lookup semantics, so it is opt-in.
            Object key = policy.maskMapKeys()
                    ? descend(entry.getKey(), entryPath + "{key}", depth + 1, inProgress)
                    : entry.getKey();
            Object masked = descend(entry.getValue(), entryPath, depth + 1, inProgress);
            changed |= masked != entry.getValue() || key != entry.getKey();
            copy.put(key, masked);
            index++;
        }
        return changed ? copy : map;
    }

    private Object descendArray(Object array, String path, int depth, Set<Object> inProgress) {
        Class<?> component = array.getClass().getComponentType();
        if (component.isPrimitive()) {
            return array;
        }
        int length = Array.getLength(array);
        Object copy = Array.newInstance(component, length);
        boolean changed = false;
        for (int i = 0; i < length; i++) {
            Object element = Array.get(array, i);
            Object masked = descend(element, path + "[" + i + "]", depth + 1, inProgress);
            changed |= masked != element;
            Array.set(copy, i, Coercion.toDeclaredType(masked, component));
        }
        return changed ? copy : array;
    }

    private Object maskLeaf(Object value, PiiDescriptor declared, Class<?> declaredType, String path) {
        if (value == null) {
            return null;
        }
        if (!policy.applies(declared.sensitivity())) {
            return value;
        }
        try {
            Class<?> runtime = value.getClass();
            if (Types.isSingleStringValueObject(runtime)) {
                return maskValueObject(value, runtime, declared, path);
            }
            PiiDescriptor resolved = resolve(declared, value);
            Object masked = apply(value, resolved, declaredType, path);
            observer.onMasked(path, resolved.category(), resolved.strategy());
            return masked;
        } catch (Throwable failure) {
            return onMaskFailure(path, failure);
        }
    }

    /**
     * Masks the string inside a single-component value object and rebuilds it, so {@code Email}
     * stays an {@code Email}. A value object whose constructor validates its input may reject the
     * masked string; that is treated as a failure and the field is dropped rather than disclosed.
     */
    private Object maskValueObject(Object value, Class<?> runtime, PiiDescriptor declared, String path)
            throws Throwable {
        MaskPlan plan = compiler.planFor(runtime);
        if (plan.members().size() != 1) {
            return apply(value, resolve(declared, value), runtime, path);
        }

        MemberPlan component = plan.members().getFirst();
        Object inner = component.accessor().get(value);
        if (inner == null) {
            return value;
        }

        PiiDescriptor resolved = resolve(declared, inner);
        Object maskedInner = apply(inner, resolved, component.declaredType(), path);
        observer.onMasked(path, resolved.category(), resolved.strategy());

        try {
            return plan.rebuilder()
                    .rebuild(value, new Object[] {Coercion.toDeclaredType(maskedInner, component.declaredType())});
        } catch (Throwable rejected) {
            observer.onFailure(path, rejected);
            return null;
        }
    }

    private Object apply(Object value, PiiDescriptor descriptor, Class<?> declaredType, String path) {
        if (descriptor.strategy() == MaskStrategy.SCAN && !descriptor.hasCustomMasker()) {
            return sanitizer.sanitize(value.toString(), path);
        }

        Masker masker = descriptor.hasCustomMasker()
                ? maskers.forType(descriptor.maskerType())
                : maskers.forStrategy(descriptor.strategy());
        if (!masker.supports(declaredType)) {
            masker = maskers.redacting();
        }
        return masker.mask(value, contexts.create(descriptor, descriptor.strategy(), path, declaredType));
    }

    /**
     * Turns {@link MaskStrategy#AUTO} into a concrete strategy: the category's default, then what
     * the value's own content is detected to be, and full redaction when neither answers. Whatever
     * was resolved is then hardened: a never-partially-revealed category refuses any strategy that
     * would show part of the value.
     */
    private PiiDescriptor resolve(PiiDescriptor descriptor, Object value) {
        return hardened(resolveStrategy(descriptor, value));
    }

    private PiiDescriptor resolveStrategy(PiiDescriptor descriptor, Object value) {
        if (descriptor.hasCustomMasker()) {
            return descriptor;
        }
        if (descriptor.strategy() != MaskStrategy.AUTO) {
            return descriptor;
        }

        MaskStrategy fromCategory = descriptor.category().defaultStrategy();
        if (fromCategory != MaskStrategy.AUTO) {
            return descriptor.withStrategy(fromCategory);
        }

        String text = value instanceof CharSequence cs ? cs.toString() : String.valueOf(value);
        return sanitizer
                .classify(text)
                .filter(detected -> detected.defaultStrategy() != MaskStrategy.AUTO)
                .map(detected -> descriptor.withCategory(detected).withStrategy(detected.defaultStrategy()))
                .orElseGet(() -> descriptor.withStrategy(MaskStrategy.REDACT));
    }

    /**
     * The {@code keep = 0} rule in {@link PiiDescriptor} only constrains maskers that honour
     * {@code keep}; the format maskers reveal fixed positions by design. So a category that must
     * never be partially revealed is forced onto {@code REDACT} whenever the resolved strategy
     * would show any part of the value. A custom masker is left alone — it is explicit code, and
     * it receives {@code keep() == 0} as its signal.
     */
    private static PiiDescriptor hardened(PiiDescriptor descriptor) {
        if (!descriptor.category().neverPartiallyReveal() || descriptor.hasCustomMasker()) {
            return descriptor;
        }
        return switch (descriptor.strategy()) {
            case REDACT, HASH, TOKENIZE, NULLIFY -> descriptor;
            default -> descriptor.withStrategy(MaskStrategy.REDACT);
        };
    }

    private Object onMaskFailure(String path, Throwable failure) {
        observer.onFailure(path, failure);
        return switch (policy.failureMode()) {
            case REDACT -> policy.redactionPlaceholder();
            case THROW -> throw new MaskingException(path, "masker failed", failure);
            case PASS_THROUGH ->
                throw new MaskingException(path, "PASS_THROUGH would disclose the value that failed to mask", failure);
        };
    }

    private Object onStructuralFailure(Object original, String path, Throwable failure) {
        observer.onFailure(path, failure);
        return switch (policy.failureMode()) {
            case REDACT -> null;
            case THROW -> throw new MaskingException(path, "could not build a masked copy", failure);
            case PASS_THROUGH -> original;
        };
    }

    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollectionLike(Collection<?> source) {
        if (source instanceof SortedSet<?> sorted) {
            return new TreeSet<>((Comparator<Object>) sorted.comparator());
        }
        return source instanceof Set ? new LinkedHashSet<>() : new ArrayList<>(source.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMapLike(Map<?, ?> source) {
        if (source instanceof SortedMap<?, ?> sorted) {
            return new TreeMap<>((Comparator<Object>) sorted.comparator());
        }
        return new LinkedHashMap<>();
    }
}
