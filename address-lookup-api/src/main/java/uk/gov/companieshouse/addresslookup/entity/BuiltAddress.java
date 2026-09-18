package uk.gov.companieshouse.addresslookup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@MappedSuperclass
public abstract class BuiltAddress {

    @Id
    private Long uprn;

    @Column(name = "versiondate")
    private LocalDate versionDate;

    @Column(name = "versionavailablefromdate")
    private LocalDateTime versionAvailableFromDate;

    @Column(name = "versionavailabletodate")
    private LocalDateTime versionAvailableToDate;

    @Column(name = "changetype")
    private String changeType;

    private String theme;

    private String description;

    @Column(name = "organisationname")
    private String organisationName;

    @Column(name = "poboxnumber")
    private String poBoxNumber;

    @Column(name = "subname")
    private String subName;

    private String name;

    @Column(name = "number")
    private String number;

    @Column(name = "streetname")
    private String streetName;

    private String locality;

    @Column(name = "townname")
    private String townName;

    @Column(name = "islandname")
    private String islandName;

    private String postcode;

    @Column(name = "fulladdress")
    private String fullAddress;

    private String country;

    @Column(name = "alternatelanguagesubname")
    private String alternateLanguageSubName;

    @Column(name = "alternatelanguagename")
    private String alternateLanguageName;

    @Column(name = "alternatelanguagenumber")
    private String alternateLanguageNumber;

    @Column(name = "alternatelanguagestreetname")
    private String alternateLanguageStreetName;

    @Column(name = "alternatelanguagelocality")
    private String alternateLanguageLocality;

    @Column(name = "alternatelanguagetownname")
    private String alternateLanguageTownName;

    @Column(name = "alternatelanguageislandname")
    private String alternateLanguageIslandName;

    @Column(name = "alternatelanguage")
    private String alternateLanguage;

    @Column(name = "alternatelanguagefulladdress")
    private String alternateLanguageFullAddress;

    @Column(name = "floorlevel")
    private String floorLevel;

    @Column(name = "lowestfloorlevel")
    private BigDecimal lowestFloorLevel;

    @Column(name = "highestfloorlevel")
    private BigDecimal highestFloorLevel;

    @Column(name = "classificationcode")
    private String classificationCode;

    @Column(name = "classificationdescription")
    private String classificationDescription;

    @Column(name = "primaryclassificationdescription")
    private String primaryClassificationDescription;

    @Column(name = "secondaryclassificationdescription")
    private String secondaryClassificationDescription;

    @Column(name = "tertiaryclassificationdescription")
    private String tertiaryClassificationDescription;

    @Column(name = "quaternaryclassificationdescription")
    private String quaternaryClassificationDescription;

    @Column(name = "buildstatus")
    private String buildStatus;

    @Column(name = "buildstatusdate")
    private LocalDate buildStatusDate;

    @Column(name = "addressstatus")
    private String addressStatus;

    @Column(name = "postcodesource")
    private String postcodeSource;

    @Column(name = "parentuprn")
    private Long parentUprn;

    @Column(name = "rootuprn")
    private Long rootUprn;

    @Column(name = "hierarchylevel")
    private Integer hierarchyLevel;

    private Integer usrn;

    @Column(name = "usrnmatchindicator")
    private String usrnMatchIndicator;

    @Column(name = "localcustodiancode")
    private Integer localCustodianCode;

    @Column(name = "localcustodiandescription")
    private String localCustodianDescription;

    @Column(name = "lowertierlocalauthoritygsscode")
    private String lowerTierLocalAuthorityGssCode;

    private BigDecimal easting;

    private BigDecimal northing;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Column(name = "positionalaccuracy")
    private String positionalAccuracy;

    @Column(name = "effectivestartdate")
    private LocalDate effectiveStartDate;

    @Column(name = "effectiveenddate")
    private LocalDate effectiveEndDate;

    public Long getUprn() {
        return uprn;
    }

