package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * {@code john.doe@example.com} becomes {@code j*******@e******.com}.
 *
 * <p>The first character and the top-level domain survive because together they are what makes a
 * masked address recognisable to the person who owns it — a support agent can confirm "is it the
 * gmail one or the work one?" — while remaining useless for contacting or enumerating them.
 */
public final class EmailMasker implements Masker {

    @Override
    public Object mask(Object value, MaskContext context) {
        String text = Masks.text(value);
        int at = text.lastIndexOf('@');
        if (at <= 0 || at == text.length() - 1) {
            // Not a recognisable address; disclosing a "first character plus stars" shape of an
            // unknown string is not obviously safe, so redact it instead.
            return Masks.placeholder(context);
        }

        String local = text.substring(0, at);
        String domain = text.substring(at + 1);
        char padding = context.padding();

        String maskedLocal = local.charAt(0) + Masks.repeat(padding, local.length() - 1);

        int lastDot = domain.lastIndexOf('.');
        String maskedDomain = lastDot <= 0
                ? domain.charAt(0) + Masks.repeat(padding, domain.length() - 1)
                : domain.charAt(0) + Masks.repeat(padding, lastDot - 1) + domain.substring(lastDot);

        return maskedLocal + "@" + maskedDomain;
    }
}
