package com.realestate.api.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

@Entity
@Table(name = "real_estate_transaction")
public class RealEstateTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "region_code", nullable = false)
    private String regionCode;

    @Column(name = "legal_dong", nullable = false)
    private String legalDong;

    @Column(name = "apt_name", nullable = false)
    private String aptName;

    @Column(name = "exclusive_area", nullable = false)
    private BigDecimal exclusiveArea;

    @Column(name = "deal_amount", nullable = false)
    private Long dealAmount;

    @Column(name = "deal_year", nullable = false)
    private Integer dealYear;

    @Column(name = "deal_month", nullable = false)
    private Integer dealMonth;

    @Column(name = "deal_day", nullable = false)
    private Integer dealDay;

    @Column(name = "deal_ym", nullable = false)
    private Integer dealYm;

    @Column(name = "floor")
    private Integer floor;

    @Column(name = "build_year")
    private Integer buildYear;

    protected RealEstateTransaction() {
    }

    public Long getId() { return id; }
    public String getRegionCode() { return regionCode; }
    public String getLegalDong() { return legalDong; }
    public String getAptName() { return aptName; }
    public BigDecimal getExclusiveArea() { return exclusiveArea; }
    public Long getDealAmount() { return dealAmount; }
    public Integer getDealYear() { return dealYear; }
    public Integer getDealMonth() { return dealMonth; }
    public Integer getDealDay() { return dealDay; }
    public Integer getDealYm() { return dealYm; }
    public Integer getFloor() { return floor; }
    public Integer getBuildYear() { return buildYear; }
}