    public LocalDate getVersionDate() {
        return versionDate;
    }

    public LocalDateTime getVersionAvailableFromDate() {
        return versionAvailableFromDate;
    }

    public LocalDateTime getVersionAvailableToDate() {
        return versionAvailableToDate;
    }

    public String getChangeType() {
        return changeType;
    }

    public String getTheme() {
        return theme;
    }

    public String getDescription() {
        return description;
    }

    public String getOrganisationName() {
        return organisationName;
    }

    public String getPoBoxNumber() {
        return poBoxNumber;
    }

    public String getSubName() {
        return subName;
    }

    public String getName() {
        return name;
    }

    public String getNumber() {
        return number;
    }

    public String getStreetName() {
        return streetName;
    }

    public String getLocality() {
        return locality;
    }

    public String getTownName() {
        return townName;
    }

    public String getIslandName() {
        return islandName;
    }

    public String getPostcode() {
        return postcode;
    }

    public String getFullAddress() {
        return fullAddress;
    }

    public String getCountry() {
        return country;
    }

    public String getAlternateLanguageSubName() {
        return alternateLanguageSubName;
    }

    public String getAlternateLanguageName() {
        return alternateLanguageName;
    }

    public String getAlternateLanguageNumber() {
        return alternateLanguageNumber;
    }

    public String getAlternateLanguageStreetName() {
        return alternateLanguageStreetName;
    }

    public String getAlternateLanguageLocality() {
        return alternateLanguageLocality;
    }

    public String getAlternateLanguageTownName() {
        return alternateLanguageTownName;
    }

    public String getAlternateLanguageIslandName() {
        return alternateLanguageIslandName;
    }

    public String getAlternateLanguage() {
        return alternateLanguage;
    }

    public String getAlternateLanguageFullAddress() {
        return alternateLanguageFullAddress;
    }

    public String getFloorLevel() {
        return floorLevel;
    }

    public BigDecimal getLowestFloorLevel() {
        return lowestFloorLevel;
    }

    public BigDecimal getHighestFloorLevel() {
        return highestFloorLevel;
    }

    public String getClassificationCode() {
        return classificationCode;
    }

    public String getClassificationDescription() {
        return classificationDescription;
    }

    public String getPrimaryClassificationDescription() {
        return primaryClassificationDescription;
    }

    public String getSecondaryClassificationDescription() {
        return secondaryClassificationDescription;
    }

    public String getTertiaryClassificationDescription() {
        return tertiaryClassificationDescription;
    }

    public String getQuaternaryClassificationDescription() {
        return quaternaryClassificationDescription;
    }

    public String getBuildStatus() {
        return buildStatus;
    }

    public LocalDate getBuildStatusDate() {
        return buildStatusDate;
    }

    public String getAddressStatus() {
        return addressStatus;
    }

    public String getPostcodeSource() {
        return postcodeSource;
    }

    public Long getParentUprn() {
        return parentUprn;
    }

    public Long getRootUprn() {
        return rootUprn;
    }

    public Integer getHierarchyLevel() {
        return hierarchyLevel;
    }

    public Integer getUsrn() {
        return usrn;
    }

    public String getUsrnMatchIndicator() {
        return usrnMatchIndicator;
    }

    public Integer getLocalCustodianCode() {
        return localCustodianCode;
    }

    public String getLocalCustodianDescription() {
        return localCustodianDescription;
    }

    public String getLowerTierLocalAuthorityGssCode() {
        return lowerTierLocalAuthorityGssCode;
    }

    public BigDecimal getEasting() {
        return easting;
    }

    public BigDecimal getNorthing() {
        return northing;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getPositionalAccuracy() {
        return positionalAccuracy;
    }

    public LocalDate getEffectiveStartDate() {
        return effectiveStartDate;
    }

    public LocalDate getEffectiveEndDate() {
        return effectiveEndDate;
    }
}
