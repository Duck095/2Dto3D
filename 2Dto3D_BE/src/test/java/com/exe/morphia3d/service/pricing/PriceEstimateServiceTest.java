package com.exe.morphia3d.service.pricing;

import com.exe.morphia3d.config.PricingPolicyProperties;
import com.exe.morphia3d.dto.request.CreatePriceEstimateRequest;
import com.exe.morphia3d.dto.response.PriceEstimateResponse;
import com.exe.morphia3d.entity.Material;
import com.exe.morphia3d.entity.Model3d;
import com.exe.morphia3d.entity.Printer;
import com.exe.morphia3d.entity.PriceEstimation;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.time.LocalDateTime;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
class PriceEstimateServiceTest {
    @Mock private Model3dRepository modelRepository;
    @Mock private MaterialRepository materialRepository;
    @Mock private PrinterRepository printerRepository;
    @Mock private ValidatedSlicingRunRepository slicingRunRepository;
    @Mock private PriceEstimationRepository estimateRepository;

    @Test
    void doesNotPersistEstimateWhenValidatedSlicerDataIsMissing() {
        Model3d model = Model3d.builder().id(21).sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.ACTIVE).build();
        Material material = Material.builder().id(4).pricePerGram(1200d)
                .isAvailable(MaterialStatus.AVAILABLE).build();
        Printer printer = Printer.builder().id(3).status(PrinterStatus.ACTIVE).build();
        when(modelRepository.findByIdAndSourceTypeAndStatus(21, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(model));
        when(materialRepository.findById(4)).thenReturn(Optional.of(material));
        when(printerRepository.findById(3)).thenReturn(Optional.of(printer));

        PriceEstimateService service = new PriceEstimateService(modelRepository, materialRepository,
                printerRepository, slicingRunRepository, estimateRepository, new PricingPolicyProperties());

        assertThatThrownBy(() -> service.create(new CreatePriceEstimateRequest(21, 4, 3, "config-sha256")))
                .isInstanceOf(PricingNotConfiguredException.class)
                .hasMessage("validatedSlicingDataForConfiguration");
        verify(estimateRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void doesNotPersistEstimateWhenPricingRatesAreNotConfigured() {
        Model3d model = Model3d.builder().id(21).sourceType(ModelType.DESIGNER_MODEL)
                .status(ModelStatus.ACTIVE)
                .validatedMaterialConsumedGrams(new java.math.BigDecimal("20"))
                .validatedPrintTimeSeconds(3600L).slicingDataSource("validated-slicer")
                .slicingDataValidatedAt(java.time.LocalDateTime.now()).build();
        Material material = Material.builder().id(4).pricePerGram(1200d)
                .isAvailable(MaterialStatus.AVAILABLE).build();
        Printer printer = Printer.builder().id(3).status(PrinterStatus.ACTIVE).build();
        when(modelRepository.findByIdAndSourceTypeAndStatus(21, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE))
                .thenReturn(Optional.of(model));
        when(materialRepository.findById(4)).thenReturn(Optional.of(material));
        when(printerRepository.findById(3)).thenReturn(Optional.of(printer));
        ValidatedSlicingRun run = new ValidatedSlicingRun();
        run.setId(8L);
        run.setModel(model);
        run.setMaterial(material);
        run.setPrinter(printer);
        run.setConfigurationFingerprint("config-sha256");
        run.setConfigurationSnapshot("{\"layerHeight\":0.2}");
        run.setConsumedMaterialGrams(new BigDecimal("20"));
        run.setPrintTimeSeconds(3600L);
        run.setSource("test-slicer");
        run.setValidatedAt(LocalDateTime.now().minusMinutes(1));
        run.setValidated(true);
        when(slicingRunRepository.findFirstByModelIdAndMaterialIdAndPrinterIdAndConfigurationFingerprintAndValidatedTrueOrderByValidatedAtDesc(
                21, 4, 3, "config-sha256")).thenReturn(Optional.of(run));

        PriceEstimateService service = new PriceEstimateService(modelRepository, materialRepository,
                printerRepository, slicingRunRepository, estimateRepository, new PricingPolicyProperties());

        assertThatThrownBy(() -> service.create(new CreatePriceEstimateRequest(21, 4, 3, "config-sha256")))
                .isInstanceOf(PricingNotConfiguredException.class)
                .hasMessage("machineRateVndPerHour");
        verify(estimateRepository, never()).saveAndFlush(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void persistsExactPolicySnapshotOnlyForMatchingValidatedRun() {
        Model3d model = Model3d.builder().id(21).sourceType(ModelType.DESIGNER_MODEL).status(ModelStatus.ACTIVE).build();
        Material material = Material.builder().id(4).pricePerGram(1200d).isAvailable(MaterialStatus.AVAILABLE).build();
        Printer printer = Printer.builder().id(3).status(PrinterStatus.ACTIVE).build();
        ValidatedSlicingRun run = new ValidatedSlicingRun();
        run.setId(8L);
        run.setModel(model);
        run.setMaterial(material);
        run.setPrinter(printer);
        run.setConfigurationFingerprint("config-sha256");
        run.setConfigurationSnapshot("{\"layerHeightMm\":0.2}");
        run.setConsumedMaterialGrams(new BigDecimal("20"));
        run.setPrintTimeSeconds(3600L);
        run.setSource("validated-slicer");
        run.setValidatedAt(LocalDateTime.now().minusMinutes(1));
        run.setValidated(true);
        when(modelRepository.findByIdAndSourceTypeAndStatus(21, ModelType.DESIGNER_MODEL, ModelStatus.ACTIVE)).thenReturn(Optional.of(model));
        when(materialRepository.findById(4)).thenReturn(Optional.of(material));
        when(printerRepository.findById(3)).thenReturn(Optional.of(printer));
        when(slicingRunRepository.findFirstByModelIdAndMaterialIdAndPrinterIdAndConfigurationFingerprintAndValidatedTrueOrderByValidatedAtDesc(
                21, 4, 3, "config-sha256")).thenReturn(Optional.of(run));
        when(estimateRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            PriceEstimation estimate = invocation.getArgument(0);
            estimate.setId(71);
            estimate.setEstimatedAt(LocalDateTime.now());
            return estimate;
        });

        PricingPolicyProperties policy = configuredPolicy();
        PriceEstimateResponse response = new PriceEstimateService(modelRepository, materialRepository,
                printerRepository, slicingRunRepository, estimateRepository, policy)
                .create(new CreatePriceEstimateRequest(21, 4, 3, "config-sha256"));

        assertThat(response.totalPriceVnd()).isEqualTo(76000L);
        assertThat(response.policyVersion()).isEqualTo("pricing-v1");
        verify(estimateRepository).saveAndFlush(argThat(estimate -> estimate.getSlicingRun() == run
                && "pricing-v1".equals(estimate.getPolicyVersion())
                && estimate.getPricingPolicySnapshot().contains("\"roundingIncrementVnd\":1000")
                && estimate.getTotalPriceVnd() == 76000L));
    }

    private PricingPolicyProperties configuredPolicy() {
        PricingPolicyProperties policy = new PricingPolicyProperties();
        policy.setMachineRateVndPerHour(new BigDecimal("10000"));
        policy.setElectricityRateVndPerKwh(new BigDecimal("3000"));
        policy.setAveragePowerKw(new BigDecimal("0.1"));
        policy.setLaborCostVnd(new BigDecimal("5000"));
        policy.setPackagingCostVnd(new BigDecimal("1000"));
        policy.setOtherCostVnd(new BigDecimal("1000"));
        policy.setFailureRate(new BigDecimal("0.1"));
        policy.setTargetMarginRate(new BigDecimal("0.3"));
        policy.setVariableSellingFeeRate(new BigDecimal("0.1"));
        policy.setMinimumSellingPriceVnd(BigDecimal.ZERO);
        policy.setRoundingIncrementVnd(new BigDecimal("1000"));
        return policy;
    }
}
