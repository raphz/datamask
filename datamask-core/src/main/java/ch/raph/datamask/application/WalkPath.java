package ch.raph.datamask.application;

/**
 * Where the engine currently is in the graph, kept as one buffer that is pushed and popped as the
 * walk descends and unwinds, and turned into a {@code String} only where one is actually needed.
 *
 * <p>The engine used to concatenate a path per member: {@code path + "." + member.name()}, on every
 * member of every object, whether or not anything was ever reported about it. On a graph with no PII
 * in it — the common case, and the one the numbers say to optimise — that was the largest source of
 * allocation in the walk, spent producing strings nobody read. Paths are now materialised at the
 * three places that consume one: masking a value, reporting a finding, and reporting a failure.
 *
 * <p>Not thread-safe, and it does not need to be: an instance belongs to one call to
 * {@link MaskingEngine#mask}, and that call walks depth-first on one thread.
 *
 * <p><strong>Every push must be undone.</strong> A structural failure is caught mid-walk and the
 * parent carries on with its next member, so an unpaired push would misattribute every path that
 * follows it — which in this library means an observer signal naming the wrong field. Push sites use
 * {@code try/finally} around the descent for that reason.
 */
final class WalkPath {

    private final StringBuilder text;

    WalkPath(String root) {
        this.text = new StringBuilder(root.length() + 32).append(root);
    }

    /** The position to hand back to {@link #reset} once this subtree has been walked. */
    int mark() {
        return text.length();
    }

    void reset(int mark) {
        text.setLength(mark);
    }

    /**
     * Appends {@code .member}, or {@code Owner.member} at the root — a path that started nowhere
     * reads as a dangling {@code .email} otherwise, and the type is what makes it identifiable.
     */
    void member(Class<?> owner, String member) {
        if (text.isEmpty()) {
            text.append(owner.getSimpleName());
        }
        text.append('.').append(member);
    }

    /** Appends {@code [index]}, for a collection or array element. */
    void index(int index) {
        text.append('[').append(index).append(']');
    }

    /**
     * Appends {@code {index}}, for a map entry. Positional on purpose: a map is often keyed by
     * exactly the PII this library exists to hide, and the path reaches observers and exception
     * messages, so embedding the key would leak it through the reporting channel.
     */
    void entry(int index) {
        text.append('{').append(index).append('}');
    }

    /** Appends {@code {key}}, for the key of the entry already appended. */
    void key() {
        text.append("{key}");
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
