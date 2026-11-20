package com.exe.morphia3d.service.pricing;

import com.exe.morphia3d.config.PricingPolicyProperties;
import com.exe.morphia3d.dto.request.CreatePriceEstimateRequest;
import com.exe.morphia3d.dto.response.PriceEstimateResponse;
import com.exe.morphia3d.dto.response.PriceEstimateOptionResponse;
import com.exe.morphia3d.dto.response.PriceEstimateOptionsResponse;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.PriceEstimation;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.ValidatedSlicingRun;
import com.exe.morphia3d.enums.MaterialStatus;
import com.exe.morphia3d.enums.ModelStatus;
import com.exe.morphia3d.enums.ModelType;
import com.exe.morphia3d.enums.PrinterStatus;
import com.exe.morphia3d.repository.MaterialRepository;
import com.exe.morphia3d.repository.Model3dRepository;
import com.exe.morphia3d.repository.PriceEstimationRepository;
import com.exe.morphia3d.repository.PrinterRepository;
import com.exe.morphia3d.repository.ValidatedSlicingRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PriceEstimateService {
    private static final long ESTIMATE_VALID_MINUTES = 15;

    private final Model3dRepository modelRepository;
    private final MaterialRepository materialRepository;
    private final PrinterRepository printerRepository;
    private final ValidatedSlicingRunRepository slicingRunRepository;
    private final PriceEstimationRepository estimateRepository;
    private final PricingPolicyProperties policyProperties;
    private final PricingEngine pricingEngine = new PricingEngine();

    @Transactional
    public PriceEstimateResponse create(CreatePriceEstimateRequest request) {
        if (request == null || policyProperties.getPolicyVersion() == null
                || !policyProperties.getPolicyVersion().matches("[A-Za-z0-9._-]{1,40}")) {
            throw new PricingNotConfiguredException("policyVersion");
        }
        Model3d model = modelRepository.findByIdAndSourceTypeAndStatus(
                        request.modelId(), ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("MODEL_NOT_AVAILABLE"));
        Material material = materialRepository.findById(request.materialId())
                .orElseThrow(() -> new IllegalArgumentException("MATERIAL_NOT_AVAILABLE"));
        Printer printer = printerRepository.findById(request.printerId())
                .orElseThrow(() -> new IllegalArgumentException("PRINTER_NOT_AVAILABLE"));

        if (material.getIsAvailable() != MaterialStatus.AVAILABLE
                || material.getPricePerGram() == null
                || !Double.isFinite(material.getPricePerGram())
                || material.getPricePerGram() < 0
                || printer.getStatus() != PrinterStatus.ACTIVE) {
            throw new IllegalArgumentException("PRINT_CONFIGURATION_UNAVAILABLE");
        }
        ValidatedSlicingRun slicingRun = slicingRunRepository
                .findFirstByModelIdAndMaterialIdAndPrinterIdAndConfigurationFingerprintAndValidatedTrueOrderByValidatedAtDesc(
                        model.getId(), material.getId(), printer.getId(), request.configurationFingerprint())
                .orElseThrow(() -> new PricingNotConfiguredException("validatedSlicingDataForConfiguration"));
        if (slicingRun.getConsumedMaterialGrams() == null || slicingRun.getConsumedMaterialGrams().signum() <= 0
                || slicingRun.getPrintTimeSeconds() == null || slicingRun.getPrintTimeSeconds() <= 0
                || slicingRun.getValidatedAt() == null || slicingRun.getValidatedAt().isAfter(LocalDateTime.now())
                || slicingRun.getSource() == null || slicingRun.getSource().isBlank()
                || slicingRun.getConfigurationSnapshot() == null || slicingRun.getConfigurationSnapshot().isBlank()
                || !Objects.equals(request.configurationFingerprint(), slicingRun.getConfigurationFingerprint())) {
            throw new PricingNotConfiguredException("validatedSlicingData");
        }

        BigDecimal printTimeHours = BigDecimal.valueOf(slicingRun.getPrintTimeSeconds())
                .divide(BigDecimal.valueOf(3600), 12, java.math.RoundingMode.HALF_UP);
        PricingResult result = pricingEngine.calculate(new PricingInput(
                slicingRun.getConsumedMaterialGrams(), BigDecimal.valueOf(material.getPricePerGram()),
                printTimeHours), policyProperties.policy());
        long amountVnd;
        try {
            amountVnd = result.sellingPriceVnd().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("PRICE_OUT_OF_RANGE", exception);
        }
        // OrderService's existing amount path uses Double; cap each unit so max quantity 99 remains exact.
        if (amountVnd > 9_007_199_254_740_991L / 99) {
            throw new IllegalArgumentException("PRICE_OUT_OF_RANGE");
        }

        PriceEstimation estimate = new PriceEstimation();
        estimate.setModel(model);
        estimate.setMaterial(material);
        estimate.setPrinter(printer);
        estimate.setMaterialCost(result.materialCostVnd().doubleValue());
        estimate.setMaterialCostVnd(result.materialCostVnd());
        estimate.setPrintTimeHours(printTimeHours.doubleValue());
        estimate.setLaborCost(policyProperties.getLaborCostVnd().doubleValue());
        estimate.setMachineCostVnd(result.machineCostVnd());
        estimate.setElectricityCostVnd(result.electricityCostVnd());
        estimate.setProductionCostVnd(result.productionCostVnd());
        estimate.setFailureAdjustedCostVnd(result.failureAdjustedCostVnd());
        estimate.setTotalCostVnd(result.totalCostVnd());
        estimate.setTotalPriceVnd(amountVnd);
        estimate.setTotalPrice((double) amountVnd);
        estimate.setSlicingDataSource(slicingRun.getSource());
        estimate.setSlicingRun(slicingRun);
        estimate.setPolicyVersion(policyProperties.getPolicyVersion());
        estimate.setPricingPolicySnapshot(policySnapshot(policyProperties));

        PriceEstimation saved = estimateRepository.saveAndFlush(estimate);
        LocalDateTime estimatedAt = Objects.requireNonNullElseGet(saved.getEstimatedAt(), LocalDateTime::now);
        return new PriceEstimateResponse(saved.getId(), model.getId(), material.getId(), printer.getId(),
                result.materialCostVnd(), result.machineCostVnd(), result.electricityCostVnd(),
                result.productionCostVnd(), result.failureAdjustedCostVnd(), result.totalCostVnd(),
                amountVnd, policyProperties.getPolicyVersion(), estimatedAt.plusMinutes(ESTIMATE_VALID_MINUTES), "VALID");
    }

    @Transactional(readOnly = true)
    public PriceEstimateOptionsResponse options(Integer modelId) {
        Model3d model = modelRepository.findByIdAndSourceTypeAndStatus(modelId, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("MODEL_NOT_AVAILABLE"));
        Map<String, PriceEstimateOptionResponse> unique = new LinkedHashMap<>();
        slicingRunRepository.findAllByModelIdAndValidatedTrueOrderByValidatedAtDesc(model.getId()).stream()
                .filter(run -> run.getConfigurationFingerprint() != null && !run.getConfigurationFingerprint().isBlank())
                .filter(run -> run.getConfigurationSnapshot() != null && !run.getConfigurationSnapshot().isBlank()
                        && run.getSource() != null && !run.getSource().isBlank()
                        && run.getValidatedAt() != null && !run.getValidatedAt().isAfter(LocalDateTime.now())
                        && run.getConsumedMaterialGrams() != null && run.getConsumedMaterialGrams().signum() > 0
                        && run.getPrintTimeSeconds() != null && run.getPrintTimeSeconds() > 0)
                .filter(run -> run.getMaterial() != null && run.getMaterial().getIsAvailable() == MaterialStatus.AVAILABLE
                        && run.getMaterial().getPricePerGram() != null && Double.isFinite(run.getMaterial().getPricePerGram())
                        && run.getMaterial().getPricePerGram() >= 0)
                .filter(run -> run.getPrinter() != null && run.getPrinter().getStatus() == PrinterStatus.ACTIVE)
                .forEach(run -> unique.putIfAbsent(run.getMaterial().getId() + ":" + run.getPrinter().getId() + ":" + run.getConfigurationFingerprint(),
                        new PriceEstimateOptionResponse(run.getMaterial().getId(), run.getMaterial().getName(),
                                run.getPrinter().getId(), run.getPrinter().getName(), run.getConfigurationFingerprint())));
        return new PriceEstimateOptionsResponse(unique.values().stream().toList());
    }

    private String policySnapshot(PricingPolicyProperties p) {
        return "{\"policyVersion\":\"" + p.getPolicyVersion() + "\","
                + "\"machineRateVndPerHour\":" + jsonNumber(p.getMachineRateVndPerHour()) + ","
                + "\"electricityRateVndPerKwh\":" + jsonNumber(p.getElectricityRateVndPerKwh()) + ","
                + "\"averagePowerKw\":" + jsonNumber(p.getAveragePowerKw()) + ","
                + "\"laborCostVnd\":" + jsonNumber(p.getLaborCostVnd()) + ","
                + "\"packagingCostVnd\":" + jsonNumber(p.getPackagingCostVnd()) + ","
                + "\"otherCostVnd\":" + jsonNumber(p.getOtherCostVnd()) + ","
                + "\"failureRate\":" + jsonNumber(p.getFailureRate()) + ","
                + "\"targetMarginRate\":" + jsonNumber(p.getTargetMarginRate()) + ","
                + "\"variableSellingFeeRate\":" + jsonNumber(p.getVariableSellingFeeRate()) + ","
                + "\"minimumSellingPriceVnd\":" + jsonNumber(p.getMinimumSellingPriceVnd()) + ","
                + "\"roundingIncrementVnd\":" + jsonNumber(p.getRoundingIncrementVnd()) + "}";
    }

    private String jsonNumber(BigDecimal value) { return value == null ? "null" : value.toPlainString(); }
}
