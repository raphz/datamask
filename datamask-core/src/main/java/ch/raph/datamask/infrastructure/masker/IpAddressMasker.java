package ch.raph.datamask.infrastructure.masker;

import ch.raph.datamask.api.MaskContext;
import ch.raph.datamask.api.Masker;

/**
 * Drops the host portion of an address: {@code 192.168.4.37} becomes {@code 192.168.4.0}, and
 * {@code 2001:db8:85a3:8d3:1319:8a2e:370:7348} becomes {@code 2001:db8:85a3::}.
 *
 * <p>An IP address is personal data under GDPR, but the network prefix is what almost every
 * legitimate use — geolocation, rate limiting, abuse analysis — actually needs.
 */
public final class IpAddressMasker implements Masker {

    private static final int IPV6_GROUPS_VISIBLE = 3;

    @Override
    public Object mask(Object value, MaskContext context) {
        if (context.category().neverPartiallyReveal()) {
            return Masks.placeholder(context);
        }
        String text = Masks.text(value).trim();
        if (text.indexOf(':') >= 0) {
            return maskIpv6(text, context);
        }
        return maskIpv4(text, context);
    }

    private Object maskIpv4(String text, MaskContext context) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            return Masks.placeholder(context);
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !octet.chars().allMatch(Character::isDigit)) {
                return Masks.placeholder(context);
            }
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + ".0";
    }

    private Object maskIpv6(String text, MaskContext context) {
        String[] groups = text.split(":", -1);
        if (groups.length < 3) {
            return Masks.placeholder(context);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(IPV6_GROUPS_VISIBLE, groups.length); i++) {
            if (groups[i].isEmpty()) {
                break;
            }
            if (i > 0) {
                out.append(':');
            }
            out.append(groups[i]);
        }
        return out.isEmpty() ? Masks.placeholder(context) : out.append("::").toString();
    }
}
