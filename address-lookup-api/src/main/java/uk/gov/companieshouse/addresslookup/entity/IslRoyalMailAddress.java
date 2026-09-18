package uk.gov.companieshouse.addresslookup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "add_isl_royalmailaddress")
public class IslRoyalMailAddress {

    @Id
    private Integer udprn;

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

    @Column(name = "departmentname")
    private String departmentName;

    @Column(name = "subbuildingname")
    private String subBuildingName;

    @Column(name = "buildingname")
    private String buildingName;

    @Column(name = "buildingnumber")
    private Integer buildingNumber;

    @Column(name = "dependentthoroughfare")
    private String dependentThoroughfare;

    private String thoroughfare;

    @Column(name = "doubledependentlocality")
    private String doubleDependentLocality;

    @Column(name = "dependentlocality")
    private String dependentLocality;

    @Column(name = "posttown")
    private String postTown;

    private String postcode;

    @Column(name = "postcodetype")
    private String postcodeType;

    @Column(name = "deliverypointsuffix")
    private String deliveryPointSuffix;

    @Column(name = "welshdependentthoroughfare")
    private String welshDependentThoroughfare;

    @Column(name = "welshthoroughfare")
    private String welshThoroughfare;

    @Column(name = "welshdoubledependentlocality")
    private String welshDoubleDependentLocality;

    @Column(name = "welshdependentlocality")
    private String welshDependentLocality;

    @Column(name = "welshposttown")
    private String welshPostTown;

    @Column(name = "poboxnumber")
    private String poBoxNumber;

    @Column(name = "updatedate")
    private LocalDate updateDate;

    @Column(name = "entrydate")
    private LocalDate entryDate;

    private BigDecimal easting;

    private BigDecimal northing;

    private BigDecimal latitude;

    private BigDecimal longitude;

    @Column(name = "positionalaccuracy")
    private String positionalAccuracy;

    @Column(name = "geometryallocationmethod")
    private String geometryAllocationMethod;

    @Column(name = "unmatchedreason")
    private String unmatchedReason;

    @Column(name = "unmatchedreasondate")
    private LocalDate unmatchedReasonDate;

    private Long uprn;

    @Column(name = "matchedaddressfeaturetype")
    private String matchedAddressFeatureType;

    @Column(name = "matchtype")
    private String matchType;

    @Column(name = "matchdate")
    private LocalDate matchDate;

    @Column(name = "matchmethod")
    private String matchMethod;

    @Column(name = "matchingorganisation")
    private String matchingOrganisation;

    public Integer getUdprn() {
        return udprn;
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

    public String getDepartmentName() {
        return departmentName;
    }

    public String getSubBuildingName() {
        return subBuildingName;
    }

    public String getBuildingName() {
        return buildingName;
    }

    public Integer getBuildingNumber() {
        return buildingNumber;
    }

    public String getDependentThoroughfare() {
        return dependentThoroughfare;
    }

    public String getThoroughfare() {
        return thoroughfare;
    }

    public String getDoubleDependentLocality() {
        return doubleDependentLocality;
    }

    public String getDependentLocality() {
        return dependentLocality;
    }

    public String getPostTown() {
        return postTown;
    }

    public String getPostcode() {
        return postcode;
    }

    public String getPostcodeType() {
        return postcodeType;
    }

    public String getDeliveryPointSuffix() {
        return deliveryPointSuffix;
    }

    public String getWelshDependentThoroughfare() {
        return welshDependentThoroughfare;
    }

    public String getWelshThoroughfare() {
        return welshThoroughfare;
    }

    public String getWelshDoubleDependentLocality() {
        return welshDoubleDependentLocality;
    }

    public String getWelshDependentLocality() {
        return welshDependentLocality;
    }

    public String getWelshPostTown() {
        return welshPostTown;
    }

    public String getPoBoxNumber() {
        return poBoxNumber;
    }

    public LocalDate getUpdateDate() {
        return updateDate;
    }

    public LocalDate getEntryDate() {
        return entryDate;
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

    public String getGeometryAllocationMethod() {
        return geometryAllocationMethod;
    }

    public String getUnmatchedReason() {
        return unmatchedReason;
    }

    public LocalDate getUnmatchedReasonDate() {
        return unmatchedReasonDate;
    }

    public Long getUprn() {
        return uprn;
    }

    public String getMatchedAddressFeatureType() {
        return matchedAddressFeatureType;
    }

    public String getMatchType() {
        return matchType;
    }

    public LocalDate getMatchDate() {
        return matchDate;
    }

    public String getMatchMethod() {
        return matchMethod;
    }

    public String getMatchingOrganisation() {
        return matchingOrganisation;
    }
}
