/**
 * Spring Boot auto-configuration for DataMask.
 *
 * <p>{@link ch.raph.datamask.spring.DataMaskAutoConfiguration} builds the single
 * {@link ch.raph.datamask.application.DataMask} from {@code datamask.*} properties and from the
 * beans an application declared; the rest of this package wires that instance into whichever
 * integration modules are on the classpath, one auto-configuration each.
 *
 * <p>This module is the composition root, and is therefore the only one in the library allowed to
 * see more than one integration at a time. Nothing here is a masking decision — every decision has
 * already been made in {@code datamask-core} or in the integration module, and this package only
 * decides what exists.
 */
package ch.raph.datamask.spring;
