package uk.gov.companieshouse.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import uk.gov.companieshouse.addresslookup.entity.RoyalMailAddressLookup;
import uk.gov.companieshouse.addresslookup.mapper.LegacyAddressMapper;
import uk.gov.companieshouse.addresslookup.model.LegacyAddress;

class LegacyAddressMapperTest {

	private final LegacyAddressMapper mapper = new LegacyAddressMapper() {
		@Override
		public LegacyAddress toLegacyAddress(RoyalMailAddressLookup address) {
			return null;
		}

		@Override
		public LegacyAddress toLegacyAddressWithoutPremise(RoyalMailAddressLookup address) {
			return null;
		}
	};

	@Test
	void join_shouldReturnEmptyString_whenAllPartsAreBlank() {
		assertEquals("", mapper.join(null, "", "   "));
	}

	@Test
	void join_shouldReturnJoinedValue_whenPartsContainText() {
		assertEquals("First, Second", mapper.join(" First ", null, "Second"));
	}
}
