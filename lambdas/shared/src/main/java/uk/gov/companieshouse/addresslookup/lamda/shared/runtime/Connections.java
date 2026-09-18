package uk.gov.companieshouse.addresslookup.lamda.shared.runtime;

import java.sql.Connection;

@FunctionalInterface
public interface Connections {
    /** Returns a dedicated connection with auto-commit disabled. */
    Connection open() throws Exception;
}
