package uk.gov.companieshouse.addresslookup.controller;

import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed annotation for standard API response codes.
 * Applies to all address lookup endpoints.
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses(value = {
    @ApiResponse(responseCode = "200", description = "Request successful"),
    @ApiResponse(responseCode = "400", description = "Invalid postcode"),
    @ApiResponse(responseCode = "404", description = "Address not found")
})
public @interface StandardApiResponses {
}
