package com.exe.morphia3d.config;

import com.exe.morphia3d.service.pricing.PricingPolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Set;

@ConfigurationProperties(prefix = "app.pricing")
public class PricingPolicyProperties {
    private String policyVersion = "pricing-v1";
    private Set<String> approvedCommercialPolicyVersions = Set.of();
    private BigDecimal machineRateVndPerHour;
    private BigDecimal electricityRateVndPerKwh;
    private BigDecimal averagePowerKw;
    private BigDecimal laborCostVnd;
    private BigDecimal packagingCostVnd;
    private BigDecimal otherCostVnd;
    private BigDecimal failureRate;
    private BigDecimal targetMarginRate;
    private BigDecimal variableSellingFeeRate;
    private BigDecimal minimumSellingPriceVnd;
    private BigDecimal roundingIncrementVnd;

    public PricingPolicy policy() {
        return new PricingPolicy(machineRateVndPerHour, electricityRateVndPerKwh, averagePowerKw,
                laborCostVnd, packagingCostVnd, otherCostVnd, failureRate, targetMarginRate,
                variableSellingFeeRate, minimumSellingPriceVnd, roundingIncrementVnd);
    }

    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String value) { this.policyVersion = value; }
    public Set<String> getApprovedCommercialPolicyVersions() { return approvedCommercialPolicyVersions; }
    public void setApprovedCommercialPolicyVersions(Set<String> values) {
        this.approvedCommercialPolicyVersions = values == null ? Set.of() : Set.copyOf(values);
    }

    public BigDecimal getMachineRateVndPerHour() { return machineRateVndPerHour; }
    public void setMachineRateVndPerHour(BigDecimal value) { this.machineRateVndPerHour = value; }
    public BigDecimal getElectricityRateVndPerKwh() { return electricityRateVndPerKwh; }
    public void setElectricityRateVndPerKwh(BigDecimal value) { this.electricityRateVndPerKwh = value; }
    public BigDecimal getAveragePowerKw() { return averagePowerKw; }
    public void setAveragePowerKw(BigDecimal value) { this.averagePowerKw = value; }
    public BigDecimal getLaborCostVnd() { return laborCostVnd; }
    public void setLaborCostVnd(BigDecimal value) { this.laborCostVnd = value; }
    public BigDecimal getPackagingCostVnd() { return packagingCostVnd; }
    public void setPackagingCostVnd(BigDecimal value) { this.packagingCostVnd = value; }
    public BigDecimal getOtherCostVnd() { return otherCostVnd; }
    public void setOtherCostVnd(BigDecimal value) { this.otherCostVnd = value; }
    public BigDecimal getFailureRate() { return failureRate; }
    public void setFailureRate(BigDecimal value) { this.failureRate = value; }
    public BigDecimal getTargetMarginRate() { return targetMarginRate; }
    public void setTargetMarginRate(BigDecimal value) { this.targetMarginRate = value; }
    public BigDecimal getVariableSellingFeeRate() { return variableSellingFeeRate; }
    public void setVariableSellingFeeRate(BigDecimal value) { this.variableSellingFeeRate = value; }
    public BigDecimal getMinimumSellingPriceVnd() { return minimumSellingPriceVnd; }
    public void setMinimumSellingPriceVnd(BigDecimal value) { this.minimumSellingPriceVnd = value; }
    public BigDecimal getRoundingIncrementVnd() { return roundingIncrementVnd; }
    public void setRoundingIncrementVnd(BigDecimal value) { this.roundingIncrementVnd = value; }
}
