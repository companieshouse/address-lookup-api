package uk.gov.companieshouse.addresslookup.lamda.schema;

/** Credentials read from Parameter Store for a single invocation. Never logged. */
public record DatabaseCredentials(String username, String password) {

    public DatabaseCredentials {
        MigrationRequest.require(username != null && !username.isBlank(), "Database username is empty");
        MigrationRequest.require(password != null && !password.isBlank(), "Database password is empty");
    }

    @Override
    public String toString() {
        return "DatabaseCredentials[username=" + username + ", password=****]";
    }
}
