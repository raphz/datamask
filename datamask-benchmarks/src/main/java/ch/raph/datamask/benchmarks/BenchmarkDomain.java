package ch.raph.datamask.benchmarks;

import ch.raph.datamask.api.PII;
import ch.raph.datamask.api.PiiCategory;
import java.util.List;

/**
 * Two object graphs of the same shape: one whose members are declared PII, one that carries none.
 *
 * <p>Same depth, same member count, same collection sizes, same string lengths — because the pair is
 * only worth comparing if the only difference between them is whether anything has to be masked.
 * {@link Customer} is what the engine has work to do on; {@link Shipment} is the no-change
 * short-circuit, and its strings are chosen so that no detector matches any of them (which
 * {@link Fixtures#requireNothingDetected} checks before a measurement is taken, rather than trusting
 * that it stayed true).
 *
 * <p>These types are also what {@code datamask-build-processor} writes plans for: it runs on this
 * module's annotation path, so {@code Customer} is compiled beside a {@code
 * BenchmarkDomain_Customer_MaskPlan}, which is what lets {@link PlanCompilerBenchmark} measure the
 * generated compiler against the reflective one over the same types.
 */
public final class BenchmarkDomain {

    private BenchmarkDomain() {}

    /** The annotated graph: six masked members across three types and a two-element collection. */
    public record Customer(
            @PII(category = PiiCategory.FULL_NAME) String name,
            @PII(category = PiiCategory.EMAIL) String email,
            @PII(category = PiiCategory.IBAN) String iban,
            String country,
            Address address,
            List<Card> cards) {}

    public record Address(
            @PII(category = PiiCategory.POSTAL_ADDRESS) String street, String city, String postalRegion) {}

    public record Card(
            @PII(category = PiiCategory.PAN) String pan,
            @PII(category = PiiCategory.CARD_EXPIRY) String expiry) {}

    /** The same shape with nothing declared and nothing detectable: the short-circuit path. */
    public record Shipment(
            String reference, String service, String lane, String status, Depot depot, List<Parcel> parcels) {}

    public record Depot(String bay, String site, String zone) {}

    public record Parcel(String sku, String weight) {}

    static Customer customer() {
        return new Customer(
                "Jean Dupont",
                "jean.dupont@example.ch",
                "CH9300762011623852957",
                "CH",
                new Address("Bahnhofstrasse 12", "Zurich", "ZH"),
                List.of(new Card("4111111111111111", "11/29"), new Card("5500005555555559", "03/28")));
    }

    static Shipment shipment() {
        return new Shipment(
                "Overnight lane",
                "priority",
                "north",
                "in transit",
                new Depot("bay four", "Kloten", "west"),
                List.of(new Parcel("crate small", "light"), new Parcel("crate large", "heavy")));
    }
}
