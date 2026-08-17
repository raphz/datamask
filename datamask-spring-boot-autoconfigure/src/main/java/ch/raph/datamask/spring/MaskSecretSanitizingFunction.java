package ch.raph.datamask.spring;

import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;

/**
 * Keeps DataMask's own key material out of the Actuator endpoints that print configuration.
 *
 * <p>{@code /actuator/env} and {@code /actuator/configprops} hide values by default, but
 * {@code management.endpoint.env.show-values: always} is a setting internal deployments reach for
 * routinely, and once it is on the only thing standing between a property and the response body is
 * a {@link SanitizingFunction} that claims the key — Boot's {@code Sanitizer} applies no built-in
 * pattern of its own at that point. {@code datamask.secret} is the single value that reverses every
 * pseudonym this library issues, so it is exactly the one that must not be readable over HTTP from
 * whoever can reach the management port.
 *
 * <p>The match is deliberately shaped rather than exact. Relaxed binding means the same secret
 * appears as {@code datamask.secret} in {@code configprops}, and as whatever spelling the property
 * source used in {@code env} — {@code DATAMASK_SECRET} from the environment, {@code datamask-secret}
 * from a mounted config tree — and every one of those has to be covered. It also covers key material
 * this library has not shipped yet, because the failure of a rule listing one property name is
 * silent: the day a {@code datamask.vault.token} exists, nobody remembers this class.
 *
 * <p>{@code datamask.ephemeral-key} is the one exclusion. It ends in {@code key} but holds a boolean,
 * and whether a deployment is running under a random per-JVM key is precisely the thing an operator
 * opens {@code /env} to find out.
 */
final class MaskSecretSanitizingFunction implements SanitizingFunction {

    /**
     * A {@code datamask} property whose last segment names key material. Case-insensitive, and
     * tolerant of the three separators relaxed binding produces.
     */
    private static final Pattern KEY_MATERIAL = Pattern.compile(
            "^datamask[._-](?:[a-z0-9]+[._-])*(?:secret|secrets|key|keys|password|passphrase|token|tokens"
                    + "|credential|credentials)$",
            Pattern.CASE_INSENSITIVE);

    /** A flag, not key material, and worth reading: see the class Javadoc. */
    private static final Pattern NOT_KEY_MATERIAL =
            Pattern.compile("^datamask[._-]ephemeral[._-]key$", Pattern.CASE_INSENSITIVE);

    @Override
    public Predicate<SanitizableData> filter() {
        return data -> isKeyMaterial(data.getKey());
    }

    @Override
    public SanitizableData apply(SanitizableData data) {
        return data.withSanitizedValue();
    }

    /** Package-private so the rule can be asserted key by key rather than only through an endpoint. */
    static boolean isKeyMaterial(String key) {
        return KEY_MATERIAL.matcher(key).matches()
                && !NOT_KEY_MATERIAL.matcher(key).matches();
    }
}
