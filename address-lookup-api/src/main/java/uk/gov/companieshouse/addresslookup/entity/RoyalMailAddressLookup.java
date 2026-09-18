package uk.gov.companieshouse.addresslookup.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "royalmail_address_lookup")
public class RoyalMailAddressLookup {

    @Id
    private String id;

    @Column(name = "address_source")
    private String addressSource;

    private Integer udprn;

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

    @Column(name = "deliverypointsuffix")
    private String deliveryPointSuffix;

    private Long uprn;

    private BigDecimal latitude;

    private BigDecimal longitude;

    private String country;

    public String getId() {
        return id;
    }

    public String getAddressSource() {
        return addressSource;
    }

    public Integer getUdprn() {
        return udprn;
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

    public String getDeliveryPointSuffix() {
        return deliveryPointSuffix;
    }

    public Long getUprn() {
        return uprn;
    }

    public BigDecimal getLatitude() {
        return latitude;
    }

    public BigDecimal getLongitude() {
        return longitude;
    }

    public String getCountry() {
        return country;
    }
}
