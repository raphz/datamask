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
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Queue;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

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
        return descend(value, "", 0, new IdentityHashMap<>());
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

    private Object descend(Object value, String path, int depth, Map<Object, Object> inProgress) {
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
            case Optional<?> optional -> descendOptional(optional, path, depth, inProgress);
            // A URI is a leaf structurally, but its query string is one of the places PII hides in
            // plain sight — `?email=john@doe.com` in a stored callback URL is the shape.
            case URI uri -> policy.scanUnannotatedText() ? scanUri(uri, path) : value;
            case URL url -> policy.scanUnannotatedText() ? scanUrl(url, path) : value;
            default -> {
                Class<?> runtime = value.getClass();
                if (runtime.isArray()) {
                    yield descendArray(value, path, depth, inProgress);
                }
                yield Types.isLeaf(runtime) ? value : descendObject(value, runtime, path, depth, inProgress);
            }
        };
    }

    private Object descendObject(
            Object value, Class<?> runtime, String path, int depth, Map<Object, Object> inProgress) {
        // An object that points back at itself cannot have its cycle reproduced, because its copy
        // does not exist until every member has been masked. So the back-reference becomes null.
        // What it must never become is the original: that instance was never masked in place, and
        // planting it inside the masked graph would put the raw values straight back.
        if (inProgress.containsKey(value)) {
            return null;
        }
        inProgress.put(value, null);
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

    /**
     * Masks what an {@code Optional} holds and re-wraps it — but hands back the <em>same</em>
     * {@code Optional} when the contents did not change. Wrapping afresh would allocate a new
     * instance on every clean graph, which reads as "changed" to the enclosing object and forces a
     * rebuild of a record that carried no PII at all.
     */
    private Object descendOptional(Optional<?> optional, String path, int depth, Map<Object, Object> inProgress) {
        if (optional.isEmpty()) {
            return optional;
        }
        Object inner = optional.get();
        Object masked = descend(inner, path, depth, inProgress);
        return masked == inner ? optional : Optional.ofNullable(masked);
    }

    private Object scanUri(URI uri, String path) {
        String text = uri.toString();
        String safe = sanitizer.sanitize(text, path);
        if (safe.equals(text)) {
            return uri;
        }
        try {
            return URI.create(safe);
        } catch (IllegalArgumentException notAUri) {
            // Masking left something that is no longer a URI. Handing back the original would
            // disclose exactly what was just detected, so the value is dropped instead.
            observer.onFailure(path, notAUri);
            return null;
        }
    }

    private Object scanUrl(URL url, String path) {
        String text = url.toString();
        String safe = sanitizer.sanitize(text, path);
        if (safe.equals(text)) {
            return url;
        }
        try {
            return URI.create(safe).toURL();
        } catch (IllegalArgumentException | MalformedURLException notAUrl) {
            observer.onFailure(path, notAUrl);
            return null;
        }
    }

    private Object descendCollection(Collection<?> collection, String path, int depth, Map<Object, Object> inProgress) {
        if (inProgress.containsKey(collection)) {
            return inProgress.get(collection);
        }
        Collection<Object> copy = newCollectionLike(collection);
        // Unlike an object, a container's copy exists before its contents are walked, so a
        // back-reference can point at the copy and the cycle survives masking intact. Registering
        // it is not a nicety: a list holding itself would otherwise unroll to the depth limit, and
        // one holding itself twice would unroll exponentially and take the caller down with it.
        inProgress.put(collection, copy);
        try {
            int index = 0;
            boolean changed = false;
            for (Object element : collection) {
                if (index >= policy.maxCollectionElements()) {
                    // Bounded on purpose: a runaway collection must not turn a log statement into an
                    // outage. Dropping the tail discloses nothing, unlike passing it through unmasked.
                    observer.onCollectionTruncated(path, index);
                    changed = true;
                    break;
                }
                Object masked = descend(element, path + "[" + index + "]", depth + 1, inProgress);
                changed |= masked != element;
                changed |= !addMasked(copy, masked);
                index++;
            }
            return changed ? copy : collection;
        } finally {
            inProgress.remove(collection);
        }
    }

    private Object descendMap(Map<?, ?> map, String path, int depth, Map<Object, Object> inProgress) {
        if (inProgress.containsKey(map)) {
            return inProgress.get(map);
        }
        Map<Object, Object> copy = newMapLike(map);
        inProgress.put(map, copy);
        try {
            return descendEntries(map, copy, path, depth, inProgress);
        } finally {
            inProgress.remove(map);
        }
    }

    private Object descendEntries(
            Map<?, ?> map, Map<Object, Object> copy, String path, int depth, Map<Object, Object> inProgress) {
        int index = 0;
        boolean changed = false;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (index >= policy.maxCollectionElements()) {
                observer.onCollectionTruncated(path, index);
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
            changed |= !putMasked(copy, key, masked);
            index++;
        }
        return changed ? copy : map;
    }

    /**
     * Adds to the copy, dropping the element when the copy refuses it — {@code ArrayDeque} and a
     * naturally ordered {@code TreeSet} reject null, and a comparator can reject a masked value.
     * Dropping one element discloses nothing; letting the refusal propagate would take the whole
     * enclosing object down a failure path it does not need.
     */
    private static boolean addMasked(Collection<Object> copy, Object element) {
        try {
            copy.add(element);
            return true;
        } catch (NullPointerException | ClassCastException | IllegalArgumentException refused) {
            return false;
        }
    }

    /** The map counterpart of {@link #addMasked}: {@code ConcurrentHashMap} rejects null on both sides. */
    private static boolean putMasked(Map<Object, Object> copy, Object key, Object value) {
        try {
            copy.put(key, value);
            return true;
        } catch (NullPointerException | ClassCastException | IllegalArgumentException refused) {
            return false;
        }
    }

    private Object descendArray(Object array, String path, int depth, Map<Object, Object> inProgress) {
        Class<?> component = array.getClass().getComponentType();
        if (component.isPrimitive()) {
            return array;
        }
        if (inProgress.containsKey(array)) {
            return inProgress.get(array);
        }
        int length = Array.getLength(array);
        Object copy = Array.newInstance(component, length);
        inProgress.put(array, copy);
        try {
            boolean changed = false;
            for (int i = 0; i < length; i++) {
                Object element = Array.get(array, i);
                Object masked = descend(element, path + "[" + i + "]", depth + 1, inProgress);
                changed |= masked != element;
                Array.set(copy, i, Coercion.toDeclaredType(masked, component));
            }
            return changed ? copy : array;
        } finally {
            inProgress.remove(array);
        }
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
            if (value instanceof Optional<?>
                    || value instanceof OptionalInt
                    || value instanceof OptionalLong
                    || value instanceof OptionalDouble) {
                return maskOptional(value, declared, path);
            }
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
     * Masks what an annotated {@code Optional} holds, then re-wraps it — the same treatment a
     * single-component value object gets, and for the same reason. Handing the wrapper itself to a
     * masker would produce text that does not fit an {@code Optional} slot, and the coercion that
     * follows would turn the whole member into a {@code null} {@code Optional}: an empty box is the
     * honest answer, a null one is a {@code NullPointerException} at the call site.
     *
     * <p>The primitive variants come back holding their type's zero, which is what masking any
     * numeric member yields.
     */
    private Object maskOptional(Object value, PiiDescriptor declared, String path) {
        return switch (value) {
            case Optional<?> optional -> {
                if (optional.isEmpty()) {
                    yield optional;
                }
                Object inner = optional.get();
                Object masked = maskLeaf(inner, declared, inner.getClass(), path);
                yield masked == inner ? optional : Optional.ofNullable(masked);
            }
            case OptionalInt optional ->
                optional.isEmpty()
                        ? optional
                        : OptionalInt.of((int) maskedNumber(optional.getAsInt(), declared, path, int.class));
            case OptionalLong optional ->
                optional.isEmpty()
                        ? optional
                        : OptionalLong.of((long) maskedNumber(optional.getAsLong(), declared, path, long.class));
            case OptionalDouble optional ->
                optional.isEmpty()
                        ? optional
                        : OptionalDouble.of(
                                (double) maskedNumber(optional.getAsDouble(), declared, path, double.class));
            default -> value;
        };
    }

    private Object maskedNumber(Object value, PiiDescriptor declared, String path, Class<?> primitive) {
        return Coercion.toDeclaredType(maskLeaf(value, declared, primitive, path), primitive);
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
            // Declared for scanning, so its findings are the design working rather than a warning.
            return sanitizer.sanitizeDeclared(value.toString(), path);
        }

        Masker masker = descriptor.hasCustomMasker()
                ? maskers.forType(descriptor.maskerType())
                : maskers.forStrategy(descriptor.strategy());
        // The runtime class, not the declared one. A member declared Object or CharSequence says
        // nothing about what a masker has to handle, and a masker answering "no" is sent to
        // redaction — so asking about the declared type redacts values that would have masked
        // properly. The declared type still goes to the context: that is what the result has to fit.
        if (!masker.supports(value.getClass())) {
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
            case THROW -> throw MaskingException.atPath(path, "masker failed", failure);
            case PASS_THROUGH ->
                throw MaskingException.atPath(path, "PASS_THROUGH would disclose the value that failed to mask", failure);
        };
    }

    private Object onStructuralFailure(Object original, String path, Throwable failure) {
        observer.onFailure(path, failure);
        return switch (policy.failureMode()) {
            case REDACT -> null;
            case THROW -> throw MaskingException.atPath(path, "could not build a masked copy", failure);
            case PASS_THROUGH -> original;
        };
    }

    /**
     * A copy shaped like the source. The shape is not cosmetic: the masked collection has to be
     * assignable to the member it came from, and a {@code Deque} that came back as an
     * {@code ArrayList} fails that check and takes the whole field to null.
     *
     * <p>{@code List} is tested before {@code Deque} because {@code LinkedList} is both, and a
     * field declared {@code List} is the far more common of the two.
     */
    @SuppressWarnings("unchecked")
    private static Collection<Object> newCollectionLike(Collection<?> source) {
        if (source instanceof SortedSet<?> sorted) {
            return new TreeSet<>((Comparator<Object>) sorted.comparator());
        }
        if (source instanceof Set) {
            return new LinkedHashSet<>();
        }
        if (source instanceof List) {
            return new ArrayList<>(source.size());
        }
        if (source instanceof Deque || source instanceof Queue) {
            return new ArrayDeque<>(Math.max(1, source.size()));
        }
        return new ArrayList<>(source.size());
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> newMapLike(Map<?, ?> source) {
        if (source instanceof SortedMap<?, ?> sorted) {
            return new TreeMap<>((Comparator<Object>) sorted.comparator());
        }
        if (source instanceof ConcurrentMap) {
            return new ConcurrentHashMap<>();
        }
        return new LinkedHashMap<>();
    }
}
